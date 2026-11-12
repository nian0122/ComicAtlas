package sqlite

import (
	maintenanceports "comicatlas.local/backend/internal/modules/maintenance/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
)

type repository struct{ database *persistence.Database }

func New(database *persistence.Database) *repository { return &repository{database} }

var _ maintenanceports.Repository = (*repository)(nil)
