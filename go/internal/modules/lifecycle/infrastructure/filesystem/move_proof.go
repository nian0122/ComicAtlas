package filesystem

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/relocate"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
)

func (files *Files) moveInfo(name string, directory bool) (os.FileInfo, error) {
	if err := files.parents(name, false); err != nil {
		return nil, err
	}
	info, err := files.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return nil, err
	}
	if directory && !info.IsDir() || !directory && !info.Mode().IsRegular() {
		return nil, lifecycledomain.ErrConflict
	}
	return info, nil
}

func (files *Files) readMoveMarker(directory, id string) (string, error) {
	name := lifecycledomain.MoveMarkerPath(directory, id)
	info, err := files.stat(name)
	if err != nil {
		return "", err
	}
	if info.Size() != 64 {
		return "", lifecycledomain.ErrChanged
	}
	input, err := files.root.Open(filepath.FromSlash(name))
	if err != nil {
		return "", err
	}
	defer input.Close()
	opened, err := input.Stat()
	if err != nil || !os.SameFile(info, opened) || !opened.Mode().IsRegular() {
		return "", lifecycledomain.ErrChanged
	}
	data, err := io.ReadAll(io.LimitReader(input, 65))
	if err != nil {
		return "", err
	}
	token, err := hex.DecodeString(string(data))
	if err != nil || len(token) != 32 || hex.EncodeToString(token) != string(data) {
		return "", lifecycledomain.ErrChanged
	}
	return string(data), nil
}

func (files *Files) prepareMoveMarker(directory, id string) (string, error) {
	proof, err := files.readMoveMarker(directory, id)
	if !errors.Is(err, os.ErrNotExist) {
		return proof, err
	}
	var nonce [32]byte
	if _, err = rand.Read(nonce[:]); err != nil {
		return "", err
	}
	proof = hex.EncodeToString(nonce[:])
	name := lifecycledomain.MoveMarkerPath(directory, id)
	temporary := name + "-" + proof + ".tmp"
	output, err := files.root.OpenFile(filepath.FromSlash(temporary), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	defer files.root.Remove(filepath.FromSlash(temporary))
	_, writeErr := io.WriteString(output, proof)
	if err = errors.Join(writeErr, output.Sync(), output.Close()); err != nil {
		return "", err
	}
	info, err := files.stat(temporary)
	if err != nil {
		return "", err
	}
	if err = relocate.WithinRoot(files.root, temporary, name, info); err != nil {
		return "", err
	}
	return proof, files.syncDirectory(directory)
}

func (files *Files) removeMoveMarker(directory, id, expected string) error {
	proof, err := files.readMoveMarker(directory, id)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if proof != expected {
		return lifecycledomain.ErrChanged
	}
	if err = files.root.Remove(filepath.FromSlash(lifecycledomain.MoveMarkerPath(directory, id))); err != nil {
		return err
	}
	return files.syncDirectory(directory)
}

func (files *Files) verifyMove(ctx context.Context, name, id string, unit lifecycledomain.MoveUnit, info os.FileInfo) error {
	if unit.Directory {
		proof, err := files.readMoveMarker(name, id)
		if errors.Is(err, os.ErrNotExist) || err == nil && proof != unit.Proof {
			return lifecycledomain.ErrChanged
		}
		return err
	}
	if info.Size() != unit.Size {
		return lifecycledomain.ErrChanged
	}
	size, proof, err := files.digest(ctx, name)
	if err == nil && (size != unit.Size || proof != unit.Proof) {
		return lifecycledomain.ErrChanged
	}
	return err
}
