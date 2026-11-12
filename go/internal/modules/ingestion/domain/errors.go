package domain

import jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"

var (
	ErrLimit            = jobsdomain.NewFailure(jobsdomain.FailureLimit, nil)
	ErrUnsafeSource     = jobsdomain.NewFailure(jobsdomain.FailureSourceUnsafe, nil)
	ErrNoMedia          = jobsdomain.NewFailure(jobsdomain.FailureNoMedia, nil)
	ErrSourceChanged    = jobsdomain.NewFailure(jobsdomain.FailureSourceChanged, nil)
	ErrImage            = jobsdomain.NewFailure(jobsdomain.FailureImage, nil)
	ErrMetadata         = jobsdomain.NewFailure(jobsdomain.FailureMetadata, nil)
	ErrPublishConflict  = jobsdomain.NewFailure(jobsdomain.FailurePublishConflict, nil)
	ErrPublishedChanged = jobsdomain.NewFailure(jobsdomain.FailurePublishedChanged, nil)
	ErrManifest         = jobsdomain.NewFailure(jobsdomain.FailureManifest, nil)
)
