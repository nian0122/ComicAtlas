package process

import "time"

// Request 只接受绝对可执行路径和独立参数，不经过命令解释器。
type Request struct {
	Executable string
	Args       []string
	Directory  string
	Env        []string
	Timeout    time.Duration
}

// Result 分别保留两个输出流的前缀；超过上限的内容继续排空。
type Result struct {
	Stdout          []byte
	Stderr          []byte
	StdoutTruncated bool
	StderrTruncated bool
	ExitCode        int
}
