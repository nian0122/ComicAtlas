package domain

import (
	"encoding/hex"
	"path"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

func UploadID(value, prefix string) bool {
	if !strings.HasPrefix(value, prefix) || len(value) != len(prefix)+32 {
		return false
	}
	_, err := hex.DecodeString(strings.TrimPrefix(value, prefix))
	return err == nil && strings.ToLower(value) == value
}
func UploadDigest(value string) bool {
	decoded, err := hex.DecodeString(value)
	return err == nil && len(decoded) == 32 && strings.ToLower(value) == value
}
func UploadName(name string) bool {
	if name == "" || len(name) > 255 || !utf8.ValidString(name) || strings.TrimSpace(name) != name || strings.HasSuffix(name, ".") || strings.ContainsAny(name, "/\\:<>\"|?*") {
		return false
	}
	for _, c := range name {
		if unicode.IsControl(c) {
			return false
		}
	}
	switch strings.ToLower(path.Ext(name)) {
	case ".jpg", ".jpeg", ".png", ".gif", ".webp", ".mp4", ".webm", ".mov", ".mkv", ".avi":
		return true
	}
	return false
}
func NormalizeUpload(request UploadRequest) (UploadRequest, int64, error) {
	if request.ChapterID == "" || len(request.ChapterID) > 128 || len(request.Files) < 1 || len(request.Files) > MaxUploadFiles || request.ReplaceMediaID != nil && (*request.ReplaceMediaID == "" || len(*request.ReplaceMediaID) > 128 || len(request.Files) != 1) {
		return request, 0, ErrUploadValidation
	}
	request.Files = append([]UploadFileRequest(nil), request.Files...)
	names := map[string]bool{}
	var total int64
	for index, file := range request.Files {
		file.SHA256 = strings.ToLower(file.SHA256)
		if !UploadName(file.Name) || !UploadDigest(file.SHA256) || file.Size < 1 || file.Size > MaxUploadFileBytes || total > MaxUploadSessionBytes-file.Size {
			return request, 0, ErrUploadValidation
		}
		key := strings.ToLower(file.Name)
		if names[key] {
			return request, 0, ErrUploadConflict
		}
		names[key] = true
		total += file.Size
		request.Files[index] = file
	}
	return request, total, nil
}
func ParseUploadRange(value string, size int64) (UploadRange, error) {
	var result UploadRange
	if !strings.HasPrefix(value, "bytes ") {
		return result, ErrUploadValidation
	}
	bounds, total, ok := strings.Cut(strings.TrimPrefix(value, "bytes "), "/")
	if !ok {
		return result, ErrUploadValidation
	}
	start, end, ok := strings.Cut(bounds, "-")
	if !ok {
		return result, ErrUploadValidation
	}
	parse := func(text string) (int64, error) {
		for _, r := range text {
			if r < '0' || r > '9' {
				return 0, ErrUploadValidation
			}
		}
		return strconv.ParseInt(text, 10, 64)
	}
	first, e1 := parse(start)
	last, e2 := parse(end)
	full, e3 := parse(total)
	if e1 != nil || e2 != nil || e3 != nil || full != size || size < 1 || size > MaxUploadFileBytes || first < 0 || last < first || last >= full || first%UploadChunkBytes != 0 || last-first+1 != min(UploadChunkBytes, full-first) {
		return result, ErrUploadValidation
	}
	return UploadRange{Start: first, Length: last - first + 1, Total: full, Part: int(first / UploadChunkBytes)}, nil
}
func UploadActive(session UploadSession, now string) error {
	if session.Status != "active" {
		if session.Status == "expired" {
			return ErrUploadExpired
		}
		return ErrUploadConflict
	}
	expires, err := time.Parse(time.RFC3339Nano, session.ExpiresAt)
	current, e2 := time.Parse(time.RFC3339Nano, now)
	if err != nil || e2 != nil {
		return ErrUploadValidation
	}
	if !current.Before(expires) {
		return ErrUploadExpired
	}
	return nil
}
func UploadBudget(space UploadSpace, limits UploadLimits, reserved, requested int64, active int) error {
	if space.Total < 0 || space.Available < 0 || space.Available > space.Total || limits.MinFreeBytes < 0 || limits.MinFreePercent < 0 || limits.MinFreePercent > 50 || reserved < 0 || requested < 0 || reserved > MaxUploadSessionBytes || requested > MaxUploadSessionBytes {
		return ErrUploadValidation
	}
	if active >= MaxUploadSessions || reserved > MaxUploadSessionBytes-requested {
		return ErrUploadSpace
	}
	// 预留两条接收流的临时分片；已落盘的声明字节从 remaining reservation 扣除。
	floor := max(limits.MinFreeBytes, (space.Total/100)*int64(limits.MinFreePercent)+(space.Total%100*int64(limits.MinFreePercent)+99)/100)
	if space.Available < floor || space.Available-floor < 2*UploadChunkBytes || space.Available-floor-2*UploadChunkBytes < reserved+requested {
		return ErrUploadSpace
	}
	return nil
}
func UploadNames(existing, reserved []string, files []UploadFile) error {
	names := map[string]bool{}
	for _, name := range existing {
		names[strings.ToLower(name)] = true
	}
	for _, name := range reserved {
		names[strings.ToLower(name)] = true
	}
	for _, file := range files {
		key := strings.ToLower(file.Name)
		if names[key] {
			return ErrUploadConflict
		}
		names[key] = true
	}
	return nil
}

func UploadReplacementAllowed(state string) bool { return state == "available" || state == "released" }

func UploadCapacity(existing, reserved, requested int, replace bool) error {
	if !replace && existing+reserved+requested > MaxUploadFiles {
		return ErrUploadValidation
	}
	if existing > MaxUploadFiles {
		return ErrUploadValidation
	}
	return nil
}
func ValidateUploadPart(part UploadPart, size int64) error {
	if size < 1 || size > MaxUploadFileBytes || part.Part < 0 || int64(part.Part) >= (size+UploadChunkBytes-1)/UploadChunkBytes || part.Size != min(UploadChunkBytes, size-int64(part.Part)*UploadChunkBytes) || !UploadDigest(part.SHA256) {
		return ErrUploadValidation
	}
	return nil
}
func UploadEnd(session UploadSession, status, now string) error {
	if status != "cancelled" && status != "expired" {
		return ErrUploadValidation
	}
	if session.Status == status {
		return nil
	}
	if session.Status != "active" {
		return ErrUploadConflict
	}
	if status == "expired" && !errorsExpired(session, now) {
		return ErrUploadConflict
	}
	return nil
}
func errorsExpired(session UploadSession, now string) bool {
	return UploadActive(session, now) == ErrUploadExpired
}
