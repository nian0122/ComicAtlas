package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"errors"
	"time"
)

func (repo *repository) SelectPurgeEntries(ctx context.Context, selection lifecycledomain.EntrySelection, days int, now time.Time) ([]lifecycledomain.SelectedPurgeEntry, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	entries, err := selectEntries(ctx, tx, selection)
	if err != nil {
		return nil, err
	}
	lifecycledomain.SortPurgeSelection(entries)
	state := lifecycledomain.NewPurgeSelection(entries, selection.ExcludedIDs)
	result := make([]lifecycledomain.SelectedPurgeEntry, 0, len(entries))
	for _, entry := range entries {
		value, needsScope := state.Next(entry.Entry, days)
		if !needsScope {
			result = append(result, value)
			continue
		}
		// 每次只保留一个有界根范围；不把整批文件清单装入结果。
		scope, readErr := purgeScope(ctx, tx, entry.Entry.ID)
		if readErr != nil {
			code, expected := lifecycledomain.PurgeSelectionBlocker(readErr)
			if !expected {
				return nil, readErr
			}
			value.Blocker = code
			result = append(result, value)
			continue
		}
		preview, evaluateErr := lifecycledomain.EvaluatePurge(scope, days, now)
		if evaluateErr != nil {
			code, expected := lifecycledomain.PurgeSelectionBlocker(evaluateErr)
			if !expected {
				return nil, evaluateErr
			}
			value.Blocker = code
			result = append(result, value)
			continue
		}
		value = state.Preview(value, scope, preview)
		var busy error
		if value.Blocker == "" {
			busy = blockChanges(ctx, tx, scope.Root.Target.ComicID, "")
			if busy != nil && !errors.Is(busy, lifecycledomain.ErrConflict) {
				return nil, busy
			}
		}
		result = append(result, state.Resolve(value, scope, busy != nil))
	}
	return result, tx.Commit()
}
func (repo *repository) PurgeTarget(ctx context.Context, id, stamp string, days int) (lifecycledomain.Target, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	defer tx.Rollback()
	scope, err := purgeScope(ctx, tx, id)
	if errors.Is(err, lifecycledomain.ErrConflict) || errors.Is(err, lifecycledomain.ErrNotFound) {
		return lifecycledomain.Target{}, lifecycledomain.ErrScopeChanged
	}
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	preview, err := lifecycledomain.EvaluatePurge(scope, days, time.Now().UTC())
	if err != nil {
		return lifecycledomain.Target{}, err
	}
	if err = lifecycledomain.ValidatePurgeSnapshot(stamp, preview); err != nil {
		return lifecycledomain.Target{}, err
	}
	return scope.Root.Target, tx.Commit()
}
