package ffmpeg

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"sync"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/process"
)

type Encoder struct {
	runner       executor
	executable   string
	probe        mediaports.Probe
	mode         EncoderMode
	nvencQuality int
	mutex        sync.Mutex
	nvencOffline bool
}

func NewEncoder(runner *process.Runner, executable string, probe mediaports.Probe, mode EncoderMode, nvencQuality int) *Encoder {
	return &Encoder{runner: runner, executable: executable, probe: probe, mode: mode, nvencQuality: nvencQuality}
}

var _ mediaports.VideoEncoder = (*Encoder)(nil)

// Encode 只创建新暂存目录；成功后的登记、发布和清理由任务文件存储负责。
func (encoder *Encoder) Encode(parent context.Context, request mediadomain.VideoEncodeRequest) (artifact mediadomain.VideoEncodeArtifact, err error) {
	if request.SourceSize < 1 {
		return artifact, mediadomain.ErrTranscodeInvalid
	}
	if request.SourceSize > mediadomain.MaxTranscodeBytes {
		return artifact, mediadomain.ErrTranscodeLimit
	}
	switch encoder.mode {
	case EncoderAuto, EncoderX264, EncoderNVENC:
	default:
		return artifact, mediadomain.ErrTranscodeUnavailable
	}
	if !filepath.IsAbs(request.SourcePath) || !filepath.IsAbs(request.OutputDirectory) {
		return artifact, mediadomain.ErrTranscodeInvalid
	}
	info, err := os.Lstat(request.OutputDirectory)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return artifact, mediadomain.ErrTranscodeInvalid
	}
	if encoder.executable == "" || encoder.probe == nil || encoder.runner == nil {
		return artifact, mediadomain.ErrTranscodeUnavailable
	}
	ctx := parent
	sourceInfo, err := os.Lstat(request.SourcePath)
	if err != nil || !sourceInfo.Mode().IsRegular() || sourceInfo.Size() != request.SourceSize {
		return artifact, mediadomain.ErrTranscodeChanged
	}
	if err = ctx.Err(); err != nil {
		return artifact, err
	}
	probe, err := encoder.probe.Probe(ctx, request.SourcePath)
	if err != nil {
		return artifact, err
	}
	source, err := mediadomain.Analyze(probe)
	if err != nil {
		return artifact, err
	}
	if err = mediadomain.ValidateTranscodeSource(source); err != nil {
		return artifact, err
	}
	ctx, cancel := context.WithTimeout(ctx, mediadomain.TranscodeTimeout(source.DurationMS))
	defer cancel()
	directory, err := os.MkdirTemp(request.OutputDirectory, ".transcode-")
	if err != nil {
		return artifact, mediadomain.ErrTranscodeOutput
	}
	defer func() {
		if err != nil {
			_ = os.RemoveAll(directory)
			artifact = mediadomain.VideoEncodeArtifact{}
		}
	}()
	filename := filepath.Join(directory, "video.mp4")
	mode := encoder.selectMode()
	gpuDecode := hardwareDecode(mode, source)
	if err = encoder.run(ctx, request.SourcePath, filename, mode, gpuDecode); err != nil {
		if !encoder.canFallback(mode, err, filename) {
			return artifact, encodeRunError(err)
		}
		// 只有 NVENC 初始化失败且没有产物时才回退；已写入部分数据说明编码进行过，保留原错误避免重复转码。
		if err = os.RemoveAll(directory); err != nil {
			return artifact, mediadomain.ErrTranscodeOutput
		}
		directory, err = os.MkdirTemp(request.OutputDirectory, ".transcode-")
		if err != nil {
			return artifact, mediadomain.ErrTranscodeOutput
		}
		filename = filepath.Join(directory, "video.mp4")
		if err = encoder.run(ctx, request.SourcePath, filename, EncoderX264, false); err != nil {
			return artifact, encodeRunError(err)
		}
		encoder.markNVENCOffline()
	}
	if err = verifySourceIdentity(request.SourcePath, sourceInfo); err != nil {
		return artifact, err
	}
	output, err := encoder.probe.Probe(ctx, filename)
	if err != nil {
		return artifact, err
	}
	artifact.Video, err = mediadomain.Analyze(output)
	if err != nil {
		return artifact, mediadomain.ErrTranscodeOutput
	}
	if err = mediadomain.ValidateTranscodeOutput(source, artifact.Video); err != nil {
		return artifact, err
	}
	artifact.Size, artifact.SHA256, err = videoDigest(ctx, filename)
	if err != nil {
		return artifact, err
	}
	artifact.Path = filename
	return artifact, nil
}

func (encoder *Encoder) selectMode() EncoderMode {
	if encoder.mode == EncoderAuto {
		encoder.mutex.Lock()
		defer encoder.mutex.Unlock()
		if encoder.nvencOffline {
			return EncoderX264
		}
		return EncoderNVENC
	}
	return encoder.mode
}

// canFallback 只处理 NVENC 在启动阶段失败且尚未产生输出的情况。
func (encoder *Encoder) canFallback(mode EncoderMode, err error, filename string) bool {
	if mode != EncoderNVENC || encoder.mode != EncoderAuto || !errors.Is(err, process.ErrExit) {
		return false
	}
	info, statErr := os.Stat(filename)
	return statErr != nil || info.Size() == 0
}

