# ComicAtlas 管理功能综合设计

**日期**: 2026-07-15  
**状态**: 已批准，待实施  
**范围**: Phase A–D — 元数据编辑、标签管理、搜索增强、封面管理、存储扫描恢复

## 1. 设计原则

- **个人库优先**：几百本规模、单用户、阅读体验优先，不过度引入后台系统或权限体系。
- **管理内嵌于 Library**：编辑入口从 `ComicDetailPage` 进入 `ComicEditPage`，不是独立 Dashboard。
- **独立资源子路径**：元数据、标签、封面使用 `/api/comics/{id}/metadata`、`/api/comics/{id}/tags`、`/api/comics/{id}/cover`，避免 `PUT /api/comics/{id}` 语义膨胀。
- **Tag 即分类**：不维护 `category` 字段，由 tag 系统承担分类职责。`comic.category` 字段遗留，现有列表页/搜索的 `category` 筛选保持可用但不再增强，未来废弃或批量迁移为 tag。
- **不扩展数据模型前先验证链路**：A1 只做最小可用，验证编辑闭环后再逐步加字段。
- **不引入 Elasticsearch**：搜索增强基于 MyBatis Plus 在 MySQL 上实现。

## 2. 总体路线图

```
Phase A
├── A0 基础检查（确认 tag/comic_tag/category/cover_path 现状）
├── A1 漫画元数据编辑（title / author）
└── A2 手动标签管理（tag CRUD + comic_tag 绑定，第一版无 type）

Phase B
└── 搜索增强（多标签 AND/OR + keyword 范围扩展）

Phase C
└── 封面管理（从已有 page 选择封面，预留 crop 扩展）

Phase D
├── A3 扩展元数据（titleJpn / description）
└── 存储扫描恢复（从 HQ 目录重建记录，占位 comic 不进入 Library）
```

## 3. A0 基础检查

在正式实施前，先确认当前数据库、实体、Mapper、Service、前端 store 的现状，避免重复建设和假设错误。

### 3.1 数据库检查

确认以下表/字段存在：

- `tag`（id, name[, type]）
- `comic_tag`（comic_id, tag_id）
- `comic.category`
- `comic.cover_path`

### 3.2 后端检查

确认以下类是否存在：

- `Tag` 实体、`TagMapper`
- `ComicTag` 实体、`ComicTagMapper`
- `ComicService` / `ComicServiceImpl`
- `ComicController`
- `BusinessException` 或同类异常

### 3.3 前端检查

确认：

- `tagApi` 是否已存在（当前 `api.ts` 已有 `tagApi.list()`）
- `ComicDetailPage` 是否已展示 tags
- 路由结构是否支持新增 `/comics/:id/edit`

### 3.4 输出

输出一份简短的状态文档，记录：

- 哪些已有，哪些需要新建
- 发现的任何不一致（例如 tag 表有 type 字段但设计第一版不使用）
- 是否需要数据库迁移脚本

## 4. A1 漫画元数据编辑

### 4.1 范围

**包含：**
- 后端新增 `GET /api/comics/{id}/metadata` 和 `PUT /api/comics/{id}/metadata`。
- 前端新增 `/comics/:id/edit` 路由与 `ComicEditPage.vue`。
- 在 `ComicDetailPage` 增加"编辑信息"入口按钮。
- 前端类型与服务层扩展。

**不包含：**
- 标签管理（A2）。
- `description`、`titleJpn` 等新增字段（A3）。
- 封面上传/选择（Phase C）。
- 权限/角色/后台系统。

### 4.2 API

#### 读取元数据

```http
GET /api/comics/{id}/metadata
```

响应：

```json
{
  "title": "漫画标题",
  "author": "作者名"
}
```

DTO：

```java
@Data
public class ComicMetadataDTO {
    private String title;
    private String author;
}
```

#### 更新元数据

```http
PUT /api/comics/{id}/metadata
```

请求：

```json
{
  "title": "新的标题",
  "author": "新的作者"
}
```

DTO：

```java
@Data
public class ComicMetadataUpdateDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    @Size(max = 128, message = "作者长度不能超过128")
    private String author;
}
```

校验：
- `title` 必填，最大 255。
- `author` 可选，最大 128。

### 4.3 前端

新增路由：

```ts
{
  path: '/comics/:id/edit',
  name: 'ComicEdit',
  component: () => import('@/pages/ComicEditPage.vue')
}
```

`ComicDetailPage` 增加"编辑信息"按钮，跳转编辑页。

`ComicEditPage` 第一版只包含标题、作者两个字段。

类型扩展：

```ts
export interface ComicMetadataDTO {
  title: string
  author?: string
}

export interface ComicMetadataUpdateDTO {
  title: string
  author?: string
}
```

### 4.4 测试

- 后端：`ComicMetadataControllerTest`、`ComicServiceTest`。
- 前端 Playwright：从详情页进入编辑页，修改标题和作者，保存后返回详情页断言更新。

