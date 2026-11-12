package application

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/xml"
	"io"
	"path"
	"sort"
	"strings"

	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/archive"
	"comicatlas.local/backend/internal/platform/natural"
)

func readComicInfo(ctx context.Context, file *zip.File) (ingestiondomain.ComicInfo, error) {
	var info ingestiondomain.ComicInfo
	if file.UncompressedSize64 > 1<<20 {
		return info, ingestiondomain.ErrLimit
	}
	input, err := file.Open()
	if err != nil {
		return info, archiveContentFailure(err)
	}
	defer input.Close()
	data, err := io.ReadAll(io.LimitReader(contextReader{ctx: ctx, reader: input}, (1<<20)+1))
	if err != nil {
		return info, archiveContentFailure(err)
	}
	if len(data) > 1<<20 {
		return info, ingestiondomain.ErrLimit
	}
	return decodeComicInfo(data)
}
func decodeComicInfo(data []byte) (ingestiondomain.ComicInfo, error) {
	var info ingestiondomain.ComicInfo
	decoder := xml.NewDecoder(bytes.NewReader(data))
	if err := decoder.Decode(&info); err != nil {
		return info, err
	}
	for {
		token, tokenErr := decoder.Token()
		if tokenErr == io.EOF {
			break
		}
		if tokenErr != nil {
			return info, tokenErr
		}
		if content, ok := token.(xml.CharData); !ok || strings.TrimSpace(string(content)) != "" {
			return info, ingestiondomain.ErrMetadata
		}
	}
	info.Series = strings.TrimSpace(info.Series)
	info.Number = strings.TrimSpace(info.Number)
	info.Title = strings.TrimSpace(info.Title)
	info.Writer = strings.TrimSpace(info.Writer)
	info.Summary = strings.TrimSpace(info.Summary)
	info.Genre = strings.TrimSpace(info.Genre)
	if len(info.Series) > 500 || len(info.Number) > 100 || len(info.Title) > 500 || len(info.Writer) > 2000 || len(info.Summary) > 65536 || len(info.Genre) > 200 || len(info.Tags) > 20000 {
		return info, ingestiondomain.ErrMetadata
	}
	return info, nil
}

type archiveSelection struct {
	comicInfo *librarydomain.ComicInfo
	document  *librarydomain.ArchiveMetadata
	reader    *archive.Reader
	files     []string
	entries   map[string]*zip.File
	metadata  librarydomain.Comic
}