func (encoder *Encoder) markNVENCOffline() {
	encoder.mutex.Lock()
	encoder.nvencOffline = true
	encoder.mutex.Unlock()
}

func (encoder *Encoder) run(ctx context.Context, source, filename string, mode EncoderMode, gpuDecode bool) error {
	_, err := encoder.runner.Run(ctx, process.Request{Executable: encoder.executable, Timeout: mediadomain.MaxTranscodeTimeout,
		Args: encoder.arguments(source, filename, mode, gpuDecode)})
	return err
}

// hardwareDecode 只对 NVDEC 支持的编码和偶数尺寸启用；奇数尺寸仍需 CPU pad。
func hardwareDecode(mode EncoderMode, source mediadomain.Video) bool {
	if mode != EncoderNVENC || source.Width%2 != 0 || source.Height%2 != 0 {
		return false
	}
	switch source.VideoCodec {
	case "h264", "hevc", "vp9", "av1":
		return true
	}
	return false
}

func (encoder *Encoder) arguments(source, filename string, mode EncoderMode, gpuDecode bool) []string {
	// NVENC 只加速编码；无法硬件解码的源仍由 CPU 多线程解码。
	decodeThreads := "1"
	if mode == EncoderNVENC && !gpuDecode {
		decodeThreads = "8"
	}
	args := []string{"-nostdin", "-hide_banner", "-v", "error", "-xerror", "-max_alloc", "268435456",
		"-threads", decodeThreads, "-filter_threads", "1", "-filter_complex_threads", "1", "-probesize", "8388608", "-analyzeduration", "5000000",
		"-protocol_whitelist", "file", "-format_whitelist", "mov,matroska,webm,avi", "-max_streams", "64", "-err_detect", "explode"}
	if gpuDecode {
		args = append(args, "-hwaccel", "cuda", "-hwaccel_output_format", "cuda")
	}
	args = append(args, "-noautorotate", "-i", source, "-map", "0:V:0", "-map", "0:a:0?", "-map_metadata", "-1", "-map_chapters", "-1")
	if mode == EncoderNVENC {
		quality := encoder.nvencQuality
		if quality < 1 || quality > 51 {
			quality = 31
		}
		args = append(args, "-c:v", "h264_nvenc", "-preset", "p5", "-tune", "hq", "-rc", "vbr",
			"-cq", strconv.Itoa(quality), "-b:v", "0", "-pix_fmt", "yuv420p")
	} else {
		args = append(args, "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-threads", "1", "-pix_fmt", "yuv420p")
	}
	if !gpuDecode {
		args = append(args, "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2")
	}
	args = append(args, "-c:a", "aac", "-b:a", "128k", "-ac", "2",
		"-movflags", "+faststart", "-max_muxing_queue_size", "1024", "-fs", strconv.FormatInt(mediadomain.MaxTranscodeBytes+1, 10),
		"-f", "mp4", "-n", filename)
	return args
}

func encodeRunError(err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	if errors.Is(err, process.ErrStart) {
		return mediadomain.ErrTranscodeUnavailable
	}
	return mediadomain.ErrTranscodeProcess
}

// verifySourceIdentity 用编码前捕获的文件身份、大小和修改时间核对来源，避免重复读取整个文件。
func verifySourceIdentity(filename string, expected os.FileInfo) error {
	current, err := os.Lstat(filename)
	if err != nil || !current.Mode().IsRegular() || !os.SameFile(expected, current) ||
		current.Size() != expected.Size() || !current.ModTime().Equal(expected.ModTime()) {
		return mediadomain.ErrTranscodeChanged
	}
	return nil
}

// videoDigest 按固定缓冲读取，并通过文件身份、大小及修改时间排除读取期间变化。
func videoDigest(ctx context.Context, filename string) (int64, string, error) {
	info, err := os.Lstat(filename)
	if err != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > mediadomain.MaxTranscodeBytes {
		return 0, "", mediadomain.ErrTranscodeOutput
	}
	file, err := os.OpenFile(filename, os.O_RDONLY, 0)
	if err != nil {
		return 0, "", mediadomain.ErrTranscodeOutput
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !os.SameFile(info, opened) {
		return 0, "", mediadomain.ErrTranscodeOutput
	}
	digest := sha256.New()
	buffer := make([]byte, 256<<10)
	var size int64
	for {
		if err = ctx.Err(); err != nil {
			return 0, "", err
		}
		count, readErr := file.Read(buffer)
		if count > 0 {
			size += int64(count)
			if size > mediadomain.MaxTranscodeBytes {
				return 0, "", mediadomain.ErrTranscodeLimit
			}
			_, _ = digest.Write(buffer[:count])
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return 0, "", mediadomain.ErrTranscodeOutput
		}
	}
	final, err := file.Stat()
	current, pathErr := os.Lstat(filename)
	if err != nil || pathErr != nil || !os.SameFile(info, current) || size != info.Size() || final.Size() != info.Size() ||
		!final.ModTime().Equal(info.ModTime()) || !current.ModTime().Equal(info.ModTime()) {
		return 0, "", mediadomain.ErrTranscodeOutput
	}
	return size, hex.EncodeToString(digest.Sum(nil)), nil
}
