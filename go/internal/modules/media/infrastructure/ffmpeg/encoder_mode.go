package ffmpeg

import "fmt"

// EncoderMode 只允许配置中明确列出的编码器；auto 优先尝试 NVENC，失败后回退 CPU。
type EncoderMode string

const (
	EncoderAuto  EncoderMode = "auto"
	EncoderX264  EncoderMode = "libx264"
	EncoderNVENC EncoderMode = "h264_nvenc"
)

func ParseEncoderMode(value string) (EncoderMode, error) {
	switch EncoderMode(value) {
	case EncoderAuto, EncoderX264, EncoderNVENC:
		return EncoderMode(value), nil
	default:
		return "", fmt.Errorf("未知转码编码器 %q", value)
	}
}
