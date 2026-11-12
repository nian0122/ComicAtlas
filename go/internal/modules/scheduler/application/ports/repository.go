package ports

import (
	schedulerdomain "comicatlas.local/backend/internal/modules/scheduler/domain"
	"context"
)

type Repository interface {
	LoadSettings(context.Context) (map[string]schedulerdomain.Settings, error)
	SaveSettings(context.Context, string, schedulerdomain.Settings, string) error
	CreateRun(context.Context, schedulerdomain.Run, int) error
	FinishRun(context.Context, string, string, int, string) error
	Runs(context.Context, string, int) ([]schedulerdomain.Run, error)
	InterruptRuns(context.Context, string) error
}
