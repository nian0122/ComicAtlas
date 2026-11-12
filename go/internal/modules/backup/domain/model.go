package domain

const (
	Format           = "comicatlas-backup"
	IndexFormat      = "comicatlas-backup-index"
	Version          = 1
	DatabasePath     = "data/comicatlas.sqlite3"
	CatalogPath      = "catalog.sqlite3"
	ManifestPath     = "manifest.json"
	ReceiptPath      = "restore.json"
	MaxEntries       = 2000000
	MaxPathBytes     = 4096
	MaxDepth         = 256
	MaxManifestBytes = 16384
	File             = "file"
	Directory        = "directory"
)

type Entry struct {
	Path       string
	Kind       string
	Size       int64
	SHA256     string
	ModifiedAt string
}

type Summary struct {
	Files       int64 `json:"files"`
	Directories int64 `json:"directories"`
	Bytes       int64 `json:"bytes"`
}

type DatabaseInfo struct {
	Format            string `json:"format"`
	MigrationCount    int    `json:"migrationCount"`
	MigrationChecksum string `json:"migrationChecksum"`
}

type Object struct {
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type Manifest struct {
	Format    string       `json:"format"`
	Version   int          `json:"version"`
	ID        string       `json:"id"`
	CreatedAt string       `json:"createdAt"`
	Database  DatabaseInfo `json:"database"`
	Catalog   Object       `json:"catalog"`
	Summary   Summary      `json:"summary"`
}

type RestoreReceipt struct {
	Format     string       `json:"format"`
	Version    int          `json:"version"`
	BackupID   string       `json:"backupId"`
	RestoredAt string       `json:"restoredAt"`
	Database   DatabaseInfo `json:"database"`
	Summary    Summary      `json:"summary"`
}

type CreateRequest struct {
	DataDirectory  string `json:"-"`
	MediaDirectory string `json:"-"`
	Destination    string `json:"-"`
}

type RestoreRequest struct {
	Source      string `json:"-"`
	Destination string `json:"-"`
}
