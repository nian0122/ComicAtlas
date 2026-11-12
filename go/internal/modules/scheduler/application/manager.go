package application

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	schedulerports "comicatlas.local/backend/internal/modules/scheduler/application/ports"
	schedulerdomain "comicatlas.local/backend/internal/modules/scheduler/domain"
)

const (
	pollInterval = 500 * time.Millisecond
	retryDelay   = 5 * time.Second
	writeTimeout = 5 * time.Second
)

// Job 是代码注册的定时任务定义；页面只能管理已注册任务的启用、间隔和执行。
type Job struct {
	ID              string
	Title           string
	Description     string
	Unit            string
	DefaultInterval time.Duration
	DefaultEnabled  bool
	Timeout         time.Duration
	RunAtStartup    bool
	Run             func(context.Context) (int, error)
	ShouldRun       func(context.Context) (bool, error)
}

type state struct {
	job      Job
	settings schedulerdomain.Settings
	running  bool
	pending  bool
	nextRun  time.Time
}

type Manager struct {
	repository schedulerports.Repository
	mu         sync.Mutex
	ctx        context.Context
	cancel     context.CancelFunc
	done       chan struct{}
	wake       chan struct{}
	active     sync.WaitGroup
	onError    func(error)
	states     []*state
	byID       map[string]*state
}

func New(repository schedulerports.Repository) *Manager {
	return &Manager{repository: repository, byID: map[string]*state{}, wake: make(chan struct{}, 1)}
}

// Register 只接受唯一 ID，并要求间隔在允许范围内；应在 Start 之前调用。
func (manager *Manager) Register(job Job) error {
	if job.ID == "" || job.Title == "" || job.Run == nil || job.Timeout <= 0 {
		return schedulerdomain.ErrValidation
	}
	if err := schedulerdomain.ValidateInterval(job.DefaultInterval); err != nil {
		return err
	}
	manager.mu.Lock()
	defer manager.mu.Unlock()
	if manager.ctx != nil {
		return errors.New("定时器已启动，不能追加注册")
	}
	if _, exists := manager.byID[job.ID]; exists {
		return errors.New("定时器重复注册")
	}
	item := &state{job: job, settings: schedulerdomain.Settings{Enabled: job.DefaultEnabled, Interval: job.DefaultInterval}}
	manager.states = append(manager.states, item)
	manager.byID[job.ID] = item
	return nil
}

// Start 先恢复设置和历史，再开放任务受理。
func (manager *Manager) Start(parent context.Context, onError func(error)) error {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	if manager.ctx != nil {
		return errors.New("定时器已启动")
	}
	initialization, release := context.WithTimeout(parent, writeTimeout)
	defer release()
	settings, err := manager.repository.LoadSettings(initialization)
	if err != nil {
		return fmt.Errorf("读取定时器设置: %w", err)
	}
	now := time.Now()
	if err = manager.repository.InterruptRuns(initialization, schedulerdomain.Timestamp(now)); err != nil {
		return fmt.Errorf("恢复定时器执行记录: %w", err)
	}
	for _, item := range manager.states {
		if persisted, ok := settings[item.job.ID]; ok {
			if schedulerdomain.ValidateInterval(persisted.Interval) == nil {
				item.settings.Interval = persisted.Interval
			}
			item.settings.Enabled = persisted.Enabled
		}
		if item.settings.Enabled {
			item.nextRun = now.Add(item.settings.Interval)
		}
	}
	manager.ctx, manager.cancel = context.WithCancel(parent)
	manager.done = make(chan struct{})
	manager.onError = onError
	go manager.loop()
	return nil
}

func (manager *Manager) Close() {
	manager.mu.Lock()
	done := manager.done
	if done != nil {
		manager.cancel()
	}
	manager.mu.Unlock()
	if done != nil {
		<-done
	}
}

func (manager *Manager) loop() {
	defer func() {
		// 等持锁的受理提交结束，再等待已登记的执行。
		manager.mu.Lock()
		manager.cancel()
		manager.mu.Unlock()
		manager.active.Wait()
		close(manager.done)
	}()
	for _, item := range manager.states {
		if item.job.RunAtStartup {
			manager.reportDispatch(manager.start(item.job.ID, schedulerdomain.TriggerStartup))
		}
	}
	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-manager.ctx.Done():
			return
		case <-ticker.C:
			manager.tick()
		case <-manager.wake:
			manager.dispatchNotifications()
		}
	}
}

func (manager *Manager) tick() {
	for _, item := range manager.states {
		manager.reportDispatch(manager.start(item.job.ID, schedulerdomain.TriggerSchedule))
	}
}

func (manager *Manager) Trigger(id string) (schedulerdomain.Run, error) {
	return manager.start(id, schedulerdomain.TriggerManual)
}

