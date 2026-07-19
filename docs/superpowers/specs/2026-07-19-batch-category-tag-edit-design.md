# ComicAtlas 漫画批量分类标签编辑设计

**日期**: 2026-07-19  
**状态**: 待实施  
**范围**: 批量设置分类 + 追加标签

## 1. 背景与目标

批量导入的漫画没有分类和标签，逐个点开编辑页设置效率极低。本设计的目标：

> 用户在漫画列表页勾选多本漫画 → 弹窗统一设置分类和标签 → 一次保存，批量生效。

## 2. 设计原则

- **最小后端改动**：新增 1 个批量端点，复用现有 `ComicServiceImpl` 的单条更新逻辑。
- **尽力而为**：部分漫画更新失败不影响其他漫画，返回 `succeeded` + `failed` 明细。
- **标签只追加**：批量操作不做全量替换，只向选中漫画追加标签，不删除已有标签。
- **与现有批量模式一致**：参考 `ImportController.batch` 的请求/响应格式。

## 3. 范围

### 包含

- 后端：新增 `POST /api/comics/batch/update` 批量更新端点。
- 前端：`ComicListPage.vue` 增加复选框多选 + 底部「批量编辑」按钮。
- 前端：新增 `BatchEditDialog.vue` 弹窗组件（分类下拉 + 标签多选）。
- 前端类型与服务层扩展。

### 不包含

- 批量修改标题/作者/描述。
- 覆盖式标签替换（全量替换模式）。
- 导入完成后自动弹窗。
- 批量删除标签。

## 4. API 设计

### 4.1 批量更新分类和标签

```http
POST /api/comics/batch/update
```

请求体：

```json
{
  "comicIds": [1, 2, 3, 5, 8],
  "categoryId": 5,
  "addTagIds": [10, 11, 15]
}
```

DTO：

```java
package com.comicatlas.api.comic.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BatchComicUpdateDTO {

    @NotEmpty(message = "漫画 ID 列表不能为空")
    @Size(max = 100, message = "单次最多更新 100 本漫画")
    private List<Long> comicIds;

    private Long categoryId;      // 可选，不传表示不修改分类

    private List<Long> addTagIds;  // 可选，不传表示不修改标签
}
```

响应体：

```json
{
  "code": 200,
  "message": "操作完成",
  "data": {
    "total": 5,
    "succeeded": 4,
    "failed": [
      {
        "comicId": 3,
        "title": "某本漫画",
        "reason": "漫画状态为 IMPORTING，无法编辑"
      }
    ]
  }
}
```

VO：

```java
package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchUpdateResultVO {
    private int total;
    private int succeeded;
    private List<FailedItem> failed;

    @Data
    public static class FailedItem {
        private Long comicId;
        private String title;
        private String reason;
    }
}
```

行为：

- 校验 `comicIds` 非空且 ≤ 100。
- 遍历 `comicIds`，逐条处理：
  - `categoryId` 非 null 时 → 调用已有 `updateMetadata()` 设置分类（不修改 title/author，只传 categoryId）。
  - `addTagIds` 非空时 → 查询现有标签 → 去重 → 插入不存在的关联。
- 单条失败时记录原因，继续处理下一条。
- 所有漫画处理完后返回汇总结果。
- `categoryId` 和 `addTagIds` 均为空时返回 400。

## 5. 后端实现

### 5.1 接口层

`ComicController` 新增方法：

```java
@PostMapping("/comics/batch/update")
public Result<BatchUpdateResultVO> batchUpdate(
        @Valid @RequestBody BatchComicUpdateDTO dto) {
    return Result.ok(comicService.batchUpdate(dto));
}
```

### 5.2 服务层

`ComicService` 接口新增：

```java
BatchUpdateResultVO batchUpdate(BatchComicUpdateDTO dto);
```

`ComicServiceImpl` 实现逻辑：

