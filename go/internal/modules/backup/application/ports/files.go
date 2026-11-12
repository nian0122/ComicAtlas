package ports

import (
	"context"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

type Workspace interface {
	Root() string
	Directory(context.Context, string) (domain.Entry, error)
	File(context.Context, string) (domain.Entry, error)
	Digest(context.Context, string) (domain.Object, error)
	ImportMedia(context.Context, string, func(domain.Entry) error) error
	ImportEntry(context.Context, string, domain.Entry) error
	WriteManifest(context.Context, domain.Manifest) error
	WriteReceipt(context.Context, domain.RestoreReceipt) error
	Publish(context.Context) error
	Close() error
}

type Bundle interface {
	Root() string
	Manifest(context.Context) (domain.Manifest, error)
	Digest(context.Context, string) (domain.Object, error)
	Check(context.Context, domain.Entry) error
	CheckExtra(context.Context, CatalogReader) error
	Close() error
}

type Filesystem interface {
	// 返回规范化的来源根供数据库和媒体适配器使用，目标不能位于任一来源内。
	Begin(context.Context, string, []string) (Workspace, []string, error)
	Open(context.Context, string) (Bundle, error)
}
