package domain

import "math"

func DefaultImageSpec() ImageSpec { return ImageSpec{MaxLongEdge: 3840, Quality: 70} }

func ValidateImageSpec(spec ImageSpec) error {
	if spec.MaxLongEdge < 1 || spec.MaxLongEdge > 16383 || spec.Quality < 1 || spec.Quality > 100 {
		return ErrImageInvalid
	}
	return nil
}

// PlanImage 在解码前决定输出尺寸、缩放路径和整帧解码像素。
// 超过单张硬上限的输入拒绝；上限内的并发由上层按解码像素权重共享预算。
func PlanImage(header ImageHeader, spec ImageSpec) (ImagePlan, error) {
	var result ImagePlan
	if ValidateImageSpec(spec) != nil || header.Width < 1 || header.Height < 1 || header.Width > math.MaxInt32 || header.Height > math.MaxInt32 || header.Orientation < 1 || header.Orientation > 8 || int64(header.Width) > math.MaxInt64/int64(header.Height) {
		return result, ErrImageInvalid
	}
	switch header.Format {
	case "jpeg", "png", "gif", "webp":
	default:
		return result, ErrImageInvalid
	}
	width, height := header.Width, header.Height
	if header.Orientation >= 5 {
		width, height = height, width
	}
	result.Width, result.Height = width, height
	long := max(width, height)
	if long > spec.MaxLongEdge {
		result.Width = max(1, int(int64(width)*int64(spec.MaxLongEdge)/int64(long)))
		result.Height = max(1, int(int64(height)*int64(spec.MaxLongEdge)/int64(long)))
	}
	result.JPEGScale = 8
	if header.Format == "jpeg" && long > spec.MaxLongEdge {
		// 选择仍不小于最终尺寸的最小 1/8…8/8 档位。
		result.JPEGScale = max(1, int((int64(spec.MaxLongEdge)*8+int64(long)-1)/int64(long)))
	}
	result.DecodeWidth = int((int64(header.Width)*int64(result.JPEGScale) + 7) / 8)
	result.DecodeHeight = int((int64(header.Height)*int64(result.JPEGScale) + 7) / 8)
	// 单 VP8 图块的有损 WebP 可由 dwebp 按目标尺寸缩放解码，不分配整帧像素。
	if header.Format == "webp" && header.SimpleLossyWebP && long > spec.MaxLongEdge {
		result.ScaleWebP = true
		result.DecodeWidth, result.DecodeHeight = result.Width, result.Height
	}
	if int64(result.DecodeWidth)*int64(result.DecodeHeight) > MaxImageDecodePixels {
		return ImagePlan{}, ErrImageLimit
	}
	return result, nil
}