func (directory *Directory) openArchive(ctx context.Context, task jobsdomain.Task) (selection archiveSelection, resultErr error) {
	if err := directory.inputPathSafe(task.SourcePath); err != nil {
		return archiveSelection{}, err
	}
	reader, err := archive.Open(ctx, directory.input, task.SourcePath)
	if err != nil {
		return archiveSelection{}, sourceFailure(err)
	}
	defer func() {
		if resultErr != nil {
			reader.Close()
		}
	}()
	files := []string{}
	entries := map[string]*zip.File{}
	var infoFile *zip.File
	var documentFile *zip.File
	for _, file := range reader.Files {
		if file.FileInfo().IsDir() {
			continue
		}
		if strings.EqualFold(path.Base(file.Name), "ComicInfo.xml") {
			if infoFile != nil {
				return archiveSelection{}, ingestiondomain.ErrMetadata
			}
			infoFile = file
			continue
		}
		if strings.EqualFold(path.Base(file.Name), "metadata.json") {
			if documentFile != nil {
				return archiveSelection{}, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
			}
			documentFile = file
			continue
		}
		if _, _, ok := ingestiondomain.MediaFormat(file.Name); !ok {
			continue
		}
		files = append(files, file.Name)
		entries[file.Name] = file
		if len(files) > MaxMedia {
			return archiveSelection{}, ingestiondomain.ErrLimit
		}
	}
	if len(files) == 0 {
		return archiveSelection{}, ingestiondomain.ErrNoMedia
	}
	// 归档常有一层传输包装目录；只剥离所有媒体共有的第一层。
	wrapper := strings.SplitN(files[0], "/", 2)[0] + "/"
	unwrap := strings.Contains(files[0], "/")
	for _, name := range files {
		if !strings.HasPrefix(name, wrapper) {
			unwrap = false
			break
		}
	}
	if unwrap {
		trimmed := map[string]*zip.File{}
		for index, name := range files {
			files[index] = strings.TrimPrefix(name, wrapper)
			trimmed[files[index]] = entries[name]
		}
		entries = trimmed
	}
	var document *librarydomain.ArchiveMetadata
	if documentFile != nil {
		metadataPath := documentFile.Name
		if unwrap {
			metadataPath = strings.TrimPrefix(metadataPath, wrapper)
		}
		if strings.Contains(metadataPath, "/") || !strings.EqualFold(metadataPath, "metadata.json") {
			return archiveSelection{}, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
		}
		document, err = readArchiveMetadata(ctx, documentFile)
		if err != nil {
			return archiveSelection{}, err
		}
		if len(document.Sources) != len(files) {
			return archiveSelection{}, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
		}
		for index := range document.Sources {
			source := &document.Sources[index]
			if unwrap {
				if !strings.HasPrefix(source.Path, wrapper) {
					return archiveSelection{}, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
				}
				source.Path = strings.TrimPrefix(source.Path, wrapper)
			}
			if _, exists := entries[source.Path]; !exists {
				return archiveSelection{}, ingestiondomainMetadataFailure(ingestiondomain.ErrMetadata)
			}
		}
	}
	sort.Slice(files, func(i, j int) bool {
		comparison := natural.Compare(files[i], files[j])
		if comparison == 0 {
			return files[i] < files[j]
		}
		return comparison < 0
	})
	var info ingestiondomain.ComicInfo
	if infoFile != nil {
		info, err = readComicInfo(ctx, infoFile)
		if err != nil {
			if classified := taskFailure(err); jobsdomain.CodeOf(classified) != jobsdomain.FailureUnknown {
				return archiveSelection{}, classified
			}
			return archiveSelection{}, jobsdomain.NewFailure(jobsdomain.FailureMetadata, err)
		}
	}

	var sourceInfo *librarydomain.ComicInfo
	if document != nil {
		sourceInfo = librarydomain.CleanComicInfo(document.ComicInfo)
		if err = librarydomain.ValidateComicInfo(sourceInfo); err != nil {
			return archiveSelection{}, ingestiondomainMetadataFailure(err)
		}
	} else if infoFile != nil {
		sourceInfo = &librarydomain.ComicInfo{Series: info.Series, Title: info.Title, Number: info.Number, Writer: info.Writer, Summary: info.Summary, Genre: info.Genre, Tags: librarydomain.ComicInfoTags(info.Tags)}
		if err = librarydomain.ValidateComicInfo(sourceInfo); err != nil {
			return archiveSelection{}, jobsdomain.NewFailure(jobsdomain.FailureMetadata, err)
		}
	}
	metadata := librarydomain.Comic{Title: task.Title, Author: info.Writer, Description: info.Summary, CategoryName: info.Genre, Tags: []librarydomain.ComicTag{}}
	if !task.TitleExplicit && info.Title != "" {
		metadata.Title = info.Title
	}
	for _, name := range librarydomain.ComicInfoTags(info.Tags) {
		metadata.Tags = append(metadata.Tags, librarydomain.ComicTag{Term: librarydomain.Term{Name: name}, Source: "comicinfo"})
	}

	if err = librarydomain.ValidateImportedMetadata(metadata); err != nil {
		return archiveSelection{}, jobsdomain.NewFailure(jobsdomain.FailureMetadata, err)
	}
	return archiveSelection{comicInfo: sourceInfo, document: document, reader: reader, files: files, entries: entries, metadata: metadata}, nil
}
