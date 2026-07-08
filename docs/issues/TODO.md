# ComicAtlas Beta v0.1 问题与待办总览

> 本文件汇总 Beta v0.1 期间发现的问题和待办项。详细内容见 `docs/issues/BUG-XXX.md`。

## 阻塞发布的 Bug

| 编号 | 模块 | 标题 | 状态 |
|------|------|------|------|
| BUG-001 | Reader | Reader 忽略 URL 中的 page 参数，导致 Detail/History 跳转页码丢失 | 已修复 |
| BUG-004 | API / History | HistoryVO coverUrl 路径错误，封面 404 | 已修复 |
| BUG-005 | Build / Docker | Dockerfile 直接 COPY 旧 target jar，容器代码与 Git 不一致 | 已修复 |
| BUG-006 | Worker / RabbitMQ | Worker 无法反序列化 Instant 字段，导入任务永远 PENDING | 已修复 |
| BUG-007 | Import / Docker | 宿主机路径与容器路径不一致，Docker 部署下导入失败 | 已修复 |

## 非阻塞 Bug

| 编号 | 模块 | 标题 | 状态 |
|------|------|------|------|
| BUG-002 | Reader / History | Reader 保存进度后不刷新 Reading Center | 已修复 |
| BUG-003 | Task Center | PARSING 状态仍显示取消按钮但后端拒绝 | 已修复 |

## 已知 TODO（Beta 后不立即做）

| 编号 | 模块 | 标题 | 计划阶段 |
|------|------|------|----------|
|      |      |      |          |

## 全链路走查记录

| 日期 | 执行人 | 结论 | 备注 |
|------|--------|------|------|
| 2026-07-07 | Sisyphus | 静态代码走查 + Docker 环境启动 + Playwright smoke 通过 | 修复 BUG-001~005；发现 BUG-006/007 阻塞导入链路 |
| 2026-07-08 | Sisyphus | 全链路 Happy Path 验证 + Playwright E2E 4/4 通过 | 修复 BUG-006/007；修复前端 API 解包与阅读器问题；提交 dc17870/d225395 |
