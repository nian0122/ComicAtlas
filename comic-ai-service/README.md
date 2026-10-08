# Comic AI Service

独立的 Spring MVC 单体应用，使用 LangChain4j 调用 OpenAI 兼容视觉模型。应用通过只读挂载的 `/manga` 目录读取漫画，不上传或修改漫画文件；复用现有 MySQL 的 `comic_atlas` 数据库，通过 `ai_analysis_task` 表保存分析任务和结果。

## 构建与启动

```powershell
mvn -f comic-ai-service/pom.xml clean package
$env:MANGA_ROOT = 'F:\\manga'
$env:AI_DB_PASSWORD = '...'
$env:AI_API_KEY = 'local'
$env:AI_MODEL = 'OpenGVLab/InternVL3_5-2B'
docker compose --env-file .env -f docker-compose.yml up -d --build comic-ai-service
```

默认配置使用 Compose 内网的本地 OpenAI 兼容服务：`http://comic-model-service:23333/v1`。若只启动 AI 单体而不启动本地模型容器，可通过环境变量切换到其他 OpenAI 兼容服务。任务接口接收容器内挂载根目录下的相对路径，禁止绝对路径、`..` 和越界符号链接。

## API

```http
POST /api/ai/analysis/tasks
Content-Type: application/json

{"comicId":123}
```

返回 `202` 和 `taskId`。服务会根据漫画库记录定位共享挂载目录，不接受宿主机路径。后台任务会递归扫描 JPEG、PNG、WEBP，按文件名顺序从整本漫画中均匀抽取默认 10 页，调用一次视觉模型，再将 JSON 结果写入任务记录。

- `GET /api/ai/analysis/tasks/{taskId}`：查询状态、进度和结果。
- `POST /api/ai/analysis/tasks/{taskId}/cancel`：请求取消排队或运行中的任务。

## 本地模型 Docker 部署

项目根目录的 Compose 已包含 `comic-model-service`。它使用 LMDeploy 加载 InternVL3.5-2B，模型服务只在 Compose 内网监听，AI 单体通过 `http://comic-model-service:23333/v1` 调用。

首次部署前准备模型目录并确认权重文件完整，然后执行：

```powershell
pwsh -File scripts/dev/download-internvl.ps1
docker compose --profile local-ai up -d comic-model-service comic-ai-service
docker compose logs -f comic-model-service
```

模型目录至少应包含 `config.json`、`model.safetensors` 和 InternVL 的自定义代码文件；权重文件大小约为 4.7 GB。模型服务镜像首次启动还会占用约 5 GB Docker 磁盘空间。

`AI_MODEL_IMAGE` 用于指定模型运行镜像，默认由项目 Dockerfile 基于 NVIDIA CUDA 镜像构建。使用阿里云 ACR、Harbor 等私有仓库中的预构建镜像时，将它替换为仓库中的完整镜像地址即可；此时仍建议保留 `build` 配置以便没有预构建镜像时回退构建。

模型权重目录由 `AI_MODEL_DIR` 控制，默认是 `.runtime/models/InternVL3_5-2B`。当前配置为单并发、4096 上下文和单请求，适合 6GB 显存显卡；修改参数前先观察显存峰值。

任务状态包括 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCEL_REQUESTED`、`CANCELLED`。应用重启后，任务记录仍保留；当前版本只自动执行本次进程内提交的任务，生产部署时应补充启动恢复扫描（将遗留 RUNNING 任务转回 QUEUED）。

结果中的作品名和作者是候选，标签自由生成，简介只代表抽样页面，不代表完整 300 页以上漫画的剧情总结。后续由 ComicAtlas 审核和回写，不在本单体直接修改 ComicAtlas 数据库。

## 配置

- `AI_MANGA_ROOT`：容器内只读漫画根目录，默认 `/manga`。
- `AI_SAMPLE_COUNT`：抽样页数，默认 10。
- `AI_MAX_CONCURRENT_TASKS`：后台并发数，默认 1。
- `AI_WORK_ROOT`：临时工作目录，默认 `/var/lib/comic-ai/work`。
- `AI_DB_NAME`：MySQL 数据库名，默认为 `comic_atlas`；主 Compose 使用 `REMOTE_INFRA_HOST` / `REMOTE_MYSQL_PORT` 连接远端 MySQL，并复用 API 数据库账号。
- `AI_TIMEOUT_SECONDS`：单次模型调用超时，默认 120 秒。
