package application

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

type UploadService struct {
	repository mediaports.UploadRepository
	files      mediaports.UploadStorage
	limits     mediadomain.UploadLimits
	slots      chan struct{}
	locks      [32]*sync.RWMutex
}

func NewUploads(repository mediaports.UploadRepository, files mediaports.UploadStorage, limits mediadomain.UploadLimits) *UploadService {
	value := &UploadService{repository: repository, files: files, limits: limits, slots: make(chan struct{}, 4)}
	for index := range value.locks {
		value.locks[index] = &sync.RWMutex{}
	}
	return value
}
func uploadID(prefix string) (string, error) {
	var value [16]byte
	_, err := rand.Read(value[:])
	return prefix + hex.EncodeToString(value[:]), err
}
func uploadNow() string { return time.Now().UTC().Format("2006-01-02T15:04:05.000000000Z") }
func (service *UploadService) lockSlot(id string) *sync.RWMutex {
	hash := sha256.Sum256([]byte(id))
	return service.locks[int(hash[0])%len(service.locks)]
}
func waitLock(ctx context.Context, try func() bool) error {
	for {
		if try() {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(5 * time.Millisecond):
		}
	}
}
func (service *UploadService) lockUpload(ctx context.Context, id string) (func(), error) {
	slot := service.lockSlot(id)
	if err := waitLock(ctx, slot.TryLock); err != nil {
		return nil, err
	}
	return slot.Unlock, nil
}
func (service *UploadService) lockReceive(ctx context.Context, id string) (func(), error) {
	slot := service.lockSlot(id)
	if err := waitLock(ctx, slot.TryRLock); err != nil {
		return nil, err
	}
	return slot.RUnlock, nil
}
func (service *UploadService) Create(ctx context.Context, request mediadomain.UploadRequest, key string) (mediadomain.UploadSession, error) {
	request, total, err := mediadomain.NormalizeUpload(request)
	if err != nil || len(key) > 128 {
		if err == nil {
			err = mediadomain.ErrUploadValidation
		}
		return mediadomain.UploadSession{}, err
	}
	if err = service.repository.ExpireUploads(ctx, uploadNow()); err != nil {
		return mediadomain.UploadSession{}, err
	}
	id, err := uploadID("us_")
	if err != nil {
		return mediadomain.UploadSession{}, err
	}
	now := time.Now().UTC()
	value := mediadomain.UploadSession{ID: id, ChapterID: request.ChapterID, ReplaceMediaID: request.ReplaceMediaID, Status: "active", TotalBytes: total, ChunkSize: mediadomain.UploadChunkBytes, CreatedAt: now.Format(mediadomain.UploadTimeLayout), ExpiresAt: now.Add(mediadomain.UploadTTL).Format(mediadomain.UploadTimeLayout), Files: []mediadomain.UploadFile{}}
	for _, file := range request.Files {
		fileID, err := uploadID("uf_")
		if err != nil {
			return value, err
		}
		value.Files = append(value.Files, mediadomain.UploadFile{ID: fileID, Name: file.Name, Size: file.Size, SHA256: file.SHA256})
	}
	data, err := json.Marshal(request)
	if err != nil {
		return value, err
	}
	digest := sha256.Sum256(data)
	space, err := service.files.UploadSpace(ctx)
	if err != nil {
		return value, err
	}
	id, err = service.repository.CreateUpload(ctx, value, key, hex.EncodeToString(digest[:]), space, service.limits)
	if err != nil {
		return value, err
	}
	return service.repository.UploadSession(ctx, id)
}
func (service *UploadService) Status(ctx context.Context, id string) (mediadomain.UploadSession, error) {
	if !mediadomain.UploadID(id, "us_") {
		return mediadomain.UploadSession{}, mediadomain.ErrUploadNotFound
	}
	if err := service.repository.ExpireUploads(ctx, uploadNow()); err != nil {
		return mediadomain.UploadSession{}, err
	}
	return service.repository.UploadSession(ctx, id)
}
func (service *UploadService) Receive(ctx context.Context, id, fileID, contentRange string, input io.Reader) (mediadomain.UploadPart, error) {
	var empty mediadomain.UploadPart
	if !mediadomain.UploadID(id, "us_") || !mediadomain.UploadID(fileID, "uf_") {
		return empty, mediadomain.ErrUploadNotFound
	}
	run, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	ctx = run
	select {
	case service.slots <- struct{}{}:
		defer func() { <-service.slots }()
	case <-ctx.Done():
		return empty, ctx.Err()
	}
	release, err := service.lockReceive(ctx, id)
	if err != nil {
		return empty, err
	}
	defer release()
	session, file, err := service.repository.UploadFile(ctx, id, fileID)
	if err != nil {
		return empty, err
	}
	if err = mediadomain.UploadActive(session, uploadNow()); err != nil {
		return empty, err
	}
	extent, err := mediadomain.ParseUploadRange(contentRange, file.Size)
	if err != nil {
		return empty, err
	}
	previous, err := service.repository.UploadPart(ctx, id, fileID, extent.Part)
	if err != nil {
		return empty, err
	}
	space, err := service.files.UploadSpace(ctx)
	if err != nil {
		return empty, err
	}
	if err = mediadomain.UploadBudget(space, service.limits, 0, 2*mediadomain.UploadChunkBytes, 0); err != nil {
		return empty, err
	}
	part, err := service.files.ReceiveUploadPart(ctx, id, file, extent, input, previous)
	if err != nil {
		return empty, err
	}
	if err = service.repository.RecordUploadPart(ctx, id, part, uploadNow()); err != nil {
		return empty, err
	}
	return part, nil
}
func (service *UploadService) Cancel(ctx context.Context, id string) error {
	if !mediadomain.UploadID(id, "us_") {
		return mediadomain.ErrUploadNotFound
	}
	release, err := service.lockUpload(ctx, id)
	if err != nil {
		return err
	}
	defer release()
	if err = service.repository.EndUpload(ctx, id, "cancelled", uploadNow()); err != nil {
		return err
	}
	_, err = service.cleanUpload(ctx, id)
	return err
}

