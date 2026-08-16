# FRP 基础设施连接

ComicAtlas 使用 FRP STCP 访问远端 MySQL、Redis、RabbitMQ、Nacos 和 FRPS Dashboard。远端服务与 Dashboard 继续只绑定 `127.0.0.1`，公网只需开放 `.env` 中 `FRP_SERVER_PORT` 对应的 TCP 端口。

连接由三个进程组成：

1. 公网服务器上的 `frps` 接收并协调连接。
2. 同一服务器上的 `frpc-provider` 将六个基础设施端口和 Dashboard 注册为 STCP 服务。
3. Windows 开发机上的 `frpc visitor` 在本地绑定对应端口，供宿主机、Docker Desktop 和浏览器使用。

项目固定使用 FRP `v0.70.1`。管理脚本会从 GitHub Release 下载客户端，并使用官方 `frp_sha256_checksums.txt` 校验归档。

## 一、初始化配置

在项目根目录执行：

```powershell
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action Initialize
```

脚本会把以下配置写入不受 Git 跟踪的 `.env`：

```dotenv
FRP_SERVER_ADDR=远端服务器地址
FRP_SERVER_PORT=7000
FRP_DASHBOARD_PORT=7500
REMOTE_MYSQL_PORT=3306
REMOTE_REDIS_PORT=6379
REMOTE_RABBITMQ_PORT=5672
REMOTE_RABBITMQ_MANAGEMENT_PORT=15672
REMOTE_NACOS_HTTP_PORT=8848
REMOTE_NACOS_GRPC_PORT=9848
FRP_AUTH_TOKEN=自动生成
FRP_STCP_SECRET=自动生成
FRP_VISITOR_BIND_ADDR=0.0.0.0
FRP_PROVIDER_USER=comicatlas-infra
FRP_DASHBOARD_USER=admin
FRP_DASHBOARD_PASSWORD=自动生成
```

`FRP_SERVER_ADDR` 是唯一的远端服务器公网地址，同时供 FRP 连接和 MySQL 备份脚本使用；`REMOTE_INFRA_HOST` 则是项目容器访问本机 FRP visitor 的入口，两者职责不同。`FRP_AUTH_TOKEN` 用于 frpc/frps 身份验证，`FRP_STCP_SECRET` 用于限制 visitor 访问。Provider 的每个 STCP proxy 还通过 `allowUsers = ["comicatlas-local"]` 只允许本项目 visitor 用户访问。Dashboard 用户名和随机密码也只保存在 `.env` 与远端权限为 `0600` 的环境文件中，禁止提交或复制到文档、日志。

## 二、准备远端安装包

```powershell
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action PrepareServer
```

生成目录为 `.runtime/frp/server-bundle/`，包含：

- Linux AMD64 的 `frps`、`frpc`；
- `frps.toml`、`frpc-provider.toml`；
- 两个 systemd unit；
- 包含认证信息的 `comicatlas-frp.env`。

把该目录传到远端服务器后执行：

```bash
sudo install -m 0755 frps frpc /usr/local/bin/
sudo install -d -m 0755 /opt/comicatlas-frp /var/log/frp
sudo install -m 0644 frps.toml frpc-provider.toml /opt/comicatlas-frp/
sudo install -m 0600 comicatlas-frp.env /etc/comicatlas-frp.env
sudo install -m 0644 frps.service frpc-provider.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now frps.service frpc-provider.service
sudo systemctl status frps.service frpc-provider.service
```

云安全组和服务器防火墙只需开放 `FRP_SERVER_PORT/TCP`。`REMOTE_*_PORT` 与 `FRP_DASHBOARD_PORT` 只绑定回环地址，不要加入公网入站规则。

## 三、安装本地 visitor

先关闭旧 SSH 隧道并安装登录自启动任务：

```powershell
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action InstallTask -ReplaceSshTunnel
```

