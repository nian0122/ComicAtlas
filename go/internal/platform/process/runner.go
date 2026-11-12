// Package process 提供有界外部工具执行与进程树生命周期管理。
package process

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var (
	ErrInvalid     = errors.New("无效的工具执行参数")
	ErrStart       = errors.New("工具启动失败")
	ErrExit        = errors.New("工具执行失败")
	ErrContainment = errors.New("工具进程树管理失败")
)

type Runner struct {
	slots       chan struct{}
	outputLimit int
}

// New 限制整个执行器的并发数以及每个输出流的内存占用。
func New(concurrency, outputLimit int) (*Runner, error) {
	if concurrency < 1 || concurrency > 16 || outputLimit < 1 || outputLimit > 1<<20 {
		return nil, ErrInvalid
	}
	return &Runner{slots: make(chan struct{}, concurrency), outputLimit: outputLimit}, nil
}

// Run 的超时包含排队时间；调用者必须明确提供环境变量，不继承宿主环境。
func (r *Runner) Run(parent context.Context, request Request) (Result, error) {
	result := Result{ExitCode: -1}
	if !filepath.IsAbs(request.Executable) || request.Timeout <= 0 || request.Timeout > 24*time.Hour ||
		(request.Directory != "" && !filepath.IsAbs(request.Directory)) {
		return result, ErrInvalid
	}
	for _, variable := range request.Env {
		key, _, ok := strings.Cut(variable, "=")
		if !ok || key == "" || strings.ContainsRune(variable, 0) {
			return result, ErrInvalid
		}
	}
	ctx, cancel := context.WithTimeout(parent, request.Timeout)
	defer cancel()
	select {
	case r.slots <- struct{}{}:
		defer func() { <-r.slots }()
	case <-ctx.Done():
		return result, ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	command := exec.Command(request.Executable, request.Args...)
	command.Dir = request.Directory
	command.Env = append([]string{}, request.Env...)
	// 自己管理管道：根进程退出后先回收后代，再等待输出流关闭。
	outRead, outWrite, err := os.Pipe()
	if err != nil {
		return result, ErrStart
	}
	defer outRead.Close()
	defer outWrite.Close()
	errRead, errWrite, err := os.Pipe()
	if err != nil {
		return result, ErrStart
	}
	defer errRead.Close()
	defer errWrite.Close()
	command.Stdout, command.Stderr = outWrite, errWrite
	tree, err := startTree(command)
	if err != nil {
		return result, err
	}
	outWrite.Close()
	errWrite.Close()
	stdout := &boundedOutput{limit: r.outputLimit}
	stderr := &boundedOutput{limit: r.outputLimit}
	var drains sync.WaitGroup
	drains.Add(2)
	go func() { defer drains.Done(); _, _ = io.Copy(stdout, outRead) }()
	go func() { defer drains.Done(); _, _ = io.Copy(stderr, errRead) }()
	wait := make(chan error, 1)
	go func() { wait <- command.Wait() }()
	var runErr, cleanupErr error
	select {
	case runErr = <-wait:
		cleanupErr = tree.stop()
	case <-ctx.Done():
		runErr = ctx.Err()
		// 即使回收失败，也结束根进程并释放 Wait 资源。
		if cleanupErr = tree.stop(); cleanupErr != nil {
			_ = command.Process.Kill()
		}
		<-wait
	}
	tree.close()
	// 回收异常时也不能被未关闭的继承管道无限阻塞。
	drained := make(chan struct{})
	go func() { drains.Wait(); close(drained) }()
	select {
	case <-drained:
	case <-time.After(time.Second):
		outRead.Close()
		errRead.Close()
		<-drained
		cleanupErr = ErrContainment
	}
	result = Result{Stdout: stdout.data, Stderr: stderr.data, StdoutTruncated: stdout.truncated,
		StderrTruncated: stderr.truncated, ExitCode: command.ProcessState.ExitCode()}
	if cleanupErr != nil {
		return result, ErrContainment
	}
	if errors.Is(runErr, context.Canceled) || errors.Is(runErr, context.DeadlineExceeded) {
		return result, runErr
	}
	if runErr != nil {
		return result, fmt.Errorf("%w（退出码 %d）", ErrExit, result.ExitCode)
	}
	return result, nil
}

type boundedOutput struct {
	data      []byte
	limit     int
	truncated bool
}

func (b *boundedOutput) Write(data []byte) (int, error) {
	remaining := b.limit - len(b.data)
	if len(data) > remaining {
		b.truncated = true
	}
	b.data = append(b.data, data[:min(len(data), remaining)]...)
	return len(data), nil
}
