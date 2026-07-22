# 前端存储优化入口实施计划

**日期**: 2026-07-21
**设计文档**: [2026-07-21-storage-optimize-frontend-design.md](2026-07-21-storage-optimize-frontend-design.md)

---

## Phase 1: 后端 API（API Service）

### Step 1.1: 新建存储查询 DTO

**文件**: `api-service/src/main/java/com/comicatlas/api/storage/dto/ComicStorageDTO.java`

```java
@Data
public class ComicStorageDTO {
    private Long comicId;
    private String title;
    private String coverUrl;
    private Long totalSize;
    private Long hqSize;
    private Long lqSize;
    private String hqStatus;   // READY / DELETED / MIXED
    private String lqStatus;   // READY / NOT_GENERATED / MIXED
    private Integer chapterCount;
    private Integer pageCount;
}
```

**文件**: `api-service/src/main/java/com/comicatlas/api/storage/dto/ChapterStorageDTO.java`

```java
@Data
public class ChapterStorageDTO {
    private Long chapterId;
    private String chapterNo;
    private String title;
    private Integer pageCount;
    private Long hqSize;
    private Long lqSize;
    private String hqStatus;
    private String lqStatus;
}
```

**文件**: `api-service/src/main/java/com/comicatlas/api/storage/dto/ComicStorageQuery.java`

```java
@Data
public class ComicStorageQuery {
    private String hqStatus;   // ALL / HAS_HQ / NO_HQ
    private String lqStatus;   // ALL / NEEDS_LQ / READY
    private String sort;       // totalSize / hqSize / lqSize / title
    private String order;      // asc / desc
    private String keyword;
}
```

### Step 1.2: MediaMapper 新增查询方法

**文件**: `api-service/.../mapper/MediaMapper.java`

```java
@Select("""
    SELECT 
        c.id as comic_id,
        c.title,
        c.cover_url,
        COUNT(DISTINCT ch.id) as chapter_count,
        COUNT(m.id) as page_count,
        SUM(CASE WHEN m.media_type = 'IMAGE' AND m.hq_status = 'READY' THEN m.file_size ELSE 0 END) as hq_size,
        SUM(CASE WHEN m.media_type = 'IMAGE' AND m.lq_status = 'READY' THEN m.lq_file_size ELSE 0 END) as lq_size,
        GROUP_CONCAT(DISTINCT m.hq_status) as hq_statuses,
        GROUP_CONCAT(DISTINCT m.lq_status) as lq_statuses
    FROM comic c
    LEFT JOIN chapter ch ON ch.comic_id = c.id
    LEFT JOIN media m ON m.chapter_id = ch.id AND m.media_type = 'IMAGE'
    WHERE c.deleted = 0
    <if test='keyword != null'>AND c.title LIKE CONCAT('%', #{keyword}, '%')</if>
    GROUP BY c.id
    <choose>
        <when test='sort == "hqSize"'>ORDER BY hq_size</when>
        <when test='sort == "lqSize"'>ORDER BY lq_size</when>
        <when test='sort == "title"'>ORDER BY c.title</when>
        <otherwise>ORDER BY (hq_size + lq_size)</otherwise>
    </choose>
    <if test='order == "desc"'>DESC</if>
    LIMIT #{offset}, #{size}
    """)
List<ComicStorageDTO> selectComicStorageList(ComicStorageQuery query, int offset, int size);

@Select("""
    SELECT COUNT(DISTINCT c.id)
    FROM comic c
    LEFT JOIN chapter ch ON ch.comic_id = c.id
    LEFT JOIN media m ON m.chapter_id = ch.id AND m.media_type = 'IMAGE'
    WHERE c.deleted = 0
    <if test='keyword != null'>AND c.title LIKE CONCAT('%', #{keyword}, '%')</if>
    """)
long countComicStorageList(ComicStorageQuery query);

@Select("""
    SELECT 
        ch.id as chapter_id,
        ch.chapter_no,
        ch.title,
        COUNT(m.id) as page_count,
        SUM(CASE WHEN m.media_type = 'IMAGE' AND m.hq_status = 'READY' THEN m.file_size ELSE 0 END) as hq_size,
        SUM(CASE WHEN m.media_type = 'IMAGE' AND m.lq_status = 'READY' THEN m.lq_file_size ELSE 0 END) as lq_size,
        GROUP_CONCAT(DISTINCT m.hq_status) as hq_statuses,
        GROUP_CONCAT(DISTINCT m.lq_status) as lq_statuses
    FROM chapter ch
    LEFT JOIN media m ON m.chapter_id = ch.id AND m.media_type = 'IMAGE'
    WHERE ch.comic_id = #{comicId}
    GROUP BY ch.id
    ORDER BY ch.sort_order
    """)
List<ChapterStorageDTO> selectChapterStorageList(Long comicId);
```

