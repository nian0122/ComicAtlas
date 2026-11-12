package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/relocate"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
)

func (move *directoryExecution) checkProperties(name string, item *directoryItem, info os.FileInfo) error {
	var err error
	if info == nil {
		info, err = move.entry(name)
		if err != nil {
			return err
		}
	}
	if info == nil || !info.Mode().IsRegular() || info.Size() != item.state.Size || info.ModTime().UnixNano() != item.state.Modified {
		return exportdomain.ErrSource
	}
	id, err := move.identity(name)
	if err != nil {
		return err
	}
	if id != item.state.Identity {
		return exportdomain.ErrSource
	}
	return nil
}

func (move *directoryExecution) verifyContent(ctx context.Context, item *directoryItem) (err error) {
	if err = move.safeParent(item.location); err != nil {
		return err
	}
	file, err := move.files.root.Open(item.location)
	if err != nil {
		return err
	}
	defer func() { err = errors.Join(err, file.Close()) }()
	before, err := file.Stat()
	if err != nil {
		return err
	}
	identity, err := relocate.Identity(file)
	if err != nil {
		return err
	}
	if identity != item.state.Identity || before.Size() != item.state.Size || before.ModTime().UnixNano() != item.state.Modified {
		return exportdomain.ErrSource
	}
	hash := sha256.New()
	buffer := move.buffer
	var size int64
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		n, readErr := file.Read(buffer)
		size += int64(n)
		move.stats.HashBytes += int64(n)
		if size > item.state.Size {
			return exportdomain.ErrSource
		}
		if n > 0 {
			_, _ = hash.Write(buffer[:n])
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	after, err := file.Stat()
	if err != nil {
		return err
	}
	if size != item.state.Size || !os.SameFile(before, after) || after.Size() != size || !before.ModTime().Equal(after.ModTime()) {
		return exportdomain.ErrSource
	}
	actual := hex.EncodeToString(hash.Sum(nil))
	if item.state.SHA256 != "" && actual != item.state.SHA256 {
		return exportdomain.ErrSource
	}
	if err = move.checkProperties(item.location, item, after); err != nil {
		return err
	}
	item.hash, item.verified = actual, after
	move.stats.HashFiles++
	return nil
}

func (move *directoryExecution) checkReceipt(name string, item *directoryItem, info os.FileInfo) error {
	if item.verified == nil || item.hash == "" {
		return exportdomain.ErrManifest
	}
	var err error
	if info == nil {
		info, err = move.entry(name)
		if err != nil {
			return err
		}
	}
	if info == nil || !info.Mode().IsRegular() || info.Size() != item.verified.Size() || !info.ModTime().Equal(item.verified.ModTime()) || !os.SameFile(info, item.verified) {
		return exportdomain.ErrSource
	}
	return nil
}
