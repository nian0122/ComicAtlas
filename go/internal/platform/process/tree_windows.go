package process

import (
	"errors"
	"os/exec"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

type processTree struct{ job windows.Handle }

func startTree(command *exec.Cmd) (*processTree, error) {
	// 挂起启动，加入 Job 后才恢复，避免启动到约束之间产生逃逸子进程。
	if strings.HasSuffix(strings.ToLower(command.Path), ".cmd") || strings.HasSuffix(strings.ToLower(command.Path), ".bat") {
		return nil, ErrInvalid
	}
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return nil, ErrContainment
	}
	tree := &processTree{job: job}
	limits := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	limits.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	_, err = windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&limits)), uint32(unsafe.Sizeof(limits)))
	if err != nil {
		tree.close()
		return nil, ErrContainment
	}
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_SUSPENDED}
	if err = command.Start(); err != nil {
		tree.close()
		return nil, ErrStart
	}
	process, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(command.Process.Pid))
	if err == nil {
		err = windows.AssignProcessToJobObject(job, process)
		windows.CloseHandle(process)
	}
	if err == nil {
		err = resumeProcess(uint32(command.Process.Pid))
	}
	if err != nil {
		_ = command.Process.Kill()
		tree.close()
		_ = command.Wait()
		return nil, ErrContainment
	}
	return tree, nil
}

func resumeProcess(pid uint32) error {
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPTHREAD, 0)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(snapshot)
	entry := windows.ThreadEntry32{Size: uint32(unsafe.Sizeof(windows.ThreadEntry32{}))}
	err = windows.Thread32First(snapshot, &entry)
	for err == nil {
		if entry.OwnerProcessID == pid {
			thread, openErr := windows.OpenThread(windows.THREAD_SUSPEND_RESUME, false, entry.ThreadID)
			if openErr != nil {
				return openErr
			}
			_, resumeErr := windows.ResumeThread(thread)
			windows.CloseHandle(thread)
			return resumeErr
		}
		err = windows.Thread32Next(snapshot, &entry)
	}
	return errors.New("找不到挂起的工具线程")
}

func (t *processTree) stop() error { return windows.TerminateJobObject(t.job, 1) }
func (t *processTree) close()      { _ = windows.CloseHandle(t.job) }
