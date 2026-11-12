package persistence

import (
	"context"
	"errors"
)

// RejectRetiredSharedCatalog 防止旧试用索引进入常规媒体管理流程。
func RejectRetiredSharedCatalog(ctx context.Context, database *Database) error {
	var exists bool
	err := database.Reader.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM app_metadata WHERE key='shared_media_preview_v1')").Scan(&exists)
	if err != nil {
		return err
	}
	if exists {
		return errors.New("共享试用索引已退役，请使用常规媒体库的数据目录")
	}
	return nil
}
