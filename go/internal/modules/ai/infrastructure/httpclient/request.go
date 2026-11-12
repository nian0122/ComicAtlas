package httpclient

import (
	aiports "comicatlas.local/backend/internal/modules/ai/application/ports"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"net/textproto"
	"sync"
)

func encodeManifest(value aidomain.Request) ([]byte, error) {
	manifest := wireManifest{Version: aidomain.ProtocolVersion, RequestID: value.RequestID, ComicID: value.ComicID, MetadataVersion: value.MetadataVersion, StructureVersion: value.StructureVersion, Categories: value.Categories, Tags: value.Tags, Samples: make([]wireSample, 0, len(value.Samples))}
	for index, sample := range value.Samples {
		manifest.Samples = append(manifest.Samples, wireSample{Sample: sample, Field: sampleField(index)})
	}
	data, err := json.Marshal(manifest)
	if err != nil || len(data) > aidomain.MaxManifestBytes {
		return nil, aidomain.ErrValidation
	}
	return data, nil
}

func sampleField(index int) string { return fmt.Sprintf("image_%02d", index+1) }

func writeRequest(ctx context.Context, form *multipart.Writer, manifest []byte, input aidomain.Request, source aiports.SampleSource) error {
	header := textproto.MIMEHeader{}
	header.Set("Content-Disposition", `form-data; name="manifest"`)
	header.Set("Content-Type", "application/json")
	part, err := form.CreatePart(header)
	if err != nil {
		return err
	}
	if _, err = part.Write(manifest); err != nil {
		return err
	}
	buffer := make([]byte, 64<<10)
	for index, sample := range input.Samples {
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = writeSample(ctx, form, sampleField(index), sample, source, buffer); err != nil {
			return err
		}
	}
	return nil
}

func writeSample(ctx context.Context, form *multipart.Writer, field string, sample aidomain.Sample, source aiports.SampleSource, buffer []byte) error {
	input, err := source.OpenSample(ctx, sample.MediaID)
	if err != nil || input == nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return aidomain.ErrSource
	}
	closeInput := sync.OnceFunc(func() { _ = input.Close() })
	stop := context.AfterFunc(ctx, closeInput)
	defer stop()
	defer closeInput()
	header := textproto.MIMEHeader{}
	header.Set("Content-Disposition", mime.FormatMediaType("form-data", map[string]string{"name": field, "filename": field + ".image"}))
	header.Set("Content-Type", sample.ContentType)
	output, err := form.CreatePart(header)
	if err != nil {
		return err
	}
	digest := sha256.New()
	size, err := io.CopyBuffer(io.MultiWriter(output, digest), io.LimitReader(sampleReader{input}, sample.Size+1), buffer)
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if err != nil {
		return err
	}
	if size != sample.Size || hex.EncodeToString(digest.Sum(nil)) != sample.SHA256 {
		return aidomain.ErrSource
	}
	return nil
}

type sampleReader struct{ io.Reader }

func (reader sampleReader) Read(buffer []byte) (int, error) {
	count, err := reader.Reader.Read(buffer)
	if err != nil && err != io.EOF {
		return count, aidomain.ErrSource
	}
	return count, err
}
