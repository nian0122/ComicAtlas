package ports

import (
	"context"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

type VideoEncoder interface {
	Encode(context.Context, mediadomain.VideoEncodeRequest) (mediadomain.VideoEncodeArtifact, error)
}

type TranscodeRepository interface {
	TranscodeScope(context.Context, string, string) (mediadomain.TranscodeScope, error)
	TranscodeAdmission(mediadomain.TranscodeScope) jobsports.Admission
	TranscodeBegin(context.Context, string) error
	TranscodeBatch(context.Context, string, int) ([]mediadomain.TranscodeItem, error)
	TranscodePrepare(context.Context, string, mediadomain.TranscodeItem, mediadomain.TranscodeRecord) error
	TranscodeCommit(context.Context, string, mediadomain.TranscodeItem) error
	TranscodeSkip(context.Context, string, mediadomain.TranscodeItem) error
	TranscodeFail(context.Context, string, mediadomain.TranscodeItem, string) error
	TranscodeProgress(context.Context, string) (int, int, error)
	TranscodeCompletion(string) persistence.WriteAction
	TranscodeGarbage(context.Context, string) ([]mediadomain.TranscodeItem, error)
	TranscodeCollected(context.Context, string, string) error
	TranscodeItems(context.Context, string, int, int) (mediadomain.TranscodePage, error)
}
type TranscodeStorage interface {
	EncodeVideo(context.Context, string, mediadomain.TranscodeItem) (*mediadomain.TranscodeRecord, error)
	PublishVideo(context.Context, string, mediadomain.TranscodeItem) error
	CollectVideo(context.Context, string, mediadomain.TranscodeItem) error
}
