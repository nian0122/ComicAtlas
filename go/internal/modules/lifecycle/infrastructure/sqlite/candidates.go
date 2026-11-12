package sqlite

import (
	"bytes"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"encoding/json"
	"strings"
)

func scopeWhere(value lifecycledomain.Target) (string, []any) {
	if value.Type == "media" {
		return "m.id=? AND ch.comic_id=?", []any{value.ID, value.ComicID}
	}
	if value.Type == "chapter" {
		return "ch.id=? AND ch.comic_id=?", []any{value.ID, value.ComicID}
	}
	return "ch.comic_id=?", []any{value.ComicID}
}

func candidates(ctx context.Context, tx *sql.Tx, value lifecycledomain.Target) ([]lifecycledomain.FileCandidate, error) {
	items := []lifecycledomain.FileCandidate{}
	where, args := scopeWhere(value)
	join := " FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id "
	queries := []string{
		"SELECT m.relative_path,m.size,m.sha256,(m.hq_state='released' AND m.sha256='')" + join + "WHERE (m.hq_state='available' OR (m.sha256='' AND m.hq_state='released')) AND " + where,
		"SELECT l.relative_path,l.size,l.sha256,0" + join + "JOIN image_lq l ON l.media_id=m.id WHERE " + where,
		"SELECT 'retired/uploads/'||s.id||'/'||r.kind||'.bin',r.size,r.sha256,0" + join + "JOIN upload_sessions s ON s.replace_media_id=m.id JOIN upload_retired r ON r.task_id=s.task_id WHERE r.status='moved' AND " + where,
	}
	for _, query := range queries {
		rows, err := tx.QueryContext(ctx, query+" LIMIT ?", append(args, lifecycledomain.MaxFiles+1)...)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var item lifecycledomain.FileCandidate
			var sourceAbsent int
			if err = rows.Scan(&item.Path, &item.Size, &item.SHA256, &sourceAbsent); err != nil {
				rows.Close()
				return nil, err
			}
			item = lifecycledomain.CandidateForFile(item.Path, item.Size, item.SHA256, sourceAbsent != 0)
			items = append(items, item)
			if len(items) > lifecycledomain.MaxFiles {
				rows.Close()
				return nil, lifecycledomain.ErrValidation
			}
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
	}
	if value.Type == "comic" {
		cover := lifecycledomain.FileCandidate{Path: "thumbs/" + value.ID + "/cover.webp", Size: -1}
		err := tx.QueryRowContext(ctx, "SELECT size,sha256 FROM comic_covers WHERE comic_id=?", value.ID).Scan(&cover.Size, &cover.SHA256)
		if err != nil && err != sql.ErrNoRows {
			return nil, err
		}
		if err == nil {
			items = append(items, cover)
		}
		items = append(items, lifecycledomain.FileCandidate{Path: "metadata/" + value.ID + ".json", Size: -1})
	}
	if err := lifecycledomain.ValidateCandidates(items); err != nil {
		return nil, err
	}
	roots, err := lifecycledomain.TrashDirectories(value)
	if err != nil {
		return nil, err
	}
	for _, root := range roots {
		var shared bool
		pattern := root + "/*"
		err = tx.QueryRowContext(ctx, `SELECT EXISTS(
		 SELECT 1 FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE lower(m.relative_path) GLOB ? AND (ch.comic_id<>? OR (?<>'' AND ch.id<>?))
		 UNION ALL
		 SELECT 1 FROM image_lq l JOIN media m ON m.id=l.media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE lower(l.relative_path) GLOB ? AND (ch.comic_id<>? OR (?<>'' AND ch.id<>?)))`,
			pattern, value.ComicID, value.ChapterID, value.ChapterID, pattern, value.ComicID, value.ChapterID, value.ChapterID).Scan(&shared)
		if err != nil {
			return nil, err
		}
		if shared {
			return nil, lifecycledomain.ErrConflict
		}
	}
	for _, item := range items {
		if len(roots) != 0 && (strings.HasPrefix(item.Path, "hq/") || strings.HasPrefix(item.Path, "lq/")) &&
			!strings.HasPrefix(item.Path, roots[0]+"/") && !strings.HasPrefix(item.Path, roots[1]+"/") {
			return nil, lifecycledomain.ErrConflict
		}
	}
	return items, nil
}

func encodeCandidates(items []lifecycledomain.FileCandidate) (string, error) {
	var output bytes.Buffer
	output.WriteByte('[')
	for index, item := range items {
		data, err := json.Marshal(item)
		if err != nil {
			return "", err
		}
		if output.Len()+len(data)+2 > lifecycledomain.MaxJournalBytes {
			return "", lifecycledomain.ErrValidation
		}
		if index > 0 {
			output.WriteByte(',')
		}
		output.Write(data)
	}
	output.WriteByte(']')
	return output.String(), nil
}
