package application

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

type Handler func(context.Context, jobsdomain.Task) (jobsports.Completion, error)

type Engine struct {
	Store        *Store
	handler      Handler
	ctx          context.Context
	stop         context.CancelFunc
	wake         chan struct{}
	done         chan struct{}
	mutex        sync.Mutex
	activeID     string
	activeCancel context.CancelFunc
	settled      func()
	OnError      func(error)
}

func Start(ctx context.Context, store *Store, handler Handler, onError func(error)) (*Engine, error) {
	if err := store.Recover(ctx); err != nil {
		return nil, err
	}
	workerCtx, cancel := context.WithCancel(ctx)
	engine := &Engine{Store: store, handler: handler, ctx: workerCtx, stop: cancel, wake: make(chan struct{}, 1), done: make(chan struct{}), OnError: onError}
	go engine.loop()
	return engine, nil
}

func (engine *Engine) Wake() {
	select {
	case engine.wake <- struct{}{}:
	default:
	}
}

func (engine *Engine) Close() { engine.stop(); <-engine.done }

// ObserveCompletion 只发送轻量唤醒；状态仍以持久化查询为准。
func (engine *Engine) ObserveCompletion(wake func()) {
	engine.mutex.Lock()
	engine.settled = wake
	engine.mutex.Unlock()
}
func (engine *Engine) notifySettled() {
	engine.mutex.Lock()
	wake := engine.settled
	engine.mutex.Unlock()
	if wake != nil {
		wake()
	}
}

func (engine *Engine) Create(ctx context.Context, source, title, key string) (jobsdomain.Task, error) {
	if engine.ctx.Err() != nil {
		return jobsdomain.Task{}, jobsdomain.ErrStopped
	}
	task, err := engine.Store.Create(ctx, source, title, key)
	if err == nil {
		engine.Wake()
	}
	return task, err
}

func (engine *Engine) CreateArchive(ctx context.Context, source, title, key string) (jobsdomain.Task, error) {
	if engine.ctx.Err() != nil {
		return jobsdomain.Task{}, jobsdomain.ErrStopped
	}
	task, err := engine.Store.CreateArchive(ctx, source, title, key)
	if err == nil {
		engine.Wake()
	}
	return task, err
}

func (engine *Engine) Cancel(ctx context.Context, id string) error {
	err := engine.Store.Cancel(ctx, id)
	if err != nil {
		return err
	}
	engine.mutex.Lock()
	if engine.activeID == id && engine.activeCancel != nil {
		engine.activeCancel()
	}
	engine.mutex.Unlock()
	engine.notifySettled()
	return nil
}

func (engine *Engine) loop() {
	defer close(engine.done)
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	for engine.ctx.Err() == nil {
		task, err := engine.Store.claim(engine.ctx)
		if err == nil {
			engine.execute(task)
			continue
		}
		if !errors.Is(err, jobsdomain.ErrNotFound) && engine.ctx.Err() == nil && engine.OnError != nil {
			engine.OnError(errors.New("读取任务队列失败"))
		}
		select {
		case <-engine.ctx.Done():
			return
		case <-engine.wake:
		case <-ticker.C:
		}
	}
}

func (engine *Engine) execute(task jobsdomain.Task) {
	defer engine.notifySettled()
	ctx, cancel := context.WithCancel(engine.ctx)
	engine.mutex.Lock()
	engine.activeID = task.ID
	engine.activeCancel = cancel
	engine.mutex.Unlock()

	// 取消可能在领取之后、注册 activeCancel 之前到达，以持久状态补足这个窗口。
	current, err := engine.Store.Inspect(ctx, task.ID)
	if err == nil && current.Status == "cancelling" {
		cancel()
	}
	var completion jobsports.Completion
	if err == nil {
		completion, err = invoke(ctx, task, engine.handler)
	}
	if err == nil {
		err = engine.Store.Complete(ctx, task.ID, completion)
		if err != nil && jobsdomain.CodeOf(err) == jobsdomain.FailureUnknown {
			err = jobsdomain.NewFailure(jobsdomain.FailureCommit, err)
		}
	}
	cancel()
	engine.mutex.Lock()
	engine.activeCancel = nil
	engine.activeID = ""
	engine.mutex.Unlock()
	if err == nil {
		return
	}
	code := jobsdomain.CodeOf(err)
	failure := err
	if code == jobsdomain.FailureUnknown && (errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded)) {
		code = jobsdomain.FailureInterrupted
	}
	cleanup, release := context.WithTimeout(context.Background(), 5*time.Second)
	defer release()
	var terminal jobsdomain.Task
	if engine.ctx.Err() != nil {
		err = engine.Store.repository.Requeue(cleanup, task.ID)
	} else {
		terminal, err = engine.Store.finish(cleanup, task.ID, "failed", code, completion.PersistFailure)
	}
	if err != nil && engine.OnError != nil {
		engine.OnError(fmt.Errorf("任务 %s 状态保存失败", task.ID))
	} else if engine.OnError != nil && engine.ctx.Err() == nil {
		if terminal.Status == "failed" && terminal.ErrorCode != nil {
			engine.OnError(fmt.Errorf("任务 %s 第 %d 次执行失败（%s）: %w", task.ID, terminal.Attempt, *terminal.ErrorCode, jobsdomain.NewFailure(*terminal.ErrorCode, failure)))
		}
	}
}

func invoke(ctx context.Context, task jobsdomain.Task, handler Handler) (completion jobsports.Completion, err error) {
	defer func() {
		if value := recover(); value != nil {
			err = jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
		}
	}()
	completion, err = handler(ctx, task)
	if err == nil && completion.Persist == nil {
		return completion, jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
	}
	return completion, err
}
