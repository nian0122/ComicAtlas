package persistence

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"time"
)

const (
	onlineSnapshotFormat      = "comicatlas-sqlite-online-v1"
	onlineSnapshotDatabase    = "comicatlas.sqlite3"
	onlineSnapshotReceiptName = "snapshot.json"
)

var onlineSnapshotName = regexp.MustCompile(`^sqlite-[0-9]{8}T[0-9]{9}Z-[0-9a-f]{8}$`)

type onlineSnapshotReceipt struct {
	Format        string                `json:"format"`
	CreatedAt     string                `json:"createdAt"`
	ContainsMedia bool                  `json:"containsMedia"`
	Database      onlineSnapshotCatalog `json:"database"`
}

type onlineSnapshotCatalog struct {
	File           string `json:"file"`
	Bytes          int64  `json:"bytes"`
	Format         string `json:"format"`
	MigrationCount int    `json:"migrationCount"`
}

// OnlineSnapshots 把运行中的 SQLite 发布为独立快照目录，只保留最近若干份。
type OnlineSnapshots struct {
	dataDirectory   string
	backupDirectory string
	keep            int
	now             func() time.Time
}

func NewOnlineSnapshots(dataDirectory, backupDirectory string, keep int) (*OnlineSnapshots, error) {
	if keep < 1 || keep > 365 {
		return nil, errors.New("快照保留数量必须为 1..365")
	}
	if !filepath.IsAbs(dataDirectory) || !filepath.IsAbs(backupDirectory) {
		return nil, errors.New("快照目录必须为绝对路径")
	}
	snapshots := &OnlineSnapshots{dataDirectory: filepath.Clean(dataDirectory), backupDirectory: filepath.Clean(backupDirectory), keep: keep, now: time.Now}
	if snapshots.dataDirectory == snapshots.backupDirectory {
		return nil, errors.New("快照目录不能与数据库目录相同")
	}
	return snapshots, nil
}

func (snapshots *OnlineSnapshots) Run(ctx context.Context) (int, error) {
	if err := ctx.Err(); err != nil {
		return 0, err
	}
	if err := os.MkdirAll(snapshots.backupDirectory, 0o750); err != nil {
		return 0, fmt.Errorf("创建快照目录: %w", err)
	}
	var suffix [4]byte
	if _, err := rand.Read(suffix[:]); err != nil {
		return 0, err
	}
	pending := filepath.Join(snapshots.backupDirectory, ".pending-"+hex.EncodeToString(suffix[:]))
	if err := os.Mkdir(pending, 0o750); err != nil {
		return 0, fmt.Errorf("创建快照暂存目录: %w", err)
	}
	published := false
	defer func() {
		if !published {
			_ = os.RemoveAll(pending)
		}
	}()
	destination := filepath.Join(pending, onlineSnapshotDatabase)
	info, err := WriteOnlineSnapshot(ctx, snapshots.dataDirectory, destination)
	if err != nil {
		return 0, err
	}
	size, err := fileSize(destination)
	if err != nil {
		return 0, err
	}
	now := snapshots.now().UTC()
	receipt := onlineSnapshotReceipt{
		Format:    onlineSnapshotFormat,
		CreatedAt: now.Format(time.RFC3339Nano),
		Database: onlineSnapshotCatalog{
			File:           onlineSnapshotDatabase,
			Bytes:          size,
			Format:         info.Format,
			MigrationCount: info.MigrationCount,
		},
	}
	content, err := json.Marshal(receipt)
	if err != nil {
		return 0, err
	}
	content = append(content, '\n')
	if err = os.WriteFile(filepath.Join(pending, onlineSnapshotReceiptName), content, 0o600); err != nil {
		return 0, fmt.Errorf("写入快照记录: %w", err)
	}
	// 时间戳保留毫秒，附加随机后缀，目录名按时间排序即可决定轮换顺序。
	name := fmt.Sprintf("sqlite-%s%03dZ-%x", now.Format("20060102T150405"), now.Nanosecond()/1_000_000, suffix)
	if err = os.Rename(pending, filepath.Join(snapshots.backupDirectory, name)); err != nil {
		return 0, fmt.Errorf("发布快照: %w", err)
	}
	published = true
	if err = snapshots.retain(); err != nil {
		return 1, err
	}
	return 1, nil
}

func (snapshots *OnlineSnapshots) retain() error {
	entries, err := os.ReadDir(snapshots.backupDirectory)
	if err != nil {
		return fmt.Errorf("读取快照目录: %w", err)
	}
	names := []string{}
	for _, entry := range entries {
		if entry.IsDir() && onlineSnapshotName.MatchString(entry.Name()) {
			names = append(names, entry.Name())
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(names)))
	for _, name := range names[min(len(names), snapshots.keep):] {
		if err = removeOnlineSnapshot(filepath.Join(snapshots.backupDirectory, name)); err != nil {
			return err
		}
	}
	return nil
}

// removeOnlineSnapshot 只删除本程序发布的目录；出现未知文件时保留原样并报错。
func removeOnlineSnapshot(directory string) error {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return fmt.Errorf("读取快照 %s: %w", filepath.Base(directory), err)
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() {
			return fmt.Errorf("快照 %s 包含非普通文件", filepath.Base(directory))
		}
		switch entry.Name() {
		case onlineSnapshotDatabase, onlineSnapshotReceiptName,
			onlineSnapshotDatabase + "-journal", onlineSnapshotDatabase + "-wal", onlineSnapshotDatabase + "-shm":
		default:
			return fmt.Errorf("快照 %s 包含意外文件 %s", filepath.Base(directory), entry.Name())
		}
	}
	if err = os.RemoveAll(directory); err != nil {
		return fmt.Errorf("清理快照 %s: %w", filepath.Base(directory), err)
	}
	return nil
}

func fileSize(path string) (int64, error) {
	info, err := os.Stat(path)
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
}
