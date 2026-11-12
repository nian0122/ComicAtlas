package application

import (
	aiports "comicatlas.local/backend/internal/modules/ai/application/ports"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	"context"
	"errors"
)

type Sampler struct {
	preparer aiports.SamplePreparer
}

func NewSampler(preparer aiports.SamplePreparer) *Sampler {
	return &Sampler{preparer: preparer}
}

func (sampler *Sampler) prepareSnapshot(ctx context.Context, requestID string, snapshot aidomain.Snapshot) (request aidomain.Request, samples aiports.PreparedSamples, err error) {
	if err = aidomain.ValidateSnapshot(snapshot); err != nil {
		return request, nil, err
	}
	samples, err = sampler.preparer.Prepare(ctx, snapshot.Images)
	if err != nil {
		return request, nil, err
	}
	request = aidomain.Request{RequestID: requestID, ComicID: snapshot.ComicID, MetadataVersion: snapshot.MetadataVersion, StructureVersion: snapshot.StructureVersion, Categories: snapshot.Categories, Tags: snapshot.Tags, Samples: samples.Samples()}
	err = aidomain.ValidatePrepared(snapshot, request)
	if err == nil {
		err = ctx.Err()
	}
	if err != nil {
		return aidomain.Request{}, nil, errors.Join(err, samples.Close())
	}
	return request, samples, nil
}
