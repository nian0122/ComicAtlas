# ComicAtlas 公开发布仓库

- 本仓库的 main 提供可通过 Docker Compose 安装的发布版本。
- Go 和 Web 在各自的独立仓库开发。发布时从经过验证的提交提取运行源码至 go/ 和 web/。
- 发布说明记录源提交和版本。提交前运行 scripts/release/verify-release-tree.ps1，并验证 Compose 配置和隔离部署。
- 仅提交发布所需源码、构建配置和公开文档。凭据、真实配置、媒体、数据库、构建产物及共享运维资料留在本地。
- 对话、注释和提交信息使用中文。