> 注：如果 DB schema 中没有 `lq_file_size` 字段，需要加它，或者先用 `file_size` 代替（LQ 大小估算）。

### Step 1.3: 状态聚合工具类

**文件**: `api-service/.../storage/StatusAggregator.java`

```java
public class StatusAggregator {
    public static String aggregateHqStatus(Set<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return "DELETED";
        if (statuses.size() == 1) return statuses.iterator().next();
        return "MIXED";
    }
    
    public static String aggregateLqStatus(Set<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return "NOT_GENERATED";
        if (statuses.size() == 1) return statuses.iterator().next();
        return "MIXED";
    }
}
```

### Step 1.4: StorageQueryService

**文件**: `api-service/.../storage/service/StorageQueryService.java`

```java
@Service
@RequiredArgsConstructor
public class StorageQueryService {
    private final MediaMapper mediaMapper;
    
    public PageResult<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size) {
        // 先查出基础数据
        List<ComicStorageDTO> list = mediaMapper.selectComicStorageList(query, (page - 1) * size, size);
        long total = mediaMapper.countComicStorageList(query);
        
        // 聚合状态
        for (ComicStorageDTO dto : list) {
            dto.setHqStatus(aggregateHqStatus(dto.getHqStatuses()));
            dto.setLqStatus(aggregateLqStatus(dto.getLqStatuses()));
            dto.setTotalSize((dto.getHqSize() != null ? dto.getHqSize() : 0) 
                           + (dto.getLqSize() != null ? dto.getLqSize() : 0));
        }
        
        // 应用筛选（HAS_HQ / NO_HQ / NEEDS_LQ）
        // 如果 DB 层筛选复杂，可以内存过滤，但最好 DB 层搞定
        
        return new PageResult<>(list, total, page, size);
    }
    
    public List<ChapterStorageDTO> listChapters(Long comicId) {
        List<ChapterStorageDTO> list = mediaMapper.selectChapterStorageList(comicId);
        for (ChapterStorageDTO dto : list) {
            dto.setHqStatus(aggregateHqStatus(dto.getHqStatuses()));
            dto.setLqStatus(aggregateLqStatus(dto.getLqStatuses()));
        }
        return list;
    }
}
```

### Step 1.5: AdminStorageController

**文件**: `api-service/.../storage/controller/AdminStorageController.java`

```java
@RestController
@RequestMapping("/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {
    private final StorageQueryService storageQueryService;
    
    @GetMapping("/comics")
    public Result<PageResult<ComicStorageDTO>> listComics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            ComicStorageQuery query) {
        return Result.ok(storageQueryService.listComics(query, page, size));
    }
    
    @GetMapping("/comics/{comicId}/chapters")
    public Result<List<ChapterStorageDTO>> listChapters(@PathVariable Long comicId) {
        return Result.ok(storageQueryService.listChapters(comicId));
    }
}
```

---

## Phase 2: 前端 API 封装

### Step 2.1: api.ts 新增方法

**文件**: `frontend/src/services/api.ts`

