package localai

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type coordinatedBackend struct {
	mu                 sync.Mutex
	backgroundCalls    int
	backgroundStarted  chan int
	interactiveStarted chan struct{}
	releaseInteractive chan struct{}
}

func (b *coordinatedBackend) Status(context.Context) bool { return true }
func (b *coordinatedBackend) State(context.Context) RuntimeState {
	return RuntimeState{Availability: "ready", Capabilities: b.Capabilities()}
}
func (b *coordinatedBackend) Capabilities() map[string]any { return map[string]any{"modelId": "test"} }
func (b *coordinatedBackend) StreamChat(ctx context.Context, _ []Message, _ ChatOptions, yield func(Event) error) error {
	if workloadFromContext(ctx) == WorkloadInteractive {
		close(b.interactiveStarted)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-b.releaseInteractive:
			return yield(Event{Type: "delta", Text: "answer"})
		}
	}
	b.mu.Lock()
	b.backgroundCalls++
	call := b.backgroundCalls
	b.mu.Unlock()
	b.backgroundStarted <- call
	if call == 1 {
		<-ctx.Done()
		return ctx.Err()
	}
	return yield(Event{Type: "delta", Text: "background"})
}

func TestCoordinatorPreemptsBackgroundAndLetsItResumeAfterInteractiveWork(t *testing.T) {
	backend := &coordinatedBackend{
		backgroundStarted:  make(chan int, 2),
		interactiveStarted: make(chan struct{}),
		releaseInteractive: make(chan struct{}),
	}
	coordinator := NewCoordinator(backend)
	backgroundResult := make(chan error, 1)
	go func() {
		backgroundResult <- coordinator.StreamChat(
			WithWorkload(context.Background(), WorkloadBackground), nil, ChatOptions{}, func(Event) error { return nil },
		)
	}()
	select {
	case call := <-backend.backgroundStarted:
		if call != 1 {
			t.Fatalf("first background call = %d", call)
		}
	case <-time.After(time.Second):
		t.Fatal("background inference did not start")
	}

	interactiveResult := make(chan error, 1)
	go func() {
		interactiveResult <- coordinator.StreamChat(context.Background(), nil, ChatOptions{}, func(Event) error { return nil })
	}()
	select {
	case <-backend.interactiveStarted:
	case <-time.After(time.Second):
		t.Fatal("interactive inference did not start after preemption")
	}
	if err := <-backgroundResult; !errors.Is(err, ErrBackgroundPreempted) {
		t.Fatalf("preempted background error = %v", err)
	}

	resumedResult := make(chan error, 1)
	go func() {
		resumedResult <- coordinator.StreamChat(
			WithWorkload(context.Background(), WorkloadBackground), nil, ChatOptions{}, func(Event) error { return nil },
		)
	}()
	select {
	case call := <-backend.backgroundStarted:
		t.Fatalf("background call %d started before interactive inference finished", call)
	case <-time.After(50 * time.Millisecond):
	}
	close(backend.releaseInteractive)
	if err := <-interactiveResult; err != nil {
		t.Fatal(err)
	}
	select {
	case call := <-backend.backgroundStarted:
		if call != 2 {
			t.Fatalf("resumed background call = %d", call)
		}
	case <-time.After(time.Second):
		t.Fatal("background inference did not resume")
	}
	if err := <-resumedResult; err != nil {
		t.Fatal(err)
	}
}