// Notify 合并业务唤醒，数据库检查和任务受理由调度循环处理。
func (manager *Manager) Notify(id string) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	item := manager.byID[id]
	if item != nil && manager.available() && item.settings.Enabled {
		item.pending = true
		manager.signal()
	}
}

func (manager *Manager) signal() {
	select {
	case manager.wake <- struct{}{}:
	default:
	}
}

func (manager *Manager) dispatchNotifications() {
	for _, item := range manager.states {
		manager.mu.Lock()
		ready := manager.available() && item.settings.Enabled && item.pending && !item.running
		if ready {
			item.pending = false
		}
		manager.mu.Unlock()
		if !ready {
			continue
		}
		if item.job.ShouldRun != nil {
			check, release := context.WithTimeout(manager.ctx, writeTimeout)
			dirty, err := item.job.ShouldRun(check)
			release()
			manager.report(err)
			if err != nil || !dirty {
				continue
			}
		}
		manager.reportDispatch(manager.start(item.job.ID, schedulerdomain.TriggerAuto))
	}
}

func (manager *Manager) reportDispatch(_ schedulerdomain.Run, err error) {
	if !errors.Is(err, schedulerdomain.ErrConflict) && !errors.Is(err, schedulerdomain.ErrUnavailable) {
		manager.report(err)
	}
}

// available 由持有 mu 的调用者使用。
func (manager *Manager) available() bool {
	return manager.ctx != nil && manager.ctx.Err() == nil
}

func (manager *Manager) List(ctx context.Context, runsLimit int) ([]schedulerdomain.Schedule, error) {
	runsLimit = normalizeLimit(runsLimit, 5)
	snapshots := manager.snapshot()
	result := make([]schedulerdomain.Schedule, 0, len(snapshots))
	for _, snapshot := range snapshots {
		runs, err := manager.repository.Runs(ctx, snapshot.item.job.ID, runsLimit+1)
		if err != nil {
			return nil, err
		}
		result = append(result, manager.view(snapshot, runs, runsLimit))
	}
	return result, nil
}

func (manager *Manager) Get(ctx context.Context, id string, runsLimit int) (schedulerdomain.Schedule, error) {
	runsLimit = normalizeLimit(runsLimit, 20)
	for _, snapshot := range manager.snapshot() {
		if snapshot.item.job.ID != id {
			continue
		}
		runs, err := manager.repository.Runs(ctx, id, runsLimit+1)
		if err != nil {
			return schedulerdomain.Schedule{}, err
		}
		return manager.view(snapshot, runs, runsLimit), nil
	}
	return schedulerdomain.Schedule{}, schedulerdomain.ErrNotFound
}

// Update 修改启用状态或间隔并立即持久化；暂停时清除排期，恢复时按当前间隔重新排期。
func (manager *Manager) Update(ctx context.Context, id string, update schedulerdomain.Update) (schedulerdomain.Schedule, error) {
	if update.Enabled == nil && update.Interval == nil {
		return schedulerdomain.Schedule{}, schedulerdomain.ErrValidation
	}
	if update.Interval != nil {
		if err := schedulerdomain.ValidateInterval(*update.Interval); err != nil {
			return schedulerdomain.Schedule{}, err
		}
	}
	manager.mu.Lock()
	item := manager.byID[id]
	if item == nil {
		manager.mu.Unlock()
		return schedulerdomain.Schedule{}, schedulerdomain.ErrNotFound
	}
	if !manager.available() {
		manager.mu.Unlock()
		return schedulerdomain.Schedule{}, schedulerdomain.ErrUnavailable
	}
	settings := item.settings
	if update.Enabled != nil {
		settings.Enabled = *update.Enabled
	}
	if update.Interval != nil {
		settings.Interval = *update.Interval
	}
	write, release := context.WithTimeout(ctx, writeTimeout)
	err := manager.repository.SaveSettings(write, id, settings, schedulerdomain.Timestamp(time.Now()))
	release()
	if err != nil {
		manager.mu.Unlock()
		return schedulerdomain.Schedule{}, err
	}
	item.settings = settings
	if settings.Enabled {
		item.nextRun = time.Now().Add(settings.Interval)
	} else {
		item.nextRun = time.Time{}
		item.pending = false
	}
	manager.mu.Unlock()
	return manager.Get(ctx, id, 20)
}

