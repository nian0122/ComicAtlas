package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
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

func (move *directoryExecution) checkReceipt(name string, item *directoryItem, info os.FileInfo) error {
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
	return nil
}
