package process

import (
	"errors"
	"os/exec"
	"syscall"
)

type processTree struct{ pid int }

func startTree(command *exec.Cmd) (*processTree, error) {
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if command.Start() != nil {
		return nil, ErrStart
	}
	return &processTree{pid: command.Process.Pid}, nil
}

func (t *processTree) stop() error {
	err := syscall.Kill(-t.pid, syscall.SIGKILL)
	if errors.Is(err, syscall.ESRCH) {
		return nil
	}
	return err
}

func (t *processTree) close() {}
