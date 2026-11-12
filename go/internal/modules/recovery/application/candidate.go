package application

import (
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
)

type CandidatePreparer struct {
	records  recoveryports.Records
	sources  recoveryports.Sources
	verifier *MediaVerifier
}

func NewCandidatePreparer(records recoveryports.Records, sources recoveryports.Sources) *CandidatePreparer {
	return &CandidatePreparer{records: records, sources: sources, verifier: NewMediaVerifier(sources)}
}

func (preparer *CandidatePreparer) Prepare(ctx context.Context, comic string, progress func(int) error) (result recoverydomain.Candidate, err error) {
	defer func() {
		err = errors.Join(err, ctx.Err())
		if err != nil {
			result = recoverydomain.Candidate{}
		}
	}()
	if err = ctx.Err(); err != nil {
		return result, err
	}
	if !recoverydomain.Key(comic) {
		return result, recoverydomain.ErrValidation
	}
	exists, err := preparer.records.HasComic(ctx, comic)
	if err != nil {
		return result, err
	}
	result.ComicID = comic
	if exists {
		result.Kind = recoverydomain.CandidateExisting
		return result, nil
	}
	document, err := preparer.sources.ReadSidecar(ctx, comic)
	if errors.Is(err, recoverydomain.ErrMissing) {
		result.Presence, err = preparer.sources.HQPresence(ctx, comic)
		if err != nil {
			return result, err
		}
		result.Kind = recoverydomain.MissingMetadataKind(result.Presence)
		return result, nil
	}
	if err != nil {
		return result, err
	}
	result.Verification, err = preparer.verifier.Verify(ctx, comic, document.Sidecar, progress)
	if err != nil {
		return result, err
	}
	result.Kind, result.Document = recoverydomain.CandidateBytesVerified, &document
	return result, nil
}
