# ComicAtlas 管理功能 A0 基础检查报告

**日期**: 2026-07-15  
**检查范围**: tag/comic_tag/category/cover_path 现状，以及 ComicService/Controller、前端 API 与页面。

---

## 1. 数据库 / 实体层

| 项 | 状态 | 说明 |
|---|---|---|
| `tag` 表实体 | ✅ 存在 | `Tag` 类包含 `id / name / type` 三个字段 |
| `comic_tag` 表实体 | ✅ 存在 | `ComicTag` 类包含 `comicId / tagId` |
| `TagMapper` | ✅ 存在 | 仅继承 `BaseMapper<Tag>`，无自定义方法 |
| `ComicTagMapper` | ✅ 存在 | 仅继承 `BaseMapper<ComicTag>`，无自定义方法 |
| `comic.category` | ✅ 存在 | `Comic` 实体有 `category` 字段，当前被 `ComicServiceImpl` 和 `ComicListVO` 使用 |
| `comic.cover_path` | ✅ 存在 | `Comic` 实体有 `coverPath` 字段，但当前**未在代码中使用** |
| `comic.title_jpn` | ✅ 存在 | `Comic` 实体已有 `titleJpn` 字段，**A3 不需要新增该列** |
| `comic.description` | ❌ 不存在 | A3 需要新增 `description` 列 |

### 重要发现

1. **`titleJpn` 已存在**：A3 扩展元数据时只需新增 `description` 字段，无需改动 `titleJpn`。
2. **`coverPath` 存在但未被使用**：当前封面 URL 在 `ComicServiceImpl` 中硬编码为：
   ```java
   vo.setCoverUrl("/files/thumbs/" + c.getId() + "/cover.webp");
   ```
   C 阶段封面管理需要将该逻辑改为读取 `comic.coverPath`，并回退到默认封面。

---

## 2. 后端 Service / Controller

| 项 | 状态 | 说明 |
|---|---|---|
| `ComicService` 接口 | ✅ 存在 | 当前方法：`listComics / getComicDetail / getChapterPages / deleteComicAsync` |
| `ComicServiceImpl` | ✅ 存在 | 已注入 `TagMapper` 和 `ComicTagMapper`，并在 `getComicDetail` 中加载 tags |
| `ComicController` | ✅ 存在 | 当前端点：`GET /comics / GET /comics/{id} / DELETE /comics/{id} / GET /comics/{id}/chapters/{chapterId}/pages` |
| `GET /api/tags` 后端端点 | ⚠️ 缺失 | 前端 `tagApi.list()` 已调用 `/tags`，但后端无对应 Controller/Service |
| 元数据编辑端点 | ❌ 不存在 | 需要新增 `GET/PUT /api/comics/{id}/metadata` |
| 标签管理端点 | ❌ 不存在 | 需要新增 `GET/POST/DELETE /api/tags` 和 `GET/PUT /api/comics/{id}/tags` |
| 封面更新端点 | ❌ 不存在 | 需要新增 `PUT /api/comics/{id}/cover` |
| 扫描恢复端点 | ❌ 不存在 | 需要新增 `POST /api/admin/storage/scan-recover` |
| `BusinessException` | ✅ 存在 | 构造方法为 `BusinessException(int code, String message)` |

### 当前 `ComicServiceImpl.getComicDetail` 已加载 tags

```java
var comicTags = comicTagMapper.selectList(
    new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id));
if (!comicTags.isEmpty()) {
    var tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
    var tags = tagMapper.selectBatchIds(tagIds);
    vo.setTags(tags.stream().map(t -> {
        ComicDetailVO.TagRef tr = new ComicDetailVO.TagRef();
        tr.setName(t.getName());
        tr.setType(t.getType());
        return tr;
    }).collect(Collectors.toList()));
}
```

这意味着 A2 后端只需要新增 Tag CRUD 和 comic_tag 更新接口，详情页展示 tags 的逻辑已存在。

---

## 3. 前端

| 项 | 状态 | 说明 |
|---|---|---|
| `tagApi` | ⚠️ 部分存在 | `api.ts` 中已有 `tagApi = { list: () => api.get('/tags') }`，但缺少创建/删除 |
| `comicApi` | ⚠️ 部分存在 | 已有 `list / detail / delete`，缺少 `getMetadata / updateMetadata / updateTags / updateCover` |
| `ComicDetailPage` 展示 tags | ✅ 存在 | 第 85–87 行已展示 `comic.tags` |
| `ComicDetailPage` 编辑入口 | ❌ 不存在 | 需要新增"编辑信息"按钮 |
| `/comics/:id/edit` 路由 | ❌ 不存在 | 需要新增 |
| `ComicEditPage` | ❌ 不存在 | 需要新建 |
| `ComicMetadataDTO` 类型 | ❌ 不存在 | 需要新增 |
| `TagDTO` / `ComicTagUpdateDTO` 类型 | ❌ 不存在 | 需要新增 |

### 当前路由表

```ts
/comics/:id          -> ComicDetailPage
/comics/:id/read     -> ReaderPage
```

需要新增：

```ts
/comics/:id/edit     -> ComicEditPage
```

---

## 4. 不一致与需要修复的点

1. **前端 `tagApi.list()` 调用 `/tags`，后端无实现**：
   - 当前 ComicDetailPage 展示 tags 是通过 `/api/comics/{id}` 详情接口返回的。
   - 但 `tagApi.list()` 已存在，说明曾经计划做 `/api/tags`，但未完成。
   - A2 需要补齐 `GET /api/tags`。

2. **封面 URL 硬编码**：
   - `ComicServiceImpl.toListVO` 和 `getComicDetail` 都硬编码了 `/files/thumbs/{id}/cover.webp`。
   - C 阶段需要改为使用 `FileUrlResolver` 或基于 `coverPath` 解析。

3. **`category` 仍在多处使用**：
   - `ComicListQuery`、`ComicListVO`、`ComicDetailVO`、`ComicServiceImpl`、`Comic` 实体都有 `category`。
   - 设计决策是不再维护 category，但现有筛选和展示需要保留为遗留功能，不增强即可。

---

## 5. 数据库迁移需求

| Phase | 迁移内容 |
|---|---|
| A1 | 无需迁移 |
| A2 | 无需迁移（复用 tag / comic_tag） |
| A3 | `ALTER TABLE comic ADD COLUMN description TEXT;` |
| B | 无需迁移 |
| C | 无需迁移（coverPath 已存在） |
| D | 无需迁移（可能需要新增 `status` 枚举值 `PLACEHOLDER`，但 `status` 字段为 VARCHAR，无需 DDL） |

---

## 6. 结论

- **A1 可立即开始**：只需新增 DTO、Service 方法、Controller 端点、前端页面。
- **A2 可立即开始**：tag/comic_tag 表和 Mapper 已存在，详情页展示逻辑已存在，主要工作是补齐 CRUD 接口和前端标签选择器。
- **A3 需要一次小迁移**：仅新增 `description` 列（`titleJpn` 已存在）。
- **C 阶段需要处理封面 URL 硬编码问题**：当前 `coverPath` 字段完全未被使用。
- **D 阶段需要确认 `status` 字段是否支持 `PLACEHOLDER`**：`status` 为 VARCHAR，无需 DDL，但代码中需要添加状态判断。

无阻塞性风险，可以进入 Phase A1 实施。
