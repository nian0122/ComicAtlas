# ComicAtlas API 文档 v0.5

**Base URL**: `http://localhost/api`

所有响应格式：`{ "code": 0, "message": "success", "data": ... }`

---

## 1. 漫画

### 列表 & 搜索
```
GET /api/comics?keyword=&tag=&status=&category=&sourceType=&sort=createdAt&page=1&size=20
```

| 参数 | 说明 |
|------|------|
| keyword | 全文搜索：title / titleJpn / author / 标签 |
| tag | 精确标签名筛选 |
| status | IMPORTING / READY / DELETING |
| category | 分类 |
| sourceType | ZIP / DIRECTORY / EHENTAI |
| sort | createdAt / updatedAt / title / pageCount / lastReadTime |

### 详情
```
GET /api/comics/{id}
```
返回：title, author, coverUrl, pageCount, sourceType, tags, description

### 元数据编辑
```
GET  /api/comics/{id}/metadata
PUT  /api/comics/{id}/metadata
{ "title": "Title", "author": "Author", "description": "Description" }
```

### 标签绑定
```
GET /api/comics/{id}/tags
PUT /api/comics/{id}/tags
{ "tagIds": [1, 2, 3] }
```

### 封面
```
GET /api/comics/{id}/covers/candidates
PUT /api/comics/{id}/cover
{ "pageId": 123 }
```

### 目录树
```
GET /api/comics/{id}/catalog
```
返回 `CatalogNode[]`：
```json
[{ "id": 1, "title": "Vol.1", "children": [...], "chapters": [
  { "id": 10, "chapterNo": "001", "title": "第1话", "globalOrder": 0, "pageCount": 24 }
]}]
```

### 章节页面
```
GET /api/comics/{comicId}/chapters/{chapterId}/pages
```

### 删除
```
DELETE /api/comics/{id}
```

---

## 2. 阅读

### 章节详情
```
GET /api/chapters/{id}
```
```json
{
  "chapterId": 10, "chapterTitle": "第1话",
  "pages": [{ "pageNumber": 1, "hqUrl": "/files/hq/35/10/001.jpg", "lqUrl": "..." }],
  "total": 24, "prevChapterId": null, "nextChapterId": 12
}
```

---

## 3. 阅读记录

```
GET    /api/history              # 列表
GET    /api/history/{comicId}    # 获取进度
PUT    /api/history/{comicId}    # 更新进度 { chapterId, pageNumber }
```

---

## 4. 导入 & 任务中心

### 创建任务
```
POST /api/tasks/import
{ "sourceType": "ZIP", "sourcePath": "D:/downloads/comic.zip" }
{ "sourceType": "DIRECTORY", "sourcePath": "D:/manga/temp/ComicA" }
```

### 任务列表（Dashboard）
```
GET /api/tasks/import?page=1&size=50&status=
```

### 任务详情 / 状态
```
GET /api/tasks/import/{id}
GET /api/tasks/import/{id}/status
```

### 取消 / 重试
```
POST /api/tasks/import/{id}/cancel
POST /api/tasks/import/{id}/retry
```

---

## 5. LQ 生成（手动触发）

```
POST /api/comics/{comicId}/lq       # 整本
POST /api/chapters/{chapterId}/lq   # 单章
```

状态：NOT_GENERATED → QUEUED → GENERATING → READY / FAILED

---

## 6. 仪表盘

```
GET /api/dashboard/statistics
```

---

## 7. 操作日志

```
GET /api/operations?module=&action=&page=1&size=20
```

---

## 8. 标签

```
GET    /api/tags
POST   /api/tags        { "name": "tag-name" }
DELETE /api/tags/{id}
```

---

## 9. 管理

```
POST /api/admin/rebuild              # metadata.json 恢复数据库
POST /api/admin/storage/scan-recover # 扫描 HQ 目录并恢复/创建占位漫画
DELETE /api/admin/comics/{id}?mode=DATABASE_ONLY  # 仅删除数据库记录
```

### 存储扫描恢复

```
POST /api/admin/storage/scan-recover
```

扫描 `MANGA_ROOT/hq/` 下的 `{comicId}/{chapterId}/*.jpg` 目录结构：
- 若 comic 数据库记录已存在 → 计入 `existingComics`
- 若目录存在 `metadata/{comicId}.json` → 恢复为完整漫画，状态 `READY`
- 若无 metadata → 创建 `PLACEHOLDER` 漫画，状态 `PLACEHOLDER`，不参与普通列表

响应：
```json
{
  "scannedComics": 3,
  "existingComics": 1,
  "restoredComics": 1,
  "placeholderComics": 1,
  "restoredChapters": 2,
  "restoredPages": 20,
  "placeholders": ["漫画 999999"],
  "errors": []
}
```

---

## 存储说明

| URL | 物理路径 | 缓存 |
|-----|---------|------|
| `/files/hq/*` | `D:/manga/hq/` | 60d |
| `/files/lq/*` | `D:/manga/lq/` | 30d |
| `/files/thumbs/*` | `D:/manga/thumbs/` | 7d |
