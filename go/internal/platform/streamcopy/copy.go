package streamcopy

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
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
func Exact(ctx context.Context, input io.Reader, output io.Writer, size int64) (Digest, error) {
	if size < 0 {
		return Digest{}, ErrSize
	}
	if err := ctx.Err(); err != nil {
		return Digest{}, err
	}
	limited := io.LimitReader(input, size)
	hash := sha256.New()
	buffer := transferBuffers.Get().(*transferBuffer)
	defer transferBuffers.Put(buffer)
	var copied int64
	empty := 0
	for {
		if err := ctx.Err(); err != nil {
			return Digest{}, err
		}
		n, err := limited.Read(buffer[:])
		if n > 0 {
			empty = 0
			written, writeErr := output.Write(buffer[:n])
			if writeErr != nil || written != n {
				return Digest{}, ErrWrite
			}
			hash.Write(buffer[:n])
			copied += int64(n)
		} else if err == nil {
			empty++
			if empty >= 100 {
				return Digest{}, ErrRead
			}
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return Digest{}, ErrRead
		}
	}
	if err := ctx.Err(); err != nil {
		return Digest{}, err
	}
	var extra [1]byte
	n, err := input.Read(extra[:])
	if copied != size || n != 0 || err != io.EOF {
		return Digest{}, ErrSize
	}
	if err := ctx.Err(); err != nil {
		return Digest{}, err
	}
	return Digest{Size: copied, SHA256: hex.EncodeToString(hash.Sum(nil))}, nil
}
