# 公网阅读入口

公网开放漫画列表、搜索、详情、章节、阅读历史以及图片和视频。允许写入阅读进度 `PUT /api/history/{comicId}`，以及漫画、章节、媒体的喜欢/不喜欢标记 `PUT /api/{comics|chapters|pages}/{id}/reaction`（含取消标记）。所有访问者与本地共用进度及标记，不提供用户隔离。

链路为：公网 Nginx → 远端回环 STCP visitor → 现有 frps → 本机独立 STCP publisher → 本机 HTTP 服务。原远端站点、基础设施隧道及本地完整管理功能保持独立。

## 配置与生成

在忽略跟踪的 `.env` 中设置（示例见 `.env.example`）：

- `FRP_SERVER_ADDR`、`FRP_SERVER_PORT`、`FRP_AUTH_TOKEN`：复用现有 FRP。
- `PUBLIC_READING_LOCAL_HOST`、`PUBLIC_READING_LOCAL_PORT`：本机 HTTP 目标。
- `PUBLIC_READING_REMOTE_PORT`：公网入口端口。
- `PUBLIC_READING_TUNNEL_PORT`：远端内部回环端口，不向公网开放。
- `PUBLIC_READING_STCP_SECRET`：独立密钥，未设置时 Prepare 自动生成。

```powershell
pwsh -NoProfile -File tools/maintenance/manage-public-reading.ps1 -Action Prepare
```

生成 `.runtime/public-reading/` 安装包；其中 TOML 包含凭据，禁止提交。发布端和访问端配置会执行 FRP 语法校验。

## 远端安装

前置条件：已有 `/usr/local/bin/frpc`（项目版本）和 `/usr/sbin/nginx`，选定公网与内部端口均未占用。复制生成的 `frpc-visitor.toml`、`nginx.conf`、`site/index.html` 和两个 unit 到 `/opt/comicatlas-public-reading/`；目录权限 0700，配置权限 0600。

```sh
/usr/local/bin/frpc verify -c /opt/comicatlas-public-reading/frpc-visitor.toml
/usr/sbin/nginx -t -c /opt/comicatlas-public-reading/nginx.conf
install -m 644 /opt/comicatlas-public-reading/comicatlas-reading-tunnel.service /opt/comicatlas-public-reading/comicatlas-reading-nginx.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now comicatlas-reading-tunnel comicatlas-reading-nginx
```

安全组与系统防火墙放行 `PUBLIC_READING_REMOTE_PORT/TCP`，不开放内部回环端口。独立 Nginx 进程使用独立 PID，不修改原站点的 Nginx 配置。

## 本机启动与自启动

```powershell
pwsh -NoProfile -File tools/maintenance/manage-public-reading.ps1 -Action Start
pwsh -NoProfile -File tools/maintenance/manage-public-reading.ps1 -Action Status
pwsh -NoProfile -File tools/maintenance/manage-public-reading.ps1 -Action Stop
# 只有显式执行 InstallTask 才注册登录启动任务。
pwsh -NoProfile -File tools/maintenance/manage-public-reading.ps1 -Action InstallTask
pwsh -NoProfile -File tools/maintenance/manage-public-reading.ps1 -Action RemoveTask
```

任务名为 `ComicAtlas Public Reading FRP`。本机必须开机、登录并运行本地服务，公网才可阅读。FRP 自动重连；计划任务负责异常退出后的重启。更新配置后需 Stop 再 Start，或重新 InstallTask；远端同步配置并重启对应独立服务。

## 权限与验证

`public-reading-nginx.conf.template` 使用方法和路径白名单，禁止管理页面、管理接口及白名单外的写入；媒体仅允许 HQ/LQ/缩略图的媒体扩展名，禁止 metadata.json、回收站文件。进度和偏好标记仅放行精确路径的 PUT，ID 必须为数字，正文限制 16 KiB。

公网代理注入阅读模式标识，前端隐藏管理和导入操作，保留进度保存与喜欢/不喜欢按钮。该标识仅控制界面，实际访问控制由远端 Nginx 执行。视频保留 Range 请求和 206 响应。

```powershell
pwsh -NoProfile -File scripts/qa/verify-public-reading.ps1 -BaseUrl 'http://<远端地址>:<公网端口>'
```

