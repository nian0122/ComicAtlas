package streamcopy

import (
	"context"
	"errors"
	"io"
	"sync"
)

const bufferSize = 64 << 10

type transferBuffer [bufferSize]byte

var transferBuffers = sync.Pool{New: func() any { return new(transferBuffer) }}

var (
	ErrSize  = errors.New("流字节数与预期不符")
	ErrRead  = errors.New("读取流失败")
	ErrWrite = errors.New("写入流失败")
)

// Exact 使用固定缓冲和真实 EOF 核对，不将整个内容读入内存。
// 调用方仍负责文件身份、时间、同步、关闭和失败文件清理。
func Exact(ctx context.Context, input io.Reader, output io.Writer, size int64) error {
	if size < 0 {
		return ErrSize
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	limited := io.LimitReader(input, size)
	buffer := transferBuffers.Get().(*transferBuffer)
	defer transferBuffers.Put(buffer)
	var copied int64
	empty := 0
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		n, err := limited.Read(buffer[:])
		if n > 0 {
			empty = 0
			written, writeErr := output.Write(buffer[:n])
			if writeErr != nil || written != n {
				return ErrWrite
			}
			copied += int64(n)
		} else if err == nil {
			empty++
			if empty >= 100 {
				return ErrRead
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return ErrRead
		}
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	var extra [1]byte
	n, err := input.Read(extra[:])
	if copied != size || n != 0 || err != io.EOF {
		return ErrSize
	}
	return ctx.Err()
}