---

## 5. A2 手动标签管理

### 5.1 数据模型

复用现有表：

```sql
tag
  id
  name        -- 唯一
  # type 字段第一版不使用，保持现有结构但业务层忽略

comic_tag
  comic_id
  tag_id
```

**第一版不涉及 `type`**。虽然 `tag` 表可能已有 `type` 字段，但业务层不暴露、不校验、不依赖，降低 UI 复杂度。

### 5.2 API

#### 标签列表

```http
GET /api/tags
```

响应：

```json
[
  { "id": 1, "name": "恋爱" },
  { "id": 2, "name": "校园" }
]
```

#### 创建标签

```http
POST /api/tags
```

请求：

```json
{ "name": "青梅竹马" }
```

约束：
- `name` 唯一，按字符串相等判断（大小写敏感由产品决定，第一版建议统一按原样存储并比较）。
- 重复创建返回 `409`。

#### 删除标签

```http
DELETE /api/tags/{id}
```

约束：
- 如果标签已绑定漫画，禁止删除，返回 `409`。

#### 读取漫画标签

```http
GET /api/comics/{id}/tags
```

响应：

```json
[1, 2, 3]
```

#### 更新漫画标签

```http
PUT /api/comics/{id}/tags
```

请求：

```json
{ "tagIds": [1, 2, 3] }
```

行为：
- 全量覆盖，事务内删除旧绑定并插入新绑定。
- 不存在的 tag id 返回 `400`。

### 5.3 前端

在 `ComicEditPage` 增加标签区块：

```
标签
[恋爱 x] [校园 x] [+ 添加标签]
```

交互：
- 点击添加弹出搜索/创建框。
- 可搜索已有标签。
- 可输入新标签名，回车后自动创建并绑定。
- 点击 × 移除绑定。

全局标签管理（可选，第一版可不做）：
- Dashboard 增加"标签管理"入口，可删除未使用标签。

### 5.4 测试

- 后端：标签 CRUD、绑定覆盖、重复/删除冲突。
- 前端 Playwright：在编辑页添加、移除标签，保存后详情页展示正确。

---

## 6. A3 扩展元数据

### 6.1 数据库变更

```sql
ALTER TABLE comic
  ADD COLUMN description TEXT,
  ADD COLUMN title_jpn VARCHAR(255);
```

### 6.2 API

扩展 A1 的 metadata 端点：

```http
PUT /api/comics/{id}/metadata
```

请求：

```json
{
  "title": "...",
  "author": "...",
  "titleJpn": "...",
  "description": "..."
}
```

```http
GET /api/comics/{id}/metadata
```

响应包含 `titleJpn` 和 `description`。

DTO 校验：

| 字段 | 规则 |
|------|------|
| title | 必填，最大 255 |
| author | 最大 128 |
| titleJpn | 最大 255 |
| description | 最大 2000 |

### 6.3 前端

`ComicEditPage` 增加：
- `titleJpn` 单行输入。
- `description` textarea，限制 2000 字符。

`ComicDetailPage` 增加 description 展示区域（可折叠）。

---

## 7. B 搜索增强

### 7.1 范围

不引入 Elasticsearch，基于 MyBatis Plus 扩展。

### 7.2 API

#### 多标签搜索

```http
GET /api/comics?tags=恋爱,校园&tagMode=AND
```

参数：
- `tags`：逗号分隔的标签名。
- `tagMode`：`AND`（默认）或 `OR`。

行为：
- `AND`：漫画同时包含所有指定标签。
- `OR`：漫画包含任意一个指定标签。

实现建议：
- `AND`：使用子查询过滤 comic id，再在外层保持原有 comic 分页查询：
  ```sql
  WHERE comic.id IN (
    SELECT comic_id
    FROM comic_tag
    JOIN tag ON comic_tag.tag_id = tag.id
    WHERE tag.name IN (...)
    GROUP BY comic_id
    HAVING COUNT(DISTINCT tag.id) = N
  )
  ```
- `OR`：使用 `EXISTS` 子查询。
- 避免 `LEFT JOIN ... GROUP BY ... HAVING`，防止分页 count 异常。

#### Keyword 范围扩展

当前 `keyword` 只匹配 `title`。增强后匹配：

| 字段 | 匹配方式 |
|------|----------|
| title | LIKE |
| author | LIKE |
| description | LIKE（A3 后生效） |
| tag.name | 关联匹配 |

使用 SQL `OR` 拼接。

### 7.3 前端

`ComicListPage` 增加：
- 标签多选器（基于 A2 的 tag 列表）。
- `tagMode` 切换：全部满足 / 任意满足。
- 搜索提示："搜索标题、作者、标签、简介"。

---

## 8. C 封面管理

### 8.1 范围

第一版只支持"从已有 page 中选择封面"，不上传新图片。

### 8.2 API