func (manager *Manager) start(id, trigger string) (schedulerdomain.Run, error) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	item := manager.byID[id]
	if item == nil {
		return schedulerdomain.Run{}, schedulerdomain.ErrNotFound
	}
	if !manager.available() {
		return schedulerdomain.Run{}, schedulerdomain.ErrUnavailable
	}
	now := time.Now()
	if trigger != schedulerdomain.TriggerManual && !item.settings.Enabled {
		return schedulerdomain.Run{}, nil
	}
	scheduled := trigger == schedulerdomain.TriggerSchedule
	if scheduled && (item.nextRun.IsZero() || now.Before(item.nextRun)) {
		return schedulerdomain.Run{}, nil
	}
	if item.running {
		if trigger == schedulerdomain.TriggerAuto {
			item.pending = true
		}
		return schedulerdomain.Run{}, schedulerdomain.ErrConflict
	}
	run, err := manager.createRun(item, trigger, now)
	if scheduled {
		if err != nil {
			item.nextRun = time.Now().Add(retryDelay)
		} else {
			item.nextRun = now.Add(item.settings.Interval)
		}
	}
	if err != nil {
		return schedulerdomain.Run{}, err
	}
	item.running = true
	manager.active.Add(1)
	go manager.execute(item, run)
	return run, nil
}

func (manager *Manager) createRun(item *state, trigger string, now time.Time) (schedulerdomain.Run, error) {
	runID, err := schedulerdomain.NewRunID()
	if err != nil {
		return schedulerdomain.Run{}, err
	}
	run := schedulerdomain.Run{ID: runID, ScheduleID: item.job.ID, Trigger: trigger, StartedAt: schedulerdomain.Timestamp(now)}
	write, release := context.WithTimeout(manager.ctx, writeTimeout)
	defer release()
	if err = manager.repository.CreateRun(write, run, 200); err != nil {
		return schedulerdomain.Run{}, err
	}
	return run, nil
}

func (manager *Manager) execute(item *state, run schedulerdomain.Run) {
	defer manager.active.Done()
	ctx, cancel := context.WithTimeout(manager.ctx, item.job.Timeout)
	processed, err := invoke(ctx, item.job.Run)
	cancel()
	failure := ""
	switch {
	case errors.Is(err, context.Canceled):
		failure = "cancelled"
	case err != nil:
		failure = err.Error()
	}
	finished := schedulerdomain.Timestamp(time.Now())
	manager.finish(run.ID, finished, processed, failure)
	manager.mu.Lock()
	defer manager.mu.Unlock()
	item.running = false
	if item.pending {
		manager.signal()
	}
}

func invoke(ctx context.Context, run func(context.Context) (int, error)) (processed int, err error) {
	defer func() {
		if recover() != nil {
			err = errors.New("定时任务异常退出")
		}
	}()
	if err = ctx.Err(); err != nil {
		return 0, err
	}
	return run(ctx)
}

// 在终态落库前保留执行占用。退出时只做最后一次有界写入。
func (manager *Manager) finish(id, finished string, processed int, failure string) {
	for {
		stopping := manager.ctx.Err() != nil
		write, release := context.WithTimeout(context.WithoutCancel(manager.ctx), writeTimeout)
		err := manager.repository.FinishRun(write, id, finished, processed, failure)
		release()
		if err == nil {
			return
		}
		manager.report(fmt.Errorf("保存定时器执行结果 %s: %w", id, err))
		if stopping {
			return
		}
		retry := time.NewTimer(retryDelay)
		select {
		case <-manager.ctx.Done():
		case <-retry.C:
		}
		retry.Stop()
	}
}

type snapshot struct {
	item     *state
	settings schedulerdomain.Settings
	running  bool
	nextRun  time.Time
}

func (manager *Manager) snapshot() []snapshot {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	result := make([]snapshot, 0, len(manager.states))
	for _, item := range manager.states {
		result = append(result, snapshot{item: item, settings: item.settings, running: item.running, nextRun: item.nextRun})
	}
	return result
}

func (manager *Manager) view(value snapshot, runs []schedulerdomain.Run, runsLimit int) schedulerdomain.Schedule {
	if runs == nil {
		runs = []schedulerdomain.Run{}
	}
	schedule := schedulerdomain.Schedule{
		ID:              value.item.job.ID,
		Title:           value.item.job.Title,
		Description:     value.item.job.Description,
		Unit:            value.item.job.Unit,
		IntervalSeconds: int(value.settings.Interval / time.Second),
		Enabled:         value.settings.Enabled,
		Running:         value.running,
		Runs:            runs,
	}
	if !value.nextRun.IsZero() && value.settings.Enabled {
		next := schedulerdomain.Timestamp(value.nextRun)
		schedule.NextRunAt = &next
	}
	for index := range runs {
		if runs[index].FinishedAt != nil {
			run := runs[index]
			schedule.LastRun = &run
			break
		}
	}
	if len(schedule.Runs) > runsLimit {
		schedule.Runs = schedule.Runs[:runsLimit]
	}
	return schedule
}

func normalizeLimit(value, fallback int) int {
	if value < 1 {
		return fallback
	}
	if value > 100 {
		return 100
	}
	return value
}

func (manager *Manager) report(err error) {
	if err == nil {
		return
	}
	manager.mu.Lock()
	onError := manager.onError
	manager.mu.Unlock()
	if onError != nil {
		onError(err)
	}
}