// cleanUpload 在会话锁内复核待清理状态，避免与即时取消重复计数。
func (service *UploadService) cleanUpload(ctx context.Context, id string) (bool, error) {
	session, readErr := service.repository.UploadSession(ctx, id)
	if readErr != nil || !session.CleanupPending {
		return false, readErr
	}
	if err := service.files.RemoveUpload(ctx, session); err != nil {
		return false, err
	}
	err := service.repository.UploadCleaned(ctx, id)
	return err == nil, err
}

func (service *UploadService) CleanupPending(ctx context.Context) (int, error) {
	if err := service.repository.ExpireUploads(ctx, uploadNow()); err != nil {
		return 0, err
	}
	var firstFailure error
	cleaned, failed := 0, 0
	afterID := ""
	for {
		ids, err := service.repository.UploadCleanup(ctx, afterID)
		if err != nil {
			return cleaned, errors.Join(firstFailure, err)
		}
		if len(ids) == 0 {
			break
		}
		// 失败项在下一轮重试，本轮继续处理后续会话。
		afterID = ids[len(ids)-1]
		for _, id := range ids {
			release, err := service.lockUpload(ctx, id)
			if err != nil {
				return cleaned, errors.Join(firstFailure, err)
			}
			removed, err := service.cleanUpload(ctx, id)
			release()
			if err != nil {
				failed++
				if firstFailure == nil {
					firstFailure = err
				}
				if ctx.Err() != nil {
					return cleaned, errors.Join(firstFailure, ctx.Err())
				}
			} else if removed {
				cleaned++
			}
		}
	}
	if firstFailure != nil {
		return cleaned, fmt.Errorf("%d 个上传会话待重试: %w", failed, firstFailure)
	}
	return cleaned, nil
}
