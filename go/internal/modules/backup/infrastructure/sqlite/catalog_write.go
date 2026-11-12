package sqlite

import (
	"context"
	"os"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func (writer *catalogWriter) Add(ctx context.Context, entry domain.Entry) error {
	if writer.closed {
		return domain.ErrInvalid
	}
	next, err := domain.Add(writer.summary, entry)
	if err != nil {
		return err
	}
	if _, err = writer.database.ExecContext(ctx, "INSERT INTO backup_entries VALUES(?,?,?,?,?)", entry.Path, entry.Kind, entry.Size, entry.SHA256, entry.ModifiedAt); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return domain.ErrInvalid
	}
	writer.summary = next
	return nil
}

func (writer *catalogWriter) Seal(ctx context.Context) (domain.Summary, error) {
	if writer.closed || domain.ValidateSummary(writer.summary) != nil {
		return domain.Summary{}, domain.ErrInvalid
	}
	if err := ctx.Err(); err != nil {
		return domain.Summary{}, err
	}
	if err := catalogRoots(ctx, &catalogReader{database: writer.database}); err != nil {
		return domain.Summary{}, err
	}
	if err := writer.Close(); err != nil {
		return domain.Summary{}, err
	}
	file, err := os.OpenFile(writer.filename, os.O_RDWR, 0)
	if err != nil {
		return domain.Summary{}, domain.ErrIO
	}
	syncErr := file.Sync()
	closeErr := file.Close()
	if syncErr != nil || closeErr != nil {
		return domain.Summary{}, domain.ErrIO
	}
	if err = ctx.Err(); err != nil {
		return domain.Summary{}, err
	}
	return writer.summary, nil
}

func (writer *catalogWriter) Close() error {
	if !writer.closed {
		writer.closed = true
		if writer.database.Close() != nil {
			writer.closeErr = domain.ErrIO
		}
	}
	return writer.closeErr
}
