package ports

import (
	"context"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

// Repository 是业务层需要的持久化能力，不暴露连接或 SQL 事务。
type Repository interface {
	Previews(context.Context, string, []librarydomain.NodeRef) ([]librarydomain.NodePreview, error)
	SelectComics(context.Context, librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error)
	SelectHQComics(context.Context, librarydomain.ComicSelection) ([]librarydomain.SelectedComic, error)
	MetadataPatchWriter(string, int, librarydomain.MetadataPatch) persistence.WriteAction
	CheckPatchReferences(context.Context, librarydomain.MetadataPatch) error
	Snapshot(context.Context, string) (librarydomain.Snapshot, error)
	SnapshotForSidecar(context.Context, string) (librarydomain.SidecarSnapshot, error)
	SnapshotGuard(librarydomain.Snapshot) persistence.WriteAction
	StorageWriter(librarydomain.Snapshot, librarydomain.Snapshot) persistence.WriteAction
	Search(ctx context.Context, query librarydomain.ListQuery) (librarydomain.Page, error)
	Detail(ctx context.Context, id string) (librarydomain.Detail, error)
	Media(ctx context.Context, id string) (librarydomain.Media, error)
	Reader(ctx context.Context, id string) (librarydomain.Reader, error)
	Progress(ctx context.Context, comicID string) (*librarydomain.Progress, error)
	SaveProgress(ctx context.Context, comicID, chapterID string, page int) (librarydomain.Progress, error)
	SaveMediaProgress(ctx context.Context, comicID, chapterID, mediaID string) (librarydomain.Progress, error)
	PlanWriter(plan librarydomain.Plan) persistence.WriteAction
	ReorderMedia(ctx context.Context, chapterID string, version int, ids []string) (librarydomain.MediaOrder, error)
	Terms(ctx context.Context, kind string, page, size int, keyword string) (librarydomain.TermPage, error)
	SaveTerm(ctx context.Context, kind, id, name string, version int) (librarydomain.Term, error)
	Term(ctx context.Context, kind, id string) (librarydomain.Term, error)
	DeleteTerm(ctx context.Context, kind, id string, version int) error
	UpdateMetadataBatch(ctx context.Context, changes []librarydomain.MetadataChange) ([]librarydomain.MetadataVersion, error)
	Structure(ctx context.Context, comicID string) (librarydomain.Structure, error)
	MutateStructure(ctx context.Context, comicID string, version int, change func(*librarydomain.StructureTree) (*librarydomain.TreeNode, error)) (librarydomain.StructureResult, error)
}
