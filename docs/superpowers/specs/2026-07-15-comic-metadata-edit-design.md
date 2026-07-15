# ComicAtlas 漫画元数据编辑设计（A1）

**日期**: 2026-07-15  
**状态**: 已批准，待实施  
**范围**: Phase A1 — 漫画元数据编辑

## 1. 背景与目标

ComicAtlas 当前已经具备稳定的导入、目录树、阅读和列表能力，但导入后的漫画元数据（标题、作者）无法修正。A1 的目标是建立第一个管理闭环：

> 用户发现数据错误 → 进入编辑页 → 修改 → 保存 → 返回详情页看到更新。

本设计刻意保持最小范围，不引入新的数据库字段，不加入分类/标签/封面/简介等扩展能力，只验证"编辑链路"是否成立。基于后续讨论，category 字段不再维护，由 tag 系统承担分类职责。

## 2. 设计原则

- **管理入口与阅读入口分离**：详情页（ComicDetailPage）保持只读，编辑跳转独立编辑页（ComicEditPage）。
- **独立资源子路径**：元数据、标签、封面使用 `/api/comics/{id}/metadata`、`/api/comics/{id}/tags`、`/api/comics/{id}/cover`，避免 `PUT /api/comics/{id}` 语义膨胀。
- **个人库优先**：几百本规模，不过度设计后台系统；编辑路径短，操作成本低。
- **不扩展数据模型**：第一版只编辑已有字段 `title / author`。category 不再维护，由 tag 系统承担分类职责。

## 3. 范围

### 包含
- 后端：新增 `GET /api/comics/{id}/metadata` 和 `PUT /api/comics/{id}/metadata`。
- 前端：新增 `/comics/:id/edit` 路由与 `ComicEditPage.vue`。
- 前端：在 `ComicDetailPage` 增加"编辑信息"入口按钮。
- 前端类型与服务层扩展。

### 不包含
- 标签管理（A2）。
- `description`、`titleJpn` 等新增字段（A3）。
- 封面上传/选择（Phase C）。
- 权限/角色/后台系统。

## 4. API 设计

### 4.1 读取漫画元数据

```http
GET /api/comics/{id}/metadata
```

响应体：

```json
{
  "title": "漫画标题",
  "author": "作者名"
}
```

DTO：

```java
package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class ComicMetadataDTO {
    private String title;
    private String author;
}
```

行为：
- 返回指定 `comic` 的 `title`、`author`。
- 漫画不存在时返回 `404`。

### 4.2 更新漫画元数据

```http
PUT /api/comics/{id}/metadata
```

请求体：

```json
{
  "title": "新的标题",
  "author": "新的作者"
}
```

DTO：

```java
package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ComicMetadataUpdateDTO {

    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255")
    private String title;

    @Size(max = 128, message = "作者长度不能超过128")
    private String author;
}
```

行为：
- 校验请求体；校验失败返回 `400`，`message` 中包含字段级错误信息（如 `标题不能为空` 或多字段错误拼接）。
- 更新 `comic` 表的 `title`、`author` 字段。
- `updated_at` 由 MyBatis Plus 自动更新。
- 漫画不存在时返回 `404`。
- 成功返回 `200` 与更新后的 `ComicMetadataDTO`。

## 5. 后端实现

### 5.1 接口层

`ComicController` 新增方法：

```java
@GetMapping("/comics/{id}/metadata")
public Result<ComicMetadataDTO> getMetadata(@PathVariable Long id) { ... }

@PutMapping("/comics/{id}/metadata")
public Result<ComicMetadataDTO> updateMetadata(
        @PathVariable Long id,
        @Valid @RequestBody ComicMetadataUpdateDTO dto) { ... }
```

### 5.2 服务层

`ComicService` 接口新增方法，由 `ComicServiceImpl` 实现：

```java
ComicMetadataDTO getMetadata(Long id);
ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateDTO dto);
```

`ComicServiceImpl` 更新逻辑：

```java
Comic comic = comicMapper.selectById(id);
if (comic == null) throw new BusinessException("漫画不存在");

comic.setTitle(dto.getTitle());
comic.setAuthor(dto.getAuthor());
comicMapper.updateById(comic);

return toMetadataDTO(comic);
```

### 5.3 校验规则

| 字段 | 规则 |
|------|------|
| title | 必填，最大 255 字符 |
| author | 可选，最大 128 字符 |

## 6. 前端设计

### 6.1 路由

```ts
// frontend/src/router/index.ts
{
  path: '/comics/:id/edit',
  name: 'ComicEdit',
  component: () => import('@/pages/ComicEditPage.vue'),
  meta: { title: '编辑漫画信息' }
}
```

### 6.2 ComicDetailPage 入口

在详情页合适位置（例如操作栏）增加：

```vue
<el-button @click="$router.push(`/comics/${comicId}/edit`)">
  编辑信息
</el-button>
```

### 6.3 ComicEditPage

页面结构：

