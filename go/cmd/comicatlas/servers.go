package main

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"time"
)

type endpoint struct {
	name     string
	server   *http.Server
	listener net.Listener
}

func listenServers(management, reading string, managementHandler, readingHandler http.Handler) ([]endpoint, error) {
	var result []endpoint
	for _, item := range []struct {
		name, address string
		handler       http.Handler
	}{{"management", management, managementHandler}, {"reading", reading, readingHandler}} {
		if item.address == "" {
			continue
		}
		listener, err := net.Listen("tcp", item.address)
		if err != nil {
			for _, opened := range result {
				opened.listener.Close()
			}
			return nil, err
		}
		server := &http.Server{Addr: item.address, Handler: item.handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
		result = append(result, endpoint{name: item.name, server: server, listener: listener})
	}
	return result, nil
}
func serveServers(ctx context.Context, endpoints []endpoint, timeout time.Duration, logger *slog.Logger) error {
	results := make(chan error, len(endpoints))
	for _, item := range endpoints {
		go func() { results <- item.server.Serve(item.listener) }()
		logger.Info("HTTP 入口启动", "access", item.name, "address", item.listener.Addr().String())
	}
	var cause error
	remaining := len(endpoints)
	select {
	case <-ctx.Done():
	case cause = <-results:
		remaining--
		if errors.Is(cause, http.ErrServerClosed) && ctx.Err() == nil {
			cause = errors.New("HTTP 入口意外关闭")
		}
	}
	shutdown, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var mutex sync.Mutex
	var failures []error
	var wait sync.WaitGroup
	for _, item := range endpoints {
		wait.Go(func() {
			if err := item.server.Shutdown(shutdown); err != nil {
				item.server.Close()
				mutex.Lock()
				failures = append(failures, err)
				mutex.Unlock()
			}
		})
	}
	wait.Wait()
	for range remaining {
		err := <-results
		if !errors.Is(err, http.ErrServerClosed) {
			failures = append(failures, err)
		}
	}
	if cause != nil && !errors.Is(cause, http.ErrServerClosed) {
		failures = append(failures, cause)
	}
	return errors.Join(failures...)
}
