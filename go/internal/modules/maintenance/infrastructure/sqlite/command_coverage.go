package sqlite

import (
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
	"database/sql"
)

func reconcileCovered(ctx context.Context, tx *sql.Tx, preview string) error {
	rows, err := tx.QueryContext(ctx, `SELECT covered.covered_by,root.status FROM batch_preview_items covered JOIN batch_preview_items root ON root.preview_id=covered.preview_id AND root.position=covered.covered_by
 WHERE covered.preview_id=? AND covered.status='pending' AND covered.covered_by IS NOT NULL AND root.status IN('succeeded','failed','cancelled','blocked') GROUP BY covered.covered_by`, preview)
	if err != nil {
		return err
	}
	observations := []maintenancedomain.ChildObservation{}
	for rows.Next() {
		var observation maintenancedomain.ChildObservation
		if err = rows.Scan(&observation.Position, &observation.Status); err != nil {
			rows.Close()
			return err
		}
		observations = append(observations, observation)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, observation := range observations {
		outcome, terminal := maintenancedomain.CoveredOutcome(observation.Status)
		if !terminal {
			continue
		}
		if _, err = tx.ExecContext(ctx, "UPDATE batch_preview_items SET status=?,error_code=?,wait_reason=NULL WHERE preview_id=? AND covered_by=? AND status='pending'", outcome.Status, outcome.ErrorCode, preview, observation.Position); err != nil {
			return err
		}
	}
	return nil
}