```typescript
export const adminApi = {
  // ... 已有方法
  
  // 新增
  storageComics: (params: {
    page?: number
    size?: number
    hqStatus?: 'ALL' | 'HAS_HQ' | 'NO_HQ'
    lqStatus?: 'ALL' | 'NEEDS_LQ' | 'READY'
    sort?: 'totalSize' | 'hqSize' | 'lqSize' | 'title'
    order?: 'asc' | 'desc'
    keyword?: string
  }) => api.get('/admin/storage/comics', { params }),
  
  storageChapters: (comicId: number) => 
    api.get(`/admin/storage/comics/${comicId}/chapters`),
}
```

---

## Phase 3: 前端 StoragePage 改造

### Step 3.1: 移除旧输入框区域

删除现有 `comicIdToDelete` 输入框和 `onDeleteHq` 方法（保留在文件中但标记为废弃，或完全删除）。

### Step 3.2: 新增数据结构与状态

```typescript
// 在 StoragePage.vue script setup 中新增
interface ComicStorageItem {
  comicId: number
  title: string
  coverUrl: string
  totalSize: number
  hqSize: number
  lqSize: number
  hqStatus: 'READY' | 'DELETED' | 'MIXED'
  lqStatus: 'READY' | 'NOT_GENERATED' | 'MIXED'
  chapterCount: number
  pageCount: number
}

interface ChapterStorageItem {
  chapterId: number
  chapterNo: string
  title: string
  pageCount: number
  hqSize: number
  lqSize: number
  hqStatus: 'READY' | 'DELETED' | 'MIXED'
  lqStatus: 'READY' | 'NOT_GENERATED' | 'MIXED'
}

const comicList = ref<ComicStorageItem[]>([])
const loading = ref(false)
const selectedComicIds = ref<number[]>([])

const filters = reactive({
  hqStatus: 'ALL' as 'ALL' | 'HAS_HQ' | 'NO_HQ',
  lqStatus: 'ALL' as 'ALL' | 'NEEDS_LQ' | 'READY',
  sort: 'hqSize' as 'totalSize' | 'hqSize' | 'lqSize' | 'title',
  order: 'desc' as 'asc' | 'desc',
  keyword: '',
})

const pagination = reactive({
  page: 1,
  size: 20,
  total: 0,
})

// Drawer 状态
const drawerVisible = ref(false)
const drawerComicId = ref<number | null>(null)
const drawerComicTitle = ref('')
const drawerChapters = ref<ChapterStorageItem[]>([])
const drawerLoading = ref(false)
const drawerSelectedIds = ref<number[]>([])
```

### Step 3.3: 新增方法