```java
public BatchUpdateResultVO batchUpdate(BatchComicUpdateDTO dto) {
    List<BatchUpdateResultVO.FailedItem> failed = new ArrayList<>();
    int succeeded = 0;

    for (Long comicId : dto.getComicIds()) {
        try {
            Comic comic = comicMapper.selectById(comicId);
            if (comic == null) {
                failed.add(new FailedItem(comicId, null, "漫画不存在"));
                continue;
            }
            if (!"READY".equals(comic.getStatus())) {
                failed.add(new FailedItem(comicId, comic.getTitle(), "漫画状态为 " + comic.getStatus() + "，无法编辑"));
                continue;
            }

            // 更新分类
            if (dto.getCategoryId() != null) {
                comic.setCategoryId(dto.getCategoryId());
                Category category = categoryMapper.selectById(dto.getCategoryId());
                if (category != null) {
                    comic.setCategory(category.getName());
                }
                comicMapper.updateById(comic);
            }

            // 追加标签
            if (dto.getAddTagIds() != null && !dto.getAddTagIds().isEmpty()) {
                // 查询已有标签 ID
                List<Long> existingTagIds = comicTagMapper.selectList(
                    new LambdaQueryWrapper<ComicTag>()
                        .eq(ComicTag::getComicId, comicId)
                ).stream().map(ComicTag::getTagId).toList();

                // 只插入不存在的
                for (Long tagId : dto.getAddTagIds()) {
                    if (!existingTagIds.contains(tagId)) {
                        ComicTag ct = new ComicTag();
                        ct.setComicId(comicId);
                        ct.setTagId(tagId);
                        comicTagMapper.insert(ct);
                    }
                }
            }

            succeeded++;
        } catch (Exception e) {
            log.error("批量更新漫画 {} 失败", comicId, e);
            failed.add(new FailedItem(comicId, null, "系统错误"));
        }
    }

    BatchUpdateResultVO result = new BatchUpdateResultVO();
    result.setTotal(dto.getComicIds().size());
    result.setSucceeded(succeeded);
    result.setFailed(failed);
    return result;
}
```

### 5.3 约束

| 规则 | 说明 |
|------|------|
| 单次最多 100 本 | `@Size(max = 100)` 校验 |
| 只允许 READY 状态 | IMPORTING/DELETING/DELETED 跳过并报告 |
| 分类设置覆盖 | 有 categoryId 则设置，无则不修改 |
| 标签追加去重 | 已有标签不重复插入 |
| 不修改 title/author | `batchUpdate` 只传 categoryId，不覆盖 title/author |

## 6. 前端设计

### 6.1 ComicListPage 复选框

在漫画列表每行添加复选框，参考 `ImportPage.vue` 的 `togglePath` 模式：

```vue
<template>
  <!-- 列表上方：全选 + 操作栏 -->
  <div class="batch-toolbar" v-if="selectedIds.length > 0">
    <el-checkbox
      v-model="selectAll"
      :indeterminate="isIndeterminate"
      @change="handleSelectAll"
    >
      全选 ({{ selectedIds.length }} / {{ list.length }})
    </el-checkbox>
    <el-button type="primary" @click="showBatchDialog = true">
      批量编辑
    </el-button>
  </div>

  <!-- 列表行 -->
  <div
    v-for="comic in list"
    :key="comic.id"
    class="comic-row"
  >
    <el-checkbox
      :model-value="selectedIds.includes(comic.id)"
      @change="() => toggleSelect(comic.id)"
    />
    <!-- 原有行内容：封面、标题、作者等 -->
  </div>

  <!-- 批量编辑弹窗 -->
  <BatchEditDialog
    v-model:visible="showBatchDialog"
    :comic-ids="selectedIds"
    @saved="onBatchSaved"
  />
</template>
```

```ts
const selectedIds = ref<number[]>([])
const showBatchDialog = ref(false)

const selectAll = computed(() =>
  list.value.length > 0 && selectedIds.value.length === list.value.length
)
const isIndeterminate = computed(() =>
  selectedIds.value.length > 0 && selectedIds.value.length < list.value.length
)

function toggleSelect(id: number) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(id)
}

function handleSelectAll(val: boolean) {
  selectedIds.value = val ? list.value.map(c => c.id) : []
}

function onBatchSaved() {
  selectedIds.value = []
  showBatchDialog.value = false
  comicStore.fetchList()  // 刷新列表
}
```

### 6.2 BatchEditDialog 弹窗

```vue
<template>
  <el-dialog
    v-model="visible"
    title="批量编辑"
    width="480px"
    :close-on-click-modal="false"
  >
    <el-form label-width="60px">
      <!-- 分类选择 -->
      <el-form-item label="分类">
        <el-select v-model="categoryId" placeholder="不修改" clearable>
          <el-option
            v-for="cat in categoryStore.list"
            :key="cat.id"
            :label="cat.name"
            :value="cat.id"
          />
        </el-select>
      </el-form-item>

      <!-- 标签多选 -->
      <el-form-item label="标签">
        <el-select
          v-model="addTagIds"
          multiple
          filterable
          placeholder="选择要追加的标签"
        >
          <el-option
            v-for="tag in tagStore.list"
            :key="tag.id"
            :label="tag.name"
            :value="tag.id"
          />
        </el-select>
      </el-form-item>
    </el-form>

    <p class="batch-hint">
      将为选中的 <strong>{{ comicIds.length }}</strong> 本漫画统一设置
    </p>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onConfirm">
        确认
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useCategoryStore } from '@/stores/management/category'
import { useTagStore } from '@/stores/tag-store'
import { comicApi } from '@/services/api'

const props = defineProps<{
  comicIds: number[]
}>()

const emit = defineEmits<{
  saved: []
}>()

const visible = defineModel<boolean>('visible', { default: false })

const categoryStore = useCategoryStore()
const tagStore = useTagStore()

const categoryId = ref<number | null>(null)
const addTagIds = ref<number[]>([])
const saving = ref(false)

async function onConfirm() {
  if (!categoryId.value && addTagIds.value.length === 0) {
    ElMessage.warning('请至少选择分类或标签')
    return
  }
  saving.value = true
  try {
    const result = await comicApi.batchUpdate({
      comicIds: props.comicIds,
      categoryId: categoryId.value,
      addTagIds: addTagIds.value,
    })
    if (result.failed.length > 0) {
      ElMessage.warning(
        `完成：${result.succeeded} 本成功，${result.failed.length} 本失败`
      )
    } else {
      ElMessage.success(`已为 ${result.succeeded} 本漫画更新`)
    }
    emit('saved')
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    saving.value = false
  }
}
</script>
```

