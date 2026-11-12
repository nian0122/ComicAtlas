package sqlite

import (
	libraryports "comicatlas.local/backend/internal/modules/library/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
)

type sqliteRepository struct{ database *persistence.Database }

func New(database *persistence.Database) *sqliteRepository {
	return &sqliteRepository{database: database}
}

var _ libraryports.Repository = (*sqliteRepository)(nil)
