# ComicAtlas 0.2 迁移计划

**版本**: 0.2  
**日期**: 2026-07-16  
**状态**: Canonical

---

## 总体策略

> **Shell-first 渐进迁移：先重构架构，再迁移页面，最后收敛 API。**

核心约束：

1. **迁移优先，功能后置**：0.2 前半程只做架构、路由、Layout、页面归属调整，不混入 Category、封面管理等新功能。
2. **每个 Phase 都保持可运行、可测试、可回退**：每完成一个阶段，前端可启动、后端可启动、Playwright 全部通过。
3. **页面迁移不改业务逻辑**：先搬家，后优化 UI。
4. **API 调整放在最后**：页面归属完成后再看哪些接口真正需要拆分。

---

## Phase 0：架构冻结

**目标**：完成设计文档，确定路由、导航、模块边界。

**输出**：

- `docs/architecture/00-index.md`
- `docs/architecture/01-product.md`
- `docs/architecture/02-navigation.md`
- `docs/architecture/03-reading.md`
- `docs/architecture/04-management.md`
- `docs/architecture/05-domain.md`
- `docs/architecture/06-api.md`
- `docs/architecture/07-frontend.md`
- `docs/architecture/08-migration.md`

**完成标准**：

- [ ] 所有架构文档已写入 `docs/architecture/`。
- [ ] 文档经过自审，无 TBD、无矛盾。
- [ ] 用户确认设计文档。

**原则**：开发期间原则上不再改动架构文档。

---

## Phase 1：Shell

**目标**：建立新的 Layout 和 Router 结构，页面先用旧组件 Wrapper。

**任务**：

1. 新建 `layouts/ReadingLayout.vue`。
2. 新建 `layouts/ManagementLayout.vue`。
3. 重构 `router/index.ts`，建立双路由树。
4. 新建 `views/reading/` 和 `views/management/` 目录。
5. 临时 Wrapper：新路由指向旧页面组件，确保功能可跑。
6. 顶部导航改为：首页 / 历史 / 管理。

**完成标准**：

- [ ] 前端可启动。
- [ ] `/`、`/library`、`/history`、`/comic/:id`、`/reader/:chapterId` 可访问。
- [ ] `/manage/*` 可访问。
- [ ] Playwright 现有测试全部通过。

---

## Phase 2：阅读迁移

**目标**：把阅读相关页面迁移到新的 `views/reading/` 目录和 Reading Layout。

**任务**：

1. 迁移 `HomePage.vue` → `views/reading/HomePage.vue`。
2. 迁移 `ComicListPage.vue` → `views/reading/LibraryPage.vue`。
3. 迁移 `ComicDetailPage.vue` → `views/reading/DetailPage.vue`。
4. 迁移 `ReaderPage.vue` → `views/reading/ReaderPage.vue`。
5. 迁移 `HistoryPage.vue` → `views/reading/HistoryPage.vue`。
6. 把阅读相关组件移到 `components/reading/`。
7. 调整 Store 组织。

**完成标准**：

- [ ] 所有阅读页面位于 `views/reading/`。
- [ ] 阅读页面无管理按钮。
- [ ] Playwright 阅读链路测试通过。

---

## Phase 3：管理迁移

**目标**：把管理相关页面迁移到新的 `views/management/` 目录和 Management Layout。

**任务**：

1. 迁移 `ComicEditPage.vue` → `views/management/ComicEditPage.vue`。
2. 迁移 `ImportPage.vue` → `views/management/ImportPage.vue`。
3. 迁移 `TaskCenterPage.vue` → `views/management/TaskPage.vue`。
4. 把管理相关组件移到 `components/management/`。
5. 建立管理侧 Store：`management/comic.ts`、`management/import.ts` 等。
6. 调整管理侧 API 服务。

**完成标准**：

- [ ] 所有管理页面位于 `views/management/`。
- [ ] 管理侧导航为 5 项：漫画 / 导入 / 存储 / 元数据 / 设置。
- [ ] Playwright 管理链路测试通过。

---

## Phase 4：清理与 Legacy 清除

**目标**：删除废弃页面、旧路由、旧组件、旧 Store、旧 API、旧 DTO、旧 CSS。

**删除清单**：

- 页面：
  - `pages/DashboardPage.vue`
  - `pages/OperationLogPage.vue`
  - 旧 `pages/` 目录（如果已空）
- 路由：
  - `/dashboard`
  - `/operations`
  - 旧 `/comics/*` 路由
- 组件：未使用的管理/统计组件
- Store：
  - 旧的 `dashboard.ts`
  - 旧的 `operation.ts`
  - 其他 Legacy Store
- API：
  - 未使用的旧 API 方法
  - 重复的 DTO
- CSS：
  - 未使用的旧样式文件
  - 重复的主题变量

**完成标准**：

- [ ] `pages/` 目录清空或删除。
- [ ] 旧路由不再存在。
- [ ] 无 `*_old.ts`、`*_legacy.ts`、`*Old.vue` 等遗留文件。
- [ ] Playwright 全部通过。
- [ ] 项目进入 0.2 架构成型状态。

---

## Phase 5：新功能开发

**目标**：在稳定的 0.2 架构上叠加新功能。

**功能列表**（按优先级）：

1. **Category 管理**：新增 `category` 表、管理页 Category Tab、漫画编辑绑定 Category。
2. **封面管理**：从已有 page 选择封面、恢复默认。
3. **存储模块**：存储统计、扫描、恢复、清理。
4. **Tag 管理增强**：现有 Tag 功能迁移到 Metadata 模块。
5. **阅读首页增强**：最近加入、继续阅读卡片。
6. **设置模块**：阅读默认设置、缓存、路径、代理。

**原则**：

- 每个新功能独立开发、独立测试。
- 不回头改动已稳定的架构。
- 新功能不混入迁移代码。

---

## 阶段依赖关系

```
Phase 0
   │
   ▼
Phase 1 (Shell)
   │
   ▼
Phase 2 (Reading Migration)
   │
   ▼
Phase 3 (Management Migration)
   │
   ▼
Phase 4 (Cleanup)
   │
   ▼
Phase 5 (New Features)
```

每个阶段都是下一阶段的基础，不能跳过。

---

## 风险与应对

| 风险 | 应对 |
|------|------|
| 迁移过程中测试大面积失败 | 每个 Phase 结束时强制跑 Playwright，失败不回退 |
| 页面迁移与 UI 优化混在一起 | 明确"迁移不改业务逻辑"原则 |
| API 调整提前导致前端炸 | API 调整放到 Phase 4 之后 |
| Legacy 文件清理不彻底 | Phase 4 使用清单逐项检查 |
| 新功能提前混入迁移 | Code Review 时重点检查范围 |
