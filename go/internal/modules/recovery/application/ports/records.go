package ports

import "context"

type Records interface {
	HasComic(context.Context, string) (bool, error)
}
