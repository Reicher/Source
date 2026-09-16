package localai

import (
	"context"
	"errors"
	"sync"
)

type Workload string

const (
	WorkloadInteractive Workload = "interactive"
	WorkloadBackground  Workload = "background"
)

var (
	ErrBackgroundPreempted = errors.New("background inference was preempted")
	ErrStreamComplete      = errors.New("stream result is complete")
)

type workloadContextKey struct{}

func WithWorkload(ctx context.Context, workload Workload) context.Context {
	return context.WithValue(ctx, workloadContextKey{}, workload)
}

func workloadFromContext(ctx context.Context) Workload {
	if workload, ok := ctx.Value(workloadContextKey{}).(Workload); ok && workload == WorkloadBackground {
		return WorkloadBackground
	}
	return WorkloadInteractive
}

// Coordinator serializes access to a one-slot model runtime. Interactive work
// preempts background inference, while background callers wait until all
// interactive callers have finished and then resume from their durable layer.
type Coordinator struct {
	backend Backend

	mu                 sync.Mutex
	changed            chan struct{}
	active             bool
	activeID           uint64
	activeWorkload     Workload
	activeCancel       context.CancelCauseFunc
	interactiveWaiters int
}

func NewCoordinator(backend Backend) *Coordinator {
	return &Coordinator{backend: backend, changed: make(chan struct{})}
}

func (c *Coordinator) Status(ctx context.Context) bool        { return c.backend.Status(ctx) }
func (c *Coordinator) State(ctx context.Context) RuntimeState { return c.backend.State(ctx) }
func (c *Coordinator) Capabilities() map[string]any           { return c.backend.Capabilities() }

func (c *Coordinator) StreamChat(
	ctx context.Context,
	messages []Message,
	options ChatOptions,
	yield func(Event) error,
) error {
	workload := workloadFromContext(ctx)
	runCtx, release, err := c.acquire(ctx, workload)
	if err != nil {
		return err
	}
	defer release()

	err = c.backend.StreamChat(runCtx, messages, options, yield)
	if errors.Is(context.Cause(runCtx), ErrBackgroundPreempted) {
		return ErrBackgroundPreempted
	}
	return err
}

func (c *Coordinator) acquire(ctx context.Context, workload Workload) (context.Context, func(), error) {
	c.mu.Lock()
	if workload == WorkloadInteractive {
		c.interactiveWaiters++
		if c.active && c.activeWorkload == WorkloadBackground && c.activeCancel != nil {
			c.activeCancel(ErrBackgroundPreempted)
		}
	}
	for c.active || (workload == WorkloadBackground && c.interactiveWaiters > 0) {
		changed := c.changed
		c.mu.Unlock()
		select {
		case <-ctx.Done():
			c.mu.Lock()
			if workload == WorkloadInteractive {
				c.interactiveWaiters--
				c.notifyLocked()
			}
			c.mu.Unlock()
			return nil, nil, context.Cause(ctx)
		case <-changed:
		}
		c.mu.Lock()
	}
	if workload == WorkloadInteractive {
		c.interactiveWaiters--
	}
	c.activeID++
	id := c.activeID
	runCtx, cancel := context.WithCancelCause(ctx)
	c.active = true
	c.activeWorkload = workload
	c.activeCancel = cancel
	c.notifyLocked()
	c.mu.Unlock()

	release := func() {
		cancel(nil)
		c.mu.Lock()
		if c.active && c.activeID == id {
			c.active = false
			c.activeWorkload = ""
			c.activeCancel = nil
			c.notifyLocked()
		}
		c.mu.Unlock()
	}
	return runCtx, release, nil
}

func (c *Coordinator) notifyLocked() {
	close(c.changed)
	c.changed = make(chan struct{})
}
