# ComicAtlas Beta v0.1 问题与待办总览

> 本文件汇总 Beta v0.1 期间发现的问题和待办项。详细内容见 `docs/issues/BUG-XXX.md`。

## 阻塞发布的 Bug

| 编号 | 模块 | 标题 | 状态 |
|------|------|------|------|
| BUG-001 | Reader | Reader 忽略 URL 中的 page 参数，导致 Detail/History 跳转页码丢失 | 已修复 |
| BUG-004 | API / History | HistoryVO coverUrl 路径错误，封面 404 | 已修复 |

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
| 2026-07-07 | Sisyphus | 静态代码走查 + 4 个问题修复 | 修复后前端构建通过 |