```typescript
async function loadComicList() {
  loading.value = true
  try {
    const res = await adminApi.storageComics({
      page: pagination.page,
      size: pagination.size,
      ...filters,
    })
    const data = res.data as { records: ComicStorageItem[]; total: number }
    comicList.value = data.records
    pagination.total = data.total
  } catch {
    ElMessage.error('加载存储列表失败')
  } finally {
    loading.value = false
  }
}

async function openDrawer(comicId: number) {
  const comic = comicList.value.find(c => c.comicId === comicId)
  drawerComicTitle.value = comic?.title || ''
  drawerComicId.value = comicId
  drawerVisible.value = true
  drawerLoading.value = true
  try {
    const res = await adminApi.storageChapters(comicId)
    drawerChapters.value = res.data as ChapterStorageItem[]
  } catch {
    ElMessage.error('加载章节列表失败')
  } finally {
    drawerLoading.value = false
  }
}

async function batchDeleteHq() {
  if (selectedComicIds.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      `确认删除 ${selectedComicIds.value.length} 部漫画的 HQ 原图？`,
      '批量删除 HQ',
      { type: 'warning' }
    )
  } catch { return }
  
  const results = { success: 0, failed: 0, errors: [] as string[] }
  for (let i = 0; i < selectedComicIds.value.length; i++) {
    const id = selectedComicIds.value[i]
    try {
      await hqApi.deleteComic(id)
      results.success++
    } catch (err: any) {
      results.failed++
      const comic = comicList.value.find(c => c.comicId === id)
      const name = comic?.title || `ID:${id}`
      if (err.response?.status === 409) {
        results.errors.push(`${name}: LQ 未就绪`)
      } else {
        results.errors.push(`${name}: ${err.response?.data?.message || '删除失败'}`)
      }
    }
  }
  
  if (results.failed === 0) {
    ElMessage.success(`${results.success} 部漫画 HQ 删除任务已提交`)
  } else {
    ElMessage.warning(`${results.success} 部成功，${results.failed} 部失败`)
    if (results.errors.length > 0) {
      console.error('批量删除 HQ 失败详情:', results.errors)
    }
  }
  await loadComicList()
}

async function batchGenerateLq() {
  // 与 batchDeleteHq 对称，调 lqApi.generateComic
}

async function deleteChapterHq(chapterId: number) {
  try {
    await ElMessageBox.confirm('确认删除本章 HQ？', '删除 HQ', { type: 'warning' })
    await hqApi.deleteChapter(chapterId)
    ElMessage.success('HQ 删除任务已提交')
    // 刷新抽屉
    if (drawerComicId.value) await openDrawer(drawerComicId.value)
  } catch (err: any) {
    if (err !== 'cancel') ElMessage.error(err.response?.data?.message || '删除失败')
  }
}

async function generateChapterLq(chapterId: number) {
  // 对称实现
}
```

### Step 3.4: 模板改造

```vue
<template>
  <div class="storage-page">
    <!-- 现有统计卡片保留 -->
    
    <!-- 现有操作区保留 -->
    
    <!-- 新增：存储优化区域 -->
    <section class="action-section">
      <h2 class="section-title">存储优化</h2>
      
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-select v-model="filters.hqStatus" placeholder="HQ 状态" @change="loadComicList">
          <el-option label="全部" value="ALL" />
          <el-option label="还有 HQ" value="HAS_HQ" />
          <el-option label="HQ 已删" value="NO_HQ" />
        </el-select>
        <el-select v-model="filters.lqStatus" placeholder="LQ 状态" @change="loadComicList">
          <el-option label="全部" value="ALL" />
          <el-option label="需要生成" value="NEEDS_LQ" />
          <el-option label="LQ 就绪" value="READY" />
        </el-select>
        <el-select v-model="filters.sort" placeholder="排序" @change="loadComicList">
          <el-option label="HQ 大小" value="hqSize" />
          <el-option label="LQ 大小" value="lqSize" />
          <el-option label="总大小" value="totalSize" />
          <el-option label="标题" value="title" />
        </el-select>
        <el-select v-model="filters.order" style="width: 100px" @change="loadComicList">
          <el-option label="降序" value="desc" />
          <el-option label="升序" value="asc" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="搜索标题" clearable @keyup.enter="loadComicList" />
      </div>
      
      <!-- 表格 -->
      <el-table
        v-loading="loading"
        :data="comicList"
        @selection-change="handleSelectionChange"
        row-key="comicId"
      >
        <el-table-column type="selection" width="40" />
        <el-table-column label="封面" width="70">
          <template #default="{ row }">
            <img :src="row.coverUrl" class="cover-thumb" loading="lazy" />
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="150" />
        <el-table-column label="HQ" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.hqSize) }}</template>
        </el-table-column>
        <el-table-column label="LQ" width="100" align="right">
          <template #default="{ row }">{{ formatSize(row.lqSize) }}</template>
        </el-table-column>
        <el-table-column label="HQ 状态" width="100">
          <template #default="{ row }">
            <el-tag :type="hqTagType(row.hqStatus)" size="small">{{ hqTagText(row.hqStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="LQ 状态" width="100">
          <template #default="{ row }">
            <el-tag :type="lqTagType(row.lqStatus)" size="small">{{ lqTagText(row.lqStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.hqStatus !== 'DELETED'" type="danger" text size="small" @click="deleteComicHq(row.comicId)">删HQ</el-button>
            <el-button v-if="row.lqStatus !== 'READY'" type="primary" text size="small" @click="generateComicLq(row.comicId)">生LQ</el-button>
            <el-button type="info" text size="small" @click="openDrawer(row.comicId)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      
      <!-- 批量操作栏 -->
      <div v-if="selectedComicIds.length > 0" class="batch-bar">
        <span>已选 {{ selectedComicIds.length }} 部</span>
        <el-button type="danger" @click="batchDeleteHq">删除选中 HQ</el-button>
        <el-button type="primary" @click="batchGenerateLq">生成选中 LQ</el-button>
      </div>
      
      <!-- 分页 -->
      <el-pagination
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.size"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50, 100]"
        @change="loadComicList"
      />
    </section>
    
    <!-- 抽屉 -->
    <el-drawer v-model="drawerVisible" :title="`${drawerComicTitle} — 存储详情`" size="500px" destroy-on-close>
      <!-- ... 见设计文档 ... -->
    </el-drawer>
  </div>
</template>
```