### 6.3 API 服务扩展

```ts
// services/api.ts
export const comicApi = {
  // ... 现有方法 ...
  batchUpdate: (data: BatchComicUpdateDTO) =>
    api.post('/comics/batch/update', data),
}
```

### 6.4 类型扩展

```ts
// types/index.ts
export interface BatchComicUpdateDTO {
  comicIds: number[]
  categoryId?: number | null
  addTagIds?: number[]
}

export interface BatchUpdateResultVO {
  total: number
  succeeded: number
  failed: FailedItem[]
}

export interface FailedItem {
  comicId: number
  title: string | null
  reason: string
}
```

## 7. 数据流

```text
用户在 ComicListPage 勾选漫画
         ↓
点击「批量编辑」按钮（selectedIds.length > 0 时显示）
         ↓
弹出 BatchEditDialog
         ↓
用户选择分类（可选）+ 标签（可选）
         ↓
点击确认 → POST /api/comics/batch/update
         ↓
后端遍历 comicIds：
  ├─ 校验状态（只允许 READY）
  ├─ 有 categoryId → 更新 comic.categoryId + comic.category
  └─ 有 addTagIds → 去重后 INSERT comic_tag（不删已有）
         ↓
返回 { total, succeeded, failed[] }
         ↓
前端显示结果提示 → 清空选择 → 刷新列表
```

## 8. 错误处理

| 场景 | 行为 |
|------|------|
| comicIds 为空 | 后端返回 400 |
| comicIds > 100 | 后端返回 400 |
| categoryId 和 addTagIds 均为空 | 后端返回 400 |
| 漫画不存在 | 记录到 failed，继续处理下一条 |
| 漫画状态非 READY | 记录到 failed，继续处理下一条 |
| 部分失败 | 前端提示 "X 本成功，Y 本失败" |
| 全部失败 | 前端提示错误信息 |
| 网络错误 | axios 拦截器统一处理 |

## 9. 文件清单

### 后端新增/修改

| 文件 | 操作 | 说明 |
|------|------|------|
| `api-service/.../dto/BatchComicUpdateDTO.java` | 新增 | 批量更新请求 DTO |
| `api-service/.../dto/BatchUpdateResultVO.java` | 新增 | 批量更新响应 VO |
| `api-service/.../controller/ComicController.java` | 修改 | 新增 `POST /comics/batch/update` |
| `api-service/.../service/ComicService.java` | 修改 | 新增 `batchUpdate()` 接口 |
| `api-service/.../service/impl/ComicServiceImpl.java` | 修改 | 实现 `batchUpdate()` |

### 前端新增/修改

| 文件 | 操作 | 说明 |
|------|------|------|
| `frontend/src/views/management/ComicListPage.vue` | 修改 | 加复选框 + 全选 + 批量编辑按钮 |
| `frontend/src/views/management/BatchEditDialog.vue` | 新增 | 批量编辑弹窗组件 |
| `frontend/src/services/api.ts` | 修改 | 新增 `comicApi.batchUpdate()` |
| `frontend/src/types/index.ts` | 修改 | 新增 `BatchComicUpdateDTO`、`BatchUpdateResultVO` |

## 10. 决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 标签操作语义 | A. 追加 / B. 替换 / C. 两种都支持 | A | 用户场景是给无标签漫画加标签，追加足够；替换模式增加复杂度且场景少 |
| 失败处理 | A. 尽力而为 / B. 全部回滚 | A | 批量编辑目标是一起处理，部分失败不应阻止其他成功 |
| 多选入口 | A. 列表复选框 / B. Shift+Click | A | 与 ImportPage 模式一致，用户熟悉 |
| 后端实现 | A. 循环调用已有方法 / B. 批量 SQL | A | 复用现有校验逻辑，代码改动最小，100 本性能足够 |
| category 更新方式 | A. 覆盖 / B. 跳过已有分类 | A | 用户场景是给无分类漫画设置，覆盖合理；同时支持改分类 |

(End of file - total 335 lines)
