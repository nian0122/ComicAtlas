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
返回：title, author, coverUrl, pageCount, sourceType, tags

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
GET /api/tags
```

---

## 9. 管理

```
POST /api/admin/rebuild              # metadata.json 恢复数据库
```

---

## 存储说明

| URL | 物理路径 | 缓存 |
|-----|---------|------|
| `/files/hq/*` | `D:/manga/hq/` | 60d |
| `/files/lq/*` | `D:/manga/lq/` | 30d |
| `/files/thumbs/*` | `D:/manga/thumbs/` | 7d |
