package domain

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"time"
)

var (
	ErrNotFound    = errors.New("定时器不存在")
	ErrConflict    = errors.New("定时器正在执行")
	ErrValidation  = errors.New("定时器参数无效")
	ErrUnavailable = errors.New("定时器暂不可用")
)

const (
	TriggerStartup  = "startup"
	TriggerSchedule = "schedule"
	TriggerManual   = "manual"
	TriggerAuto     = "auto"
)

const (
	MinInterval = time.Minute
	MaxInterval = 7 * 24 * time.Hour
)

// TimestampFormat 与任务系统一致，保证字符串排序即时间排序。
const TimestampFormat = "2006-01-02T15:04:05.000000000Z"

func ValidateInterval(value time.Duration) error {
	if value < MinInterval || value > MaxInterval {
		return ErrValidation
	}
	return nil
}

func Timestamp(at time.Time) string { return at.UTC().Format(TimestampFormat) }

func NewRunID() (string, error) {
	var buffer [16]byte
	if _, err := rand.Read(buffer[:]); err != nil {
		return "", err
	}
	return "schedule_run_" + hex.EncodeToString(buffer[:]), nil
}