脚本不修改阅读进度；部署验收另外使用已有进度原值验证 PUT/GET，并验证真实媒体 Range 请求。回滚公网发布使用 `systemctl disable --now comicatlas-reading-nginx comicatlas-reading-tunnel` 和本机 `RemoveTask`，不影响其他服务。

## 部署记录（2026-10-01）

- 公网入口选择 8088，本机目标保持 80，远端内部 visitor 使用回环 18080；原远端 80 站点保留。
- 两个远端 systemd 服务已启动并启用自启动，本地独立登录启动任务已注册。
- FRP 双端配置、远端 Nginx 语法校验通过；21 项 HTTP 白名单检查通过。
- 通过临时验收通道完成浏览器验证：漫画库、详情、移动历史可用，管理及偏好写入口隐藏；现有阅读进度原值 PUT/GET 成功；图片和真实 MP4 的 Range 均返回 206。
- 前端构建、FSD、类型检查、lint、60 项单元测试和修改文件格式检查通过。完整 `pnpm check` 被已有 `ComicListPage.vue` 按钮规范问题阻断，不宣称完整门禁通过。
- 远端系统防火墙 INPUT 为 ACCEPT，8088 监听正常；公网直连仍超时，等待云安全组放行 TCP 8088 后复验。

### 同日追加：开放喜欢与不喜欢

- 精确放行漫画、章节、媒体的 reaction PUT 接口，恢复桌面与移动端按钮；管理操作保持禁止。
- 远端 Nginx 已平滑重载，本机前端镜像已更新。26 项 HTTP 边界检查通过。
- 经临时验收通道，三类实体的 LIKE、DISLIKE、NONE 均实际保存并读回通过，测试后恢复原标记；浏览器确认按钮可见且管理入口仍隐藏。
- 构建、类型与 FSD 检查通过；完整前端门禁仍被上述既有按钮规范问题阻断。公网 8088 仍待安全组放行。

### 安全组放行后验收

- 用户放行 TCP 8088 后，直接通过公网地址执行 26 项 HTTP 边界检查，全部通过。
- 真实浏览器公网直连主页与漫画库成功，站内资源请求正常，管理入口隐藏。此前的公网连接阻塞已解除。

### 入口迁移至远端 80

原 rag-diabetes 前端下架并释放远端 80 后，将 `PUBLIC_READING_REMOTE_PORT` 切换为 80；入口迁移后需在云安全组放行 TCP 80。

- 新配置已生成并通过 Nginx 语法校验。独立公网阅读服务现监听 `0.0.0.0:80`；系统 Nginx 保持禁用，rag-diabetes 服务与数据未删除。
- 公网 HTTP 白名单 26 项和浏览器主页、漫画库、静态资源检查全部通过，`/manage` 仍返回 403。
- 原公网 8088 已停止响应；旧 Nginx 配置备份保存在远端 `/opt/comicatlas-public-reading/nginx.conf.8088`，项目数据仍保留。

## 个人网站备案页

`site/personal-blog/index.html` 是个人技术笔记的静态首页，不依赖数据库或 Miniflux。备案前 `.env` 使用 `PUBLIC_SITE_MODE=closed`，域名入口返回 403；这是首次备案期间保持网站关闭的状态。当前页面和关闭配置已部署到 `/opt/comicatlas-public-reading/site/` 与独立公网 Nginx，配置检查通过。

备案申请可按实际情况填写网站名称“个人技术笔记”、网站内容为个人非经营性技术记录；不要申报实际不提供的服务。首次备案通过后，将 `.env` 中 `PUBLIC_SITE_MODE` 改为 `open`，并填写 `PUBLIC_SITE_ICP_NUMBER`，再运行 `tools/maintenance/manage-public-reading.ps1 -Action Prepare`。生成的 `nginx.conf` 和 `site/index.html` 更新至远端目录后，执行 Nginx 配置检查并重启 `comicatlas-reading-nginx`。生成脚本会在开放模式校验备案号并自动添加工信部备案查询链接。

2026-10-01 备案期间暂停漫画公网映射：本机发布端已停止并移除登录自启任务，远端 `comicatlas-reading-tunnel` 已停止并禁用自启。独立 Nginx 仍监听 80 供备案域名关闭页使用；漫画公网入口不可用，本地服务和数据未停止或删除。
