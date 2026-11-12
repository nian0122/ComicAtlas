package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"
	"time"

	backupapp "comicatlas.local/backend/internal/modules/backup/application"
	backupdomain "comicatlas.local/backend/internal/modules/backup/domain"
	backupfiles "comicatlas.local/backend/internal/modules/backup/infrastructure/filesystem"
	backupsqlite "comicatlas.local/backend/internal/modules/backup/infrastructure/sqlite"
	"comicatlas.local/backend/internal/platform/config"
)

func isBackupCommand(name string) bool {
	return name == "backup" || name == "verify-backup" || name == "restore"
}

func runBackupCommand(parent context.Context, args []string, getenv func(string) string, output io.Writer) error {
	if len(args) == 0 || !isBackupCommand(args[0]) {
		return backupdomain.ErrValidation
	}
	operation := args[0]
	flags := flag.NewFlagSet(operation, flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	var source, destination, data, media string
	var timeout time.Duration
	flags.DurationVar(&timeout, "timeout", 24*time.Hour, "总超时，最长七天")
	if operation != "backup" {
		flags.StringVar(&source, "source", "", "备份包目录")
	}
	if operation != "verify-backup" {
		flags.StringVar(&destination, "destination", "", "必须不存在的目标目录")
	}
	if operation == "backup" {
		flags.StringVar(&data, "data-dir", "", "数据库目录，默认读取 DATA_DIR")
		flags.StringVar(&media, "media-dir", "", "媒体目录，默认读取 MEDIA_DIR")
	}
	if err := flags.Parse(args[1:]); errors.Is(err, flag.ErrHelp) {
		_, err := fmt.Fprintln(output, "用法：comicatlas backup --destination 新目录 [--data-dir 数据目录 --media-dir 媒体目录]\n      comicatlas verify-backup --source 备份目录\n      comicatlas restore --source 备份目录 --destination 新目录\n通用选项：--timeout 24h；备份前需停止共享数据和媒体的写入方。")
		return err
	} else if err != nil || flags.NArg() != 0 || timeout < time.Millisecond || timeout > 7*24*time.Hour {
		return backupdomain.ErrValidation
	}
	provided := map[string]bool{}
	flags.Visit(func(item *flag.Flag) { provided[item.Name] = true })
	if operation != "backup" && strings.TrimSpace(source) == "" || operation != "verify-backup" && strings.TrimSpace(destination) == "" {
		return backupdomain.ErrValidation
	}
	if operation == "backup" {
		if provided["data-dir"] && strings.TrimSpace(data) == "" || provided["media-dir"] && strings.TrimSpace(media) == "" {
			return backupdomain.ErrValidation
		}
		if !provided["data-dir"] || !provided["media-dir"] {
			directories, err := config.LoadDirectories(getenv)
			if err != nil {
				return err
			}
			if !provided["data-dir"] {
				data = directories.Data
			}
			if !provided["media-dir"] {
				media = directories.Media
			}
		}
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	service := backupapp.New(backupsqlite.Databases{}, backupsqlite.Catalogs{}, backupfiles.Filesystem{})
	var value any
	var err error
	switch operation {
	case "backup":
		value, err = service.Create(ctx, backupdomain.CreateRequest{DataDirectory: data, MediaDirectory: media, Destination: destination})
	case "verify-backup":
		value, err = service.Verify(ctx, source)
	case "restore":
		value, err = service.Restore(ctx, backupdomain.RestoreRequest{Source: source, Destination: destination})
	}
	if err != nil {
		return err
	}
	return json.NewEncoder(output).Encode(struct {
		Operation string `json:"operation"`
		Data      any    `json:"data"`
	}{operation, value})
}