```vue
<template>
  <div class="comic-edit-page">
    <h1>编辑漫画信息</h1>
    <el-form :model="form" :rules="rules" ref="formRef" v-loading="loading">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" />
      </el-form-item>

      <el-form-item label="作者" prop="author">
        <el-input v-model="form.author" />
      </el-form-item>

      <div class="actions">
        <el-button @click="onCancel">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="saving">保存</el-button>
      </div>
    </el-form>
  </div>
</template>
```

交互：
- 页面挂载时请求 `GET /api/comics/:id/metadata`。
- 用户点击保存时触发表单校验，通过后调用 `PUT /api/comics/:id/metadata`。
- 保存成功后使用 `router.push(`/comics/${id}`)` 返回详情页，详情页重新加载数据。
- 取消时返回详情页（`router.back()` 或 `router.push`）。

### 6.4 API 服务扩展

```ts
// services/api.ts
export const comicApi = {
  list: (params: any) => api.get('/comics', { params }),
  detail: (id: number) => api.get(`/comics/${id}`),
  delete: (id: number) => api.delete(`/comics/${id}`),
  getMetadata: (id: number) => api.get(`/comics/${id}/metadata`),
  updateMetadata: (id: number, data: ComicMetadataUpdateDTO) =>
    api.put(`/comics/${id}/metadata`, data),
}
```

### 6.5 类型扩展

```ts
// types/index.ts
export interface ComicMetadataDTO {
  title: string
  author?: string
}

export interface ComicMetadataUpdateDTO {
  title: string
  author?: string
}
```

## 7. 数据流

```text
用户点击"编辑信息"
        ↓
前端路由跳转 /comics/:id/edit
        ↓
ComicEditPage 挂载
        ↓
请求 GET /api/comics/:id/metadata
        ↓
渲染表单（title/author）
        ↓
用户修改并点击保存
        ↓
前端表单校验
        ↓
PUT /api/comics/:id/metadata
        ↓
后端 Bean Validation → 业务校验 → 更新 comic 表
        ↓
返回 200 与更新后的 ComicMetadataDTO
        ↓
前端 router.push(`/comics/:id`)
        ↓
ComicDetailPage 重新加载，展示新数据
```

## 8. 错误处理

| 场景 | HTTP 状态 | 响应 | 前端行为 |
|------|-----------|------|----------|
| 漫画不存在 | 404 | `Result.fail(404, "漫画不存在")` | 显示错误并返回列表 |
| title 为空 | 400 | 字段级校验错误 | 表单项高亮并提示 |
| title 超过 255 | 400 | 字段级校验错误 | 表单项高亮并提示 |
| author 超长 | 400 | 字段级校验错误 | 表单项高亮并提示 |
| 数据库更新失败 | 500 | `Result.fail(500, "更新失败")` | ElMessage 错误提示 |
| 网络错误 | — | axios 拦截器处理 | 统一错误提示 |

## 9. 边界与约束

- **不删除文件**：本功能只更新数据库，不涉及本地文件操作。
- **不更新阅读状态**：`reading_history` 等表不受影响。
- **不触发 MQ**：元数据更新不发送事件。
- **不更新 catalog/chapter/page**：本功能只修改 `comic` 表。
- **不维护 category**：A1 不再编辑 `category` 字段，该字段由 tag 系统承担分类职责，未来考虑废弃或迁移。

## 10. 测试

### 10.1 后端测试

- `ComicMetadataControllerTest`：
  - 正常读取元数据。
  - 读取不存在的漫画返回 404。
  - 正常更新元数据。
  - 更新不存在的漫画返回 404。
  - title 为空返回 400。
  - title 超过 255 返回 400。
- `ComicServiceTest`：
  - 更新成功并返回正确 DTO。

### 10.2 前端测试

- Playwright 端到端：
  - 从详情页点击"编辑信息"进入编辑页。
  - 修改标题和作者并保存。
  - 返回详情页断言数据已更新。

## 11. 后续扩展

A2 标签管理将复用本设计建立的模式：

```
GET /api/comics/{id}/tags
PUT /api/comics/{id}/tags
GET /api/tags
POST /api/tags
DELETE /api/tags/{id}
```

A3 扩展元数据将加入 `description`、`titleJpn` 等字段。

`comic.category` 字段不再维护，未来考虑废弃或批量迁移为 tag。

## 12. 决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 编辑入口 | A. 详情页内联编辑 / B. 独立 Dashboard / C. 独立编辑页 | C | 职责清晰，避免 ComicDetailPage 膨胀，又保持短操作路径 |
| 可编辑字段 | A. title/author / B. 加 category / C. 加 description | A | A1 目标是验证编辑链路，不扩展数据模型；category 由 tag 替代 |
| category 处理 | A. 保留编辑 / B. 不再维护 / C. 删除字段 | B | tag 已足够承担分类职责，category 不再维护，未来考虑废弃或迁移 |
| API 风格 | A. 复用详情接口 / B. 独立 metadata 端点 / C. PATCH | B | 边界清晰，与后续 tags/cover 子资源风格一致 |
