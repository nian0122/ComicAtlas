package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"comicatlas.local/backend/internal/platform/config"
)

func main() {
	logOutput := os.Stdout
	if len(os.Args) > 1 && (isBackupCommand(os.Args[1]) || isSQLiteBackupCommand(os.Args[1])) {
		logOutput = os.Stderr
	}
	slog.SetDefault(slog.New(slog.NewJSONHandler(logOutput, nil)))
	if err := run(); err != nil {
		slog.Error("应用退出", "error", err)
		os.Exit(1)
	}
}

func run() error {
	if len(os.Args) > 1 && (isBackupCommand(os.Args[1]) || isSQLiteBackupCommand(os.Args[1])) {
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		if isSQLiteBackupCommand(os.Args[1]) {
			return runSQLiteBackupCommand(ctx, os.Args[1:], os.Stdout)
		}
		return runBackupCommand(ctx, os.Args[1:], os.Getenv, os.Stdout)
	}
	configuration, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}
	if len(os.Args) > 1 {
		if len(os.Args) != 2 || os.Args[1] != "healthcheck" {
			return errors.New("支持 healthcheck、backup、verify-backup、restore、backup-sqlite、verify-sqlite 子命令")
		}
		host, port, _ := net.SplitHostPort(configuration.HTTPAddress)
		if host == "" || host == "0.0.0.0" || host == "::" {
			host = "127.0.0.1"
		}
		client := http.Client{Timeout: 2 * time.Second, Transport: &http.Transport{Proxy: nil}}
		response, requestErr := client.Get("http://" + net.JoinHostPort(host, port) + "/health/ready")
		if requestErr != nil {
			return requestErr
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			return fmt.Errorf("健康检查返回 %d", response.StatusCode)
		}
		return nil
	}
	return runServer(configuration)
}
