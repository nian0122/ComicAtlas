# ComicAtlas API 文档

**Base URL**: `http://localhost/api`

所有响应格式：
```json
{ "code": 0, "message": "success", "data": ... }
```

---

## 1. 漫画

### 列表
```
GET /api/comics?keyword=&tag=&status=&category=&sourceType=&sort=createdAt&page=1&size=20
```

| 参数 | 必填 | 说明 |
|------|------|------|
| keyword | 否 | 标题搜索 |
| tag | 否 | 标签名 |
| status | 否 | IMPORTING / READY / DELETING |
| category | 否 | 分类 |
| sourceType | 否 | ZIP / DIRECTORY / EHENTAI |
| sort | 否 | createdAt / updatedAt / title / pageCount |
| page | 否 | 页码，默认 1 |
| size | 否 | 每页条数，默认 20 |

### 详情
```
GET /api/comics/{id}
```

返回：`ComicDetailVO`（title, author, coverUrl, pageCount, sourceType, status, tags, chapters 等）

### 目录树
```
GET /api/comics/{id}/catalog
```

返回 `CatalogNode[]`：
```json
[{
  "id": 1,
  "title": "Vol.1",
  "children": [{ "id": 2, "title": "第一部", "children": [], "chapters": [...] }],
  "chapters": [{
    "id": 10, "chapterNo": "001", "title": "第1话",
    "globalOrder": 0, "pageCount": 24
  }]
}]
```

### 章节页面
```
GET /api/comics/{comicId}/chapters/{chapterId}/pages
```

返回：`ChapterPageVO`（chapterId, chapterNo, pages[], prevChapterId, nextChapterId）

### 删除
```
DELETE /api/comics/{id}
```

---

## 2. 阅读

### 章节详情（推荐）
```
GET /api/chapters/{id}
```

返回 `ReaderDTO`：
```json
{
  "chapterId": 10,
  "chapterTitle": "第1话",
  "pages": [{
    "id": 100, "pageNumber": 1,
    "hqUrl": "/files/hq/35/10/001.jpg",
    "lqUrl": "/files/lq/35/10/001.webp",
    "lqStatus": "NOT_GENERATED"
  }],
  "total": 24,
  "prevChapterId": null,
  "nextChapterId": 12
}
```

---

## 3. 阅读记录

```
GET    /api/history              # 列表
GET    /api/history/{comicId}    # 获取进度
PUT    /api/history/{comicId}    # 更新进度
```

PUT 请求体：
```json
{ "chapterId": 10, "pageNumber": 5 }
```

---

## 4. 导入

### 创建任务
```
POST /api/tasks/import
```
```json
{ "sourceType": "ZIP", "sourcePath": "D:/downloads/comic.zip" }
{ "sourceType": "DIRECTORY", "sourcePath": "D:/manga/temp/ComicA" }
```

### 任务列表
```
GET /api/tasks/import?page=1&size=20&status=
```

### 任务详情
```
GET /api/tasks/import/{id}
```

### 任务状态
```
GET /api/tasks/import/{id}/status
```

返回：`{ taskId, status, progress }`

### 取消 / 重试
```
POST /api/tasks/import/{id}/cancel
POST /api/tasks/import/{id}/retry
```

---

## 5. LQ 生成

### 触发整本漫画
```
POST /api/comics/{comicId}/lq
```

### 触发单个章节
```
POST /api/chapters/{chapterId}/lq
```

LQ 状态流转：`NOT_GENERATED` → `QUEUED` → `GENERATING` → `READY` / `FAILED`

---

## 6. 仪表盘

```
GET /api/dashboard/statistics
```

返回：comicCount, pageCount, tagCount, storageUsed, todayImported 等

---

## 7. 操作日志

```
GET /api/operations?module=&action=&businessId=&keyword=&page=1&size=20
```

---

## 8. 标签

```
GET /api/tags
```

---

## 存储说明

| URL 前缀 | 物理路径 | 过期 |
|----------|----------|------|
| `/files/hq/*` | `D:/manga/hq/` | 60d |
| `/files/lq/*` | `D:/manga/lq/` | 30d |
| `/files/thumbs/*` | `D:/manga/thumbs/` | 7d |

文件路径格式：`/files/hq/{comicId}/{chapterId}/{imageName}`
