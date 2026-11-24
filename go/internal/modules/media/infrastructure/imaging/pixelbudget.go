package imaging

import (
	"context"
	"sync"
)

// pixelBudget 按解码像素权重分配共享预算，容量单位为一份并发预算。
// 等待队列先进先出，大幅图片不会因为后续小图持续到达而长期等不到全部权重。
type pixelBudget struct {
	mutex sync.Mutex
	total int64
	used  int64
	queue []*budgetWaiter
}

type budgetWaiter struct {
	units   int64
	ready   chan struct{}
	granted bool
	removed bool
}

func newPixelBudget(total int64) *pixelBudget {
	return &pixelBudget{total: max(total, 1)}
}

// acquire 取得权重对应的预算；超过总预算的请求按总预算独占，避免永久等待。
func (budget *pixelBudget) acquire(ctx context.Context, units int64) (func(), error) {
	units = min(max(units, 1), budget.total)
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	waiter := &budgetWaiter{units: units, ready: make(chan struct{})}
	budget.mutex.Lock()
	budget.queue = append(budget.queue, waiter)
	budget.dispatch()
	budget.mutex.Unlock()
	select {
	case <-waiter.ready:
		return func() { budget.release(units) }, nil
	case <-ctx.Done():
		budget.mutex.Lock()
		if waiter.granted {
			budget.mutex.Unlock()
			return func() { budget.release(units) }, nil
		}
		waiter.removed = true
		budget.dispatch()
		budget.mutex.Unlock()
		return nil, ctx.Err()
	}
}

func (budget *pixelBudget) release(units int64) {
	budget.mutex.Lock()
	budget.used -= units
	budget.dispatch()
	budget.mutex.Unlock()
}

func (budget *pixelBudget) dispatch() {
	for len(budget.queue) > 0 {
		head := budget.queue[0]
		if head.removed {
			budget.queue = budget.queue[1:]
			continue
		}
		if budget.used+head.units > budget.total {
			return
		}
		budget.queue = budget.queue[1:]
		budget.used += head.units
		head.granted = true
		close(head.ready)
	}
}
