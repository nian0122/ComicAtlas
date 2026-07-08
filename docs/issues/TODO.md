# ComicAtlas 问题与待办总览

> 本文件汇总已修复问题与后续阶段待办。详细内容见 `docs/issues/BUG-XXX.md` 与 `docs/superpowers/plans/`。

## 已修复（Beta v0.1）

| 编号 | 模块 | 标题 | 状态 |
|------|------|------|------|
| BUG-001 | Reader | Reader 忽略 URL 中的 page 参数，导致 Detail/History 跳转页码丢失 | 已修复 |
| BUG-002 | Reader / History | Reader 保存进度后不刷新 Reading Center | 已修复 |
| BUG-003 | Task Center | PARSING 状态仍显示取消按钮但后端拒绝 | 已修复 |
| BUG-004 | API / History | HistoryVO coverUrl 路径错误，封面 404 | 已修复 |
| BUG-005 | Build / Docker | Dockerfile 直接 COPY 旧 target jar，容器代码与 Git 不一致 | 已修复 |
| BUG-006 | Worker / RabbitMQ | Worker 无法反序列化 Instant 字段，导入任务永远 PENDING | 已修复 |
| BUG-007 | Import / Docker | 宿主机路径与容器路径不一致，Docker 部署下导入失败 | 已修复 |

## Phase I：Reader Enhancement

详见 [`docs/superpowers/plans/2026-07-08-reader-enhancement-phase1.md`](../superpowers/plans/2026-07-08-reader-enhancement-phase1.md)。

| 优先级 | 模块 | 标题 | 状态 |
|--------|------|------|------|
| P0 | Reader | 图片预加载 + LQ 优先渐进加载 | 待实现 |
| P0 | Reader | 缩放 / fit 模式（fit-width / fit-height / fit-screen / actual） | 待实现 |
| P1 | Reader | 阅读方向切换（LTR / RTL / vertical） | 待实现 |
| P1 | Reader | 工具栏自动隐藏 / 显隐切换 | 待实现 |
| P1 | Reader | 页码缩略图快速跳转 | 待实现 |
| P2 | Reader | 全屏模式 | 待实现 |
| P2 | Reader | 双页模式 | 待实现 |
| P2 | Reader | 移动端触摸手势 | 待实现 |

## 后续阶段（待定）

| 优先级 | 模块 | 标题 | 计划阶段 |
|--------|------|------|----------|
| ⭐⭐⭐⭐ | LQ | 手动触发体验优化、批量生成 | Phase II |
| ⭐⭐⭐ | Dashboard | 统计图表 | Phase III |
| ⭐⭐ | Settings | 系统配置页面 | Phase IV |

## 全链路走查记录

| 日期 | 执行人 | 结论 | 备注 |
|------|--------|------|------|
| 2026-07-07 | Sisyphus | 静态代码走查 + Docker 环境启动 + Playwright smoke 通过 | 修复 BUG-001~005；发现 BUG-006/007 阻塞导入链路 |
| 2026-07-08 | Sisyphus | 全链路 Happy Path 验证 + Playwright E2E 4/4 通过 | 修复 BUG-006/007；修复前端 API 解包与阅读器问题；提交 dc17870/d225395 |
| 2026-07-08 | Sisyphus | 发布 v0.1.0 | 清理测试数据、修复 RabbitMQ 队列声明冲突、创建 tag `v0.1.0` |
