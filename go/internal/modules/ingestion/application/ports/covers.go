package ports

import "context"

type CoverGenerator interface {
	GenerateCover(context.Context, string) error
}