### Step 3.5: 样式补充

```css
.filter-bar {
  display: flex;
  gap: var(--space-sm);
  margin-bottom: var(--space-base);
  flex-wrap: wrap;
}

.cover-thumb {
  width: 48px;
  height: 64px;
  object-fit: cover;
  border-radius: var(--radius-sm);
}

.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--space-base);
  padding: var(--space-base) 0;
  border-top: 1px solid var(--border);
  margin-top: var(--space-base);
}

.drawer-stats {
  display: flex;
  gap: var(--space-lg);
  margin-bottom: var(--space-base);
  padding-bottom: var(--space-base);
  border-bottom: 1px solid var(--border);
}
```

---

## Phase 4: ComicListPage 最小关联

### Step 4.1: 新增导入与按钮

**文件**: `frontend/src/views/management/ComicListPage.vue`

```typescript
import { Coin } from '@element-plus/icons-vue'

function goStorage(comicId: number) {
  router.push({
    path: '/manage/storage',
    query: { highlight: comicId.toString() }
  })
}
```

在行内操作区增加：
```vue
<el-button type="info" text :icon="Coin" size="small" @click.stop="goStorage(comic.id)">存储</el-button>
```

---

## Phase 5: 验证

### Step 5.1: 编译检查
```bash
cd frontend && npx vue-tsc --noEmit
```

### Step 5.2: 后端编译
```bash
cd api-service && mvn compile
```

### Step 5.3: 功能验证清单

| 场景 | 预期 |
|------|------|
| 打开 StoragePage | 显示统计卡片 + 操作区 + 存储优化表格 |
| 表格加载 | 显示 loading，加载后显示漫画列表 |
| 筛选 "还有 HQ" | 只显示 HQ 状态为 READY 或 MIXED 的漫画 |
| 筛选 "需要生成 LQ" | 只显示 LQ 状态为 NOT_GENERATED 或 MIXED 的漫画 |
| 按 HQ 大小降序 | 占用最大的漫画在最前 |
| 勾选 3 部漫画 → 删除 HQ | 弹出确认框 → 提交后显示结果 → 刷新列表 |
| 点击"详情" | 右侧抽屉打开，显示章节列表 |
| 抽屉内勾选 2 章 → 删除 HQ | 提交后刷新抽屉 |
| ComicListPage 点击"存储" | 跳转到 StoragePage 并高亮对应行 |

---

## 并行执行建议

**Phase 1（后端）和 Phase 2-3（前端）可并行**：
- 后端开发 API + Mapper + Service + Controller
- 前端同步改造 StoragePage 模板和逻辑（用 mock 数据先跑通 UI）
- 后端 API 完成后联调

**预计工作量**：
- 后端：2-3 小时（主要是 SQL 和状态聚合逻辑）
- 前端：4-6 小时（StoragePage 大改 + 抽屉交互）
