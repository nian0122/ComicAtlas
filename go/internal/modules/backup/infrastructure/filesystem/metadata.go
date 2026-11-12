package filesystem

import (
	"context"
	"encoding/json"
	"os"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func (work *workspace) WriteManifest(ctx context.Context, manifest domain.Manifest) error {
	data, err := domain.EncodeManifest(manifest)
	if err != nil {
		return err
	}
	return work.writeMetadata(ctx, domain.ManifestPath, data)
}

func (work *workspace) WriteReceipt(ctx context.Context, receipt domain.RestoreReceipt) error {
	if domain.ValidateReceipt(receipt) != nil {
		return domain.ErrValidation
	}
	data, err := json.Marshal(receipt)
	if err != nil || len(data)+1 > domain.MaxManifestBytes {
		return domain.ErrValidation
	}
	return work.writeMetadata(ctx, domain.ReceiptPath, append(data, '\n'))
}

func (work *workspace) writeMetadata(ctx context.Context, name string, data []byte) (result error) {
	if work.closed || work.root == nil {
		return domain.ErrInvalid
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	file, err := work.root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return domain.ErrIO
	}
	defer func() {
		if file.Close() != nil && result == nil {
			result = domain.ErrIO
		}
	}()
	written, err := file.Write(data)
	if err != nil || written != len(data) || file.Sync() != nil {
		return domain.ErrIO
	}
	return ctx.Err()
}
