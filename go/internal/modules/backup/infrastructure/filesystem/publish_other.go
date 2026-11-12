//go:build !windows && !linux

package filesystem

import (
	"os"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func publishNoReplace(*os.Root, string, string) error { return domain.ErrIO }