```http
PUT /api/comics/{id}/cover
```

请求：

```json
{
  "pageId": 123
}
```

**预留扩展**：未来可加入 `crop` 字段用于裁切封面区域，第一版不实现但 DTO 设计应避免阻止扩展。

行为：
- 查询 page 的 `hq_root` + `hq_path`。
- 更新 `comic.cover_path`。
- 可选：同步生成缩略图到 `/files/thumbs/{comicId}/cover.jpg`。
- 返回更新后的封面 URL。
- 封面候选页加载策略：按 chapter.global_order 和 page.page_number 排序分页加载，避免大漫画一次性加载过多缩略图。

### 8.3 前端

`ComicEditPage` 增加"封面"区块：

```
封面
[当前封面缩略图]
[更换封面]
```

点击后弹窗展示该漫画所有 page 缩略图网格，选择后关闭弹窗，保存时提交。

---

## 9. D 存储扫描恢复

### 9.1 范围

从 `D:/manga/hq/` 扫描目录结构，重建或补充数据库记录。属于灾难恢复/维护功能，不是日常管理。

### 9.2 API

```http
POST /api/admin/storage/scan-recover
```

请求：

```json
{ "dryRun": false }
```

行为：
- 扫描 `storage.roots.HQ.path` 下的 `{comicId}/{chapterId}/*.jpg`。
- 已存在的 comic：检查并补充缺失的 chapter/page。
- 不存在的 comic：
  - 目录下有 `metadata.json`：创建完整 comic + catalog/chapter/page，状态 `READY`。
  - 没有 `metadata.json`：创建**占位记录**，状态 `PLACEHOLDER`，不进入普通 Library 列表；生成 `storage_recovery_task` 或类似记录，等待人工确认/补全元数据。
- 返回统计：

```json
{
  "scannedComics": 10,
  "createdComics": 2,
  "updatedComics": 8,
  "createdChapters": 5,
  "createdPages": 120,
  "placeholders": 1,
  "skipped": 0
}
```

**约束**：
- `PLACEHOLDER` 状态的 comic 不应出现在 `/api/comics` 普通列表和搜索中。
- 必须提供明确的 UI 入口（如 Dashboard "待确认恢复项"）人工处理占位记录。

### 9.3 前端

Dashboard 增加"存储扫描恢复"入口：
- 说明文字。
- "开始扫描"按钮。
- 显示进度和结果统计。
- 显示占位记录列表，支持跳转补全元数据或删除。

---

## 10. 决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 编辑入口 | A. 详情页内联编辑 / B. 独立 Dashboard / C. 独立编辑页 | C | 职责清晰，避免 ComicDetailPage 膨胀，又保持短操作路径 |
| 可编辑字段（A1） | A. title/author / B. 加 category / C. 加 description | A | A1 验证编辑链路，不扩展数据模型 |
| category 处理 | A. 保留编辑 / B. 不再维护 / C. 删除字段 | B | tag 已足够承担分类职责，category 不再维护，未来废弃或迁移 |
| tag.type 第一版 | A. 暴露编辑 / B. 忽略 | B | 降低 UI 复杂度，type 可后续按需引入 |
| 标签绑定方式 | A. 增量 PATCH / B. 全量 PUT | B | 全量覆盖更简单，避免增量语义歧义 |
| 封面来源 | A. 上传 / B. 选择已有 page / C. 自动生成 | B | 第一版最小可用，上传可在后续版本加入 |
| 搜索引擎 | A. Elasticsearch / B. MyBatis Plus | B | 个人库规模 MySQL 足够，避免引入新组件 |
| 扫描恢复未知目录 | A. 创建占位 comic / B. 创建真实 comic / C. 只生成任务记录 | A+C | 创建 `PLACEHOLDER` 状态占位 comic 并生成恢复任务，不进入 Library |

---

## 11. 风险与注意事项

1. **标签删除冲突**：标签有绑定时禁止删除，避免数据不一致。
2. **category 遗留**：`comic.category` 字段不再编辑，但现有数据保留。未来 A3 后是否需要批量迁移为 tag，需单独评估。
3. **封面缩略图生成**：如果同步生成，需考虑大漫画的封面选择性能；可异步处理。
4. **存储扫描恢复**：操作可能耗时较长，后续可考虑改为异步任务 + 进度查询。
5. **搜索 keyword 扩展**：description 字段在 A3 后才生效，B 阶段实现时需兼容字段不存在的情况。
6. **PLACEHOLDER comic 隔离**：必须确保占位记录不污染普通列表和搜索，避免用户误操作。

---

## 12. 后续可扩展项

- 封面上传（选择本地文件或 URL）以及封面裁切（crop）。
- 标签类型管理（genre/theme/parody 等预定义）。
- 批量编辑（多本漫画同时打标签）。
- 漫画评分、收藏、笔记。
- 异步存储扫描恢复任务。