`-ReplaceSshTunnel` 会停止并禁用旧计划任务 `ComicAtlas Remote Infra Tunnel`。如果旧 SSH 隧道是手动启动的，脚本会报告端口占用，需要先手动结束该进程。旧脚本仍保留，可以随时恢复。

常用命令：

```powershell
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action Verify
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action Start
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action Status
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action Stop
pwsh -File tools/maintenance/manage-remote-infra-frp.ps1 -Action RemoveTask
```

计划任务在登录时启动 `frpc`，进程异常退出后每分钟自动重启。FRP 客户端配置同时启用了 TLS、Wire Protocol v2、TCP mux keepalive，并在首次登录失败后持续重连。

本地 visitor 启动后，在浏览器打开 `http://127.0.0.1:${FRP_DASHBOARD_PORT}`，使用 `.env` 中的 `FRP_DASHBOARD_USER` 和 `FRP_DASHBOARD_PASSWORD` 登录。Dashboard visitor 固定绑定 `127.0.0.1`，不会随 `FRP_VISITOR_BIND_ADDR` 暴露到局域网。

## 四、安全边界

- `.runtime/`、`tools/vendor/frp/` 和 `.env` 均已加入 Git 忽略规则。
- `FRP_VISITOR_BIND_ADDR=0.0.0.0` 是为了让 Docker Desktop 通过 `host.docker.internal` 访问端口；Windows 防火墙仍应阻止局域网入站访问这六个端口。
- FRP Dashboard 只监听远端回环地址，并通过需要 STCP secret 的 visitor 提供给本机 `127.0.0.1:${FRP_DASHBOARD_PORT}`；不开放公网 Dashboard、HTTP vhost 或 SSH Tunnel Gateway。
- 升级时先更新远端 `frps`，再更新 provider 和本地 visitor。

## 五、部署日志

### 2026-08-14

- 将远端主机、基础设施端口、FRP 服务端口和 Dashboard 端口统一收口到本地及远端权限受限的 `.env` 环境文件，项目配置不再固化远端连接信息。
- 远端 MySQL、Redis、RabbitMQ、RabbitMQ Management、Nacos HTTP 和 Nacos gRPC 的 Docker 发布端口统一限制为 `127.0.0.1`，公网只保留 FRP 服务端口。
- 重新部署并验证远端 Compose、`frps.service` 和 `frpc-provider.service`；七个 FRP 代理端口、Dashboard、RabbitMQ Management、Nacos 和 Worker 只读 MySQL 登录均验证通过。

### 2026-08-09

- 修复 FRP 客户端心跳配置不匹配：本地 visitor 和远端 provider 恢复每 30 秒发送心跳，服务端继续使用 90 秒心跳超时。
- 修复前两个客户端会被 `frps` 每约 90 秒以 `heartbeat timeout` 主动断开并自动重连；修复后持续观察超过两个旧超时周期，未再新增超时或重连。

### 2026-08-08

- 将远端旧版 FRP 替换为 `v0.70.1`，统一安装到 `/usr/local/bin/`，配置集中到 `/opt/comicatlas-frp/`。
- 启用 `frps.service` 和 `frpc-provider.service`，两个服务均设置为 systemd 开机自启。
- 通过 STCP 提供 MySQL、Redis、RabbitMQ、RabbitMQ Management、Nacos HTTP、Nacos gRPC 和 FRPS Dashboard 共七个代理。
- Dashboard 仅监听远端 `127.0.0.1:7500`，本地通过 visitor 的 `127.0.0.1:7500` 访问；公网仍只开放 `7000/TCP`。
- 为跨用户 STCP 显式配置 `allowUsers = ["comicatlas-local"]`，避免 visitor 端口已监听但实际连接被拒绝。
- 本地安装 `ComicAtlas Remote Infra FRP` 登录自启动任务，并禁用旧 SSH Tunnel 任务。
- 验证结果：七个代理健康检查通过，Dashboard 使用真实 Chromium 访问返回 HTTP 200，页面显示 FRP `v0.70.1`。
