package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"path/filepath"
	"time"

	backupdomain "comicatlas.local/backend/internal/modules/backup/domain"
	"comicatlas.local/backend/internal/platform/persistence"
)

func isSQLiteBackupCommand(name string) bool {
	return name == "backup-sqlite" || name == "verify-sqlite"
}

func runSQLiteBackupCommand(parent context.Context, args []string, output io.Writer) error {
	if len(args) == 0 || !isSQLiteBackupCommand(args[0]) {
		return backupdomain.ErrValidation
	}
	operation := args[0]
	flags := flag.NewFlagSet(operation, flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	var data, snapshot string
	if operation == "backup-sqlite" {
		flags.StringVar(&data, "data-dir", "", "现有数据库目录")
		flags.StringVar(&snapshot, "destination", "", "必须不存在的快照文件")
	} else {
		flags.StringVar(&snapshot, "source", "", "独立快照文件")
	}
	timeout := flags.Duration("timeout", 10*time.Minute, "总超时，最长一小时")
	if err := flags.Parse(args[1:]); errors.Is(err, flag.ErrHelp) {
		_, err = fmt.Fprintln(output, "用法：comicatlas backup-sqlite --data-dir 数据目录 --destination 新快照文件\n      comicatlas verify-sqlite --source 快照文件\n路径必须为绝对路径；通用选项：--timeout 10m。")
		return err
	} else if err != nil || flags.NArg() != 0 || *timeout < time.Millisecond || *timeout > time.Hour ||
		!filepath.IsAbs(snapshot) || operation == "backup-sqlite" && !filepath.IsAbs(data) {
		return backupdomain.ErrValidation
	}

	ctx, cancel := context.WithTimeout(parent, *timeout)
	defer cancel()
	var info persistence.SnapshotInfo
	var err error
	if operation == "backup-sqlite" {
		info, err = persistence.WriteOnlineSnapshot(ctx, data, snapshot)
	} else {
		info, err = persistence.InspectSnapshotFile(ctx, snapshot)
	}
	if err != nil {
		return err
	}
	return json.NewEncoder(output).Encode(struct {
		Operation string                   `json:"operation"`
		Data      persistence.SnapshotInfo `json:"data"`
	}{operation, info})
}
