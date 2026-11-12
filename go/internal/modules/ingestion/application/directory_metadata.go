package application

import (
	"context"
	"errors"
	"io"
	"os"

	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func (directory *Directory) readDirectoryMetadata(ctx context.Context, source *os.Root, task jobsdomain.Task) (librarydomain.Comic, *librarydomain.ComicInfo, error) {
	comic := librarydomain.Comic{Title: task.Title, Tags: []librarydomain.ComicTag{}}
	file, err := source.Open("ComicInfo.xml")
	if errors.Is(err, os.ErrNotExist) {
		return comic, nil, nil
	}
	if err != nil {
		return comic, nil, sourceFailure(err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return comic, nil, sourceFailure(err)
	}
	if !info.Mode().IsRegular() || info.Size() > 1<<20 {
		return comic, nil, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
	}
	data, err := io.ReadAll(io.LimitReader(contextReader{ctx: ctx, reader: file}, 1<<20+1))
	if err != nil {
		return comic, nil, sourceFailure(err)
	}
	if len(data) > 1<<20 {
		return comic, nil, ingestiondomainMetadataFailure(ingestiondomain.ErrLimit)
	}
	value, err := decodeComicInfo(data)
	if err != nil {
		return comic, nil, ingestiondomainMetadataFailure(err)
	}
	metadata := &librarydomain.ComicInfo{Series: value.Series, Title: value.Title, Number: value.Number, Writer: value.Writer, Summary: value.Summary, Genre: value.Genre, Tags: librarydomain.ComicInfoTags(value.Tags)}
	if err = librarydomain.ValidateComicInfo(metadata); err != nil {
		return comic, nil, ingestiondomainMetadataFailure(err)
	}
	if !task.TitleExplicit && value.Title != "" {
		comic.Title = value.Title
	}
	comic.Author, comic.Description, comic.CategoryName = value.Writer, value.Summary, value.Genre
	for _, tag := range metadata.Tags {
		comic.Tags = append(comic.Tags, librarydomain.ComicTag{Term: librarydomain.Term{Name: tag}, Source: "comicinfo"})
	}
	if err = librarydomain.ValidateImportedMetadata(comic); err != nil {
		return comic, nil, ingestiondomainMetadataFailure(err)
	}
	return comic, metadata, nil
}
