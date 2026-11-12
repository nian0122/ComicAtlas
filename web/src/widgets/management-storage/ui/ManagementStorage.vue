<script setup lang="ts">
import { AppSearch } from '@/shared/ui/search'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ManagementPagedFeed } from '@/shared/ui/pagination'
import {
  storageApi,
  operationBlockers,
  type Statistics,
  type StorageObject,
  type StorageQuery,
} from '@/entities/storage'
import { comicCoverUrl } from '@/entities/comic'
import { getApiErrorMessage } from '@/shared/api/http'
import { useSelection } from '@/shared/lib/selection'
import { ManagementIcon, ManagementActionMenu, ManagementImage, formatBytes, formatDate } from '@/shared/ui/management'
type Attention = NonNullable<StorageQuery['attention']>
type Sort = 'totalBytes' | 'hqBytes' | 'lqBytes' | 'title'
const props = defineProps<{ statistics: Statistics; writable: boolean; busy: boolean }>()
const emit = defineEmits<{
  recover: []
  audit: []
  refresh: []
  generate: [objects: StorageObject[]]
  'generate-single': [object: StorageObject]
  release: [kind: 'comics' | 'chapters', id: string, title: string]
  releaseBatch: [ids: string[]]
}>()
const records = ref<StorageObject[]>([]),
  current = ref<StorageObject | null>(null)
const selection = useSelection(records),
  selected = selection.selected,
  allSelected = selection.allSelected,
  partial = selection.partial
const chapters = ref<StorageObject[]>([]),
  chapterPage = ref(1),
  chapterTotal = ref(0)
const page = ref(1),
  loadedPage = ref(1),
  total = ref(0),
  keyword = ref(''),
  sort = ref<Sort>('totalBytes'),
  attention = ref<Attention | ''>(''),
  loading = ref(true),
  loadingMore = ref(false),
  hasLoaded = ref(false),
  listError = ref(''),
  appendError = ref(''),
  detailLoading = ref(false),
  error = ref('')
let generation = 0,
  loadedQuery = '',
  detailGeneration = 0,
  disposed = false
const inventory = computed(() => props.statistics.inventory)
const observation = computed(() => props.statistics.observation)
const scanning = computed(() => ['queued', 'running', 'cancelling'].includes(props.statistics.refresh?.status ?? ''))
const bytes = computed(() => inventory.value.hq.bytes + inventory.value.lq.bytes + inventory.value.covers.bytes)
const observed = computed(() => {
  const usage = observation.value?.usage
  return usage
    ? [
        { key: 'hq', name: '原件', usage: usage.hq },
        { key: 'lq', name: '轻量副本', usage: usage.lq },
        { key: 'covers', name: '漫画封面', usage: usage.covers },
        { key: 'metadata', name: '作品元数据', usage: usage.metadata ?? { files: 0, bytes: 0 } },
        { key: 'temp', name: '解压临时文件', usage: usage.temp },
        { key: 'staging', name: '任务暂存', usage: usage.staging },
        { key: 'trash', name: '回收文件', usage: usage.trash },
        { key: 'exports', name: '导出产物', usage: usage.exports },
        { key: 'retired', name: '保留旧版本', usage: usage.retired },
        { key: 'other', name: '其他', usage: usage.other },
      ]
    : []
})
const observedTotal = computed(() => observed.value.reduce((sum, item) => sum + item.usage.bytes, 0))
const observedMedia = computed(() => {
  const usage = observation.value?.usage
  return usage ? usage.hq.bytes + usage.lq.bytes + usage.covers.bytes : 0
})
const nonRegistered = computed(() =>
  observed.value.filter(
    (item) =>
      ['metadata', 'temp', 'staging', 'trash', 'exports', 'retired', 'other'].includes(item.key) &&
      item.usage.bytes > 0,
  ),
)
const nonRegisteredBytes = computed(() => nonRegistered.value.reduce((sum, item) => sum + item.usage.bytes, 0))
const actualSegments = computed(() => {
  const usage = observation.value?.usage
  if (!usage) return []
  return [
    { key: 'hq', name: '原件', bytes: usage.hq.bytes },
    { key: 'lq', name: '轻量副本', bytes: usage.lq.bytes },
    { key: 'covers', name: '漫画封面', bytes: usage.covers.bytes },
    { key: 'other', name: '非登记', bytes: nonRegisteredBytes.value },
  ].filter((segment) => segment.bytes > 0)
})
const actualBarLabel = computed(
  () =>
    actualSegments.value.map((segment) => `${segment.name} ${formatBytes(segment.bytes)}`).join('，') || '媒体卷为空',
)
const delta = computed(() => observedMedia.value - bytes.value)
const deltaClass = computed(() => (delta.value > 0 ? 'is-positive' : delta.value < 0 ? 'is-negative' : ''))
const deltaHint = computed(() =>
  delta.value > 0
    ? '实际盘上比登记多，可能含未登记或残留文件'
    : delta.value < 0
      ? '登记比实际盘上多，可能有缺失文件'
      : '与登记一致',
)
const refreshStatusLabel = computed(() => {
  const status = props.statistics.refresh?.status ?? ''
  return (
    (
      {
        queued: '排队中',
        running: '进行中',
        succeeded: '成功',
        failed: '失败',
        cancelling: '取消中',
        cancelled: '已取消',
      } as Record<string, string>
    )[status] ?? status
  )
})
const freePercent = computed(() => {
  const device = props.statistics.device
  return device && device.totalBytes > 0 ? Math.round((device.availableBytes / device.totalBytes) * 100) : 0
})
const attentionItems = computed(() => [
  {
    key: 'can_release' as const,
    label: '可释放原件',
    value: formatBytes(inventory.value.availableBytes ?? 0),
    hint: `${inventory.value.availableImages ?? 0} 张可用图片`,
    count: inventory.value.availableImages ?? 0,
  },
  {
    key: 'missing_lq' as const,
    label: '缺失轻量副本',
    value: `${inventory.value.missingLq ?? 0} 张`,
    hint: '可生成轻量副本',
    count: inventory.value.missingLq ?? 0,
  },
  {
    key: 'missing_source_sha256' as const,
    label: '缺少原件 SHA-256',
    value: `${inventory.value.missingSourceSha256Images} 张`,
    hint: '在漫画详情中核对媒体',
    count: inventory.value.missingSourceSha256Images,
  },
])
const visibleAttention = computed(() => attentionItems.value.filter((item) => item.count > 0))
const hasAttention = computed(() => visibleAttention.value.length > 0)
const attentionLabel = computed(() => attentionItems.value.find((item) => item.key === attention.value)?.label ?? '')
const sortLabel = computed(
  () =>
    ({
      totalBytes: '按登记大小降序',
      hqBytes: '按原件大小降序',
      lqBytes: '按轻量副本大小降序',
      title: '按标题升序',
    })[sort.value],
)
const listCountLabel = computed(() => {
  if (!hasLoaded.value) return '数量暂不可用'
  const prefix = listError.value ? '上次结果：' : ''
  const attentionText = attention.value ? ` · ${attentionLabel.value}` : ''
  return `${prefix}共 ${total.value} 部${attentionText} · ${sortLabel.value}`
})
const currentAttention = computed(() => {
  const value = current.value?.inventory
  return !!value && !!((value.availableImages ?? 0) || (value.missingLq ?? 0) || value.missingSourceSha256Images)
})
function actualPercent(value: number) {
  return observedTotal.value > 0 ? `${(value / observedTotal.value) * 100}%` : '0%'
}
function signedBytes(value: number) {
  if (value === 0) return '0 B'
  return `${value > 0 ? '+' : '-'}${formatBytes(Math.abs(value))}`
}
function searchStorage() {
  page.value = 1
  loadedPage.value = 1
  void load()
}
function changeSort(event: Event) {
  sort.value = (event.target as HTMLSelectElement).value as Sort
  page.value = 1
  loadedPage.value = 1
  void load()
}
function setAttention(value: Attention) {
  attention.value = attention.value === value ? '' : value
  page.value = 1
  loadedPage.value = 1
  void load()
}
function clearAttention() {
  if (!attention.value) return
  attention.value = ''
  page.value = 1
  loadedPage.value = 1
  void load()
}
async function load(append = false) {
  if (append && (loading.value || loadingMore.value || !!listError.value || records.value.length >= total.value)) return
  const request = ++generation
  if (append) loadingMore.value = true
  else {
    loading.value = true
    loadingMore.value = false
    appendError.value = ''
    listError.value = ''
  }
  const target = append ? loadedPage.value + 1 : page.value
  const order: 'asc' | 'desc' = sort.value === 'title' ? 'asc' : 'desc'
  const query = {
    page: target,
    pageSize: 20,
    keyword: keyword.value,
    sort: sort.value,
    order,
    attention: attention.value || undefined,
  }
  const signature = JSON.stringify({
    keyword: keyword.value,
    sort: sort.value,
    order,
    attention: attention.value || '',
  })
  if (!append && signature !== loadedQuery) {
    records.value = []
    total.value = 0
    selection.clear()
    hasLoaded.value = false
  }
  try {
    const result = await storageApi.comics(query)
    if (disposed || generation !== request) return
    const lastPage = Math.max(1, Math.ceil(result.total / 20))
    if (!append && page.value > lastPage) {
      page.value = lastPage
      loadedPage.value = lastPage
      await load()
      return
    }
    if (append) {
      records.value = [
        ...records.value,
        ...result.items.filter((item) => !records.value.some((row) => row.id === item.id)),
      ]
      loadedPage.value = target
    } else {
      records.value = result.items
      page.value = target
      loadedPage.value = target
      selection.retain(result.items.map((item) => item.id))
    }
    total.value = result.total
    hasLoaded.value = true
    loadedQuery = signature
  } catch (e) {
    if (!disposed && generation === request) {
      if (append) appendError.value = getApiErrorMessage(e, '追加作品占用失败')
      else listError.value = getApiErrorMessage(e, '无法读取作品占用')
    }
  } finally {
    if (!disposed && generation === request) {
      if (append) loadingMore.value = false
      else loading.value = false
    }
  }
}
function changePage(next: number) {
  if (loading.value || loadingMore.value) return
  page.value = next
  loadedPage.value = next
  void load()
}
function loadMore() {
  void load(true)
}
async function inspect(id: string, nextPage = 1) {
  const request = ++detailGeneration
  detailLoading.value = true
  error.value = ''
  chapterPage.value = nextPage
  try {
    const [item, result] = await Promise.all([
      storageApi.comic(id),
      storageApi.chapters(id, { page: nextPage, pageSize: 20, sort: 'totalBytes', order: 'desc' }),
    ])
    if (disposed || request !== detailGeneration) return
    current.value = item
    chapters.value = result.items
    chapterTotal.value = result.total
  } catch (e) {
    if (request === detailGeneration) error.value = getApiErrorMessage(e, '无法读取存储明细')
  } finally {
    if (request === detailGeneration) detailLoading.value = false
  }
}
function close() {
  ++detailGeneration
  current.value = null
  chapters.value = []
  detailLoading.value = false
}
watch(
  () => props.statistics,
  () => {
    void load()
    if (current.value) void inspect(current.value.id, chapterPage.value)
  },
)
void load()
onBeforeUnmount(() => {
  disposed = true
  ++generation
  ++detailGeneration
})
</script>

<template>
  <section class="mw-storage-hero" aria-label="媒体卷占用">
    <div class="mw-storage-hero-main">
      <div class="mw-storage-hero-head">
        <div>
          <p class="mw-storage-label">媒体卷实际占用</p>
          <strong v-if="observation" class="mw-storage-figure">{{ formatBytes(observedTotal) }}</strong>
          <strong v-else class="mw-storage-figure is-unscanned">尚未扫描</strong>
          <p class="mw-muted">
            {{ observation ? `上次扫描 ${formatDate(observation.finishedAt)}` : '还没有完整的磁盘扫描结果' }}
          </p>
          <p v-if="scanning" class="mw-storage-hint">扫描进行中，完成后更新实际占用；进度与结果见任务中心。</p>
          <p v-else class="mw-storage-hint">扫描成功后更新实际占用。可手动扫描，或在定时器中启用「媒体卷扫描」。</p>
        </div>
        <button class="mw-button" :disabled="!writable || scanning" :aria-busy="scanning" @click="emit('refresh')">
          <ManagementIcon name="scan" />{{ scanning ? '正在扫描' : '扫描媒体卷' }}
        </button>
      </div>
      <template v-if="observation">
        <div class="mw-storage-bar" role="img" :aria-label="actualBarLabel">
          <span
            v-for="segment in actualSegments"
            :key="segment.key"
            :class="`mw-seg-${segment.key}`"
            :style="{ width: actualPercent(segment.bytes) }"
          />
        </div>
        <div class="mw-storage-bar-legend mw-storage-legend">
          <span v-for="segment in actualSegments" :key="segment.key"
            ><i :class="`mw-seg-${segment.key}`" />{{ segment.name }} {{ formatBytes(segment.bytes) }}</span
          >
          <span v-if="!actualSegments.length" class="mw-muted">媒体卷为空</span>
        </div>
        <p v-if="nonRegisteredBytes" class="mw-storage-note">
          非登记占用 {{ formatBytes(nonRegisteredBytes) }}：{{
            nonRegistered.map((item) => `${item.name} ${formatBytes(item.usage.bytes)}`).join(' · ')
          }}
        </p>
        <p v-else class="mw-storage-note is-muted">没有临时、暂存、回收、导出或保留旧版本文件。</p>
      </template>
      <p v-else class="mw-storage-note is-muted">登记占用仍可读取；扫描后会显示实际盘上占用、临时文件与回收空间。</p>
    </div>
    <div class="mw-storage-hero-side">
      <div class="mw-storage-side">
        <span>登记占用</span>
        <strong>{{ formatBytes(bytes) }}</strong>
        <small
          >原件 {{ formatBytes(inventory.hq.bytes) }} · 副本 {{ formatBytes(inventory.lq.bytes) }} · 封面
          {{ formatBytes(inventory.covers.bytes) }}</small
        >
      </div>
      <div v-if="observation" class="mw-storage-side">
        <span>实际与登记差额</span>
        <strong :class="deltaClass">{{ signedBytes(delta) }}</strong>
        <small>{{ deltaHint }}</small>
      </div>
      <div v-if="statistics.device" class="mw-storage-side">
        <span>磁盘可用</span>
        <strong>{{ formatBytes(statistics.device.availableBytes) }}</strong>
        <div class="mw-storage-free" role="img" :aria-label="`磁盘可用 ${freePercent}%`">
          <span :style="{ width: `${freePercent}%` }" />
        </div>
        <small>{{ freePercent }}% 可用</small>
      </div>
    </div>
  </section>

  <details class="mw-observation">
    <summary>上次完整扫描</summary>
    <p v-if="!observation" class="mw-muted">尚未扫描媒体卷。</p>
    <template v-else>
      <div class="mw-storage-scan-meta">
        <span>开始 {{ formatDate(observation.startedAt) }}</span>
        <span>完成 {{ formatDate(observation.finishedAt) }}</span>
      </div>
      <p class="mw-muted">
        观测所属任务：<code>{{ observation.taskId }}</code>
      </p>
      <dl class="mw-storage-scan-grid">
        <div v-for="entry in observed" :key="entry.key">
          <dt>{{ entry.name }}</dt>
          <dd>{{ formatBytes(entry.usage.bytes) }} · {{ entry.usage.files }} 个文件</dd>
        </div>
      </dl>
    </template>
    <p v-if="statistics.refresh">
      最近扫描：{{ refreshStatusLabel }}
      {{ scanning ? '· 进度与结果见任务中心' : `· 已处理 ${statistics.refresh.completedCount} 项` }}
    </p>
    <p class="mw-muted">占用扫描只计量文件，不检查媒体完整性；失败或取消时保留上次完整结果。</p>
  </details>

  <section class="mw-storage-attention" aria-label="可优化项">
    <div class="mw-storage-attention-head">
      <h2>可优化</h2>
      <span class="mw-muted">按登记指标筛选作品</span>
    </div>
    <div v-if="hasAttention" class="mw-storage-attention-list">
      <button
        v-for="item in visibleAttention"
        :key="item.key"
        type="button"
        class="mw-attention"
        :class="{ active: attention === item.key }"
        :aria-pressed="attention === item.key"
        @click="setAttention(item.key)"
      >
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.hint }}</small>
      </button>
    </div>
    <p v-else class="mw-muted">当前没有待优化的存储项目。</p>
  </section>

  <div class="mw-section-tools">
    <div class="mw-storage-section-title">
      <h2>作品占用</h2>
      <span class="mw-muted">{{ listCountLabel }}</span>
    </div>
    <ManagementActionMenu label="恢复与维护"
      ><button class="mw-button" :disabled="!writable" @click="emit('recover')">扫盘恢复</button>
      <p>从媒体卷恢复缺失的作品登记。</p>
      <button class="mw-button" :disabled="busy" @click="emit('audit')">回收文件核对</button>
      <p>核对回收历史中的文件，与空间扫描分开。</p></ManagementActionMenu
    >
  </div>
  <div class="mw-toolbar mw-storage-toolbar" role="search" aria-label="搜索与筛选作品">
    <AppSearch v-model="keyword" label="搜索存储作品" placeholder="搜索作品" :disabled="busy" @search="searchStorage" />
    <span class="mw-storage-sort-label mw-muted">排序</span>
    <select class="mw-storage-sort" :value="sort" aria-label="作品排序" :disabled="busy" @change="changeSort">
      <option value="totalBytes">登记大小</option>
      <option value="hqBytes">原件大小</option>
      <option value="lqBytes">轻量副本大小</option>
      <option value="title">标题</option>
    </select>
    <span v-if="attention" class="mw-storage-filter">
      {{ attentionLabel }}
      <button type="button" aria-label="清除可优化筛选" @click="clearAttention">
        <ManagementIcon name="close" :size="12" />
      </button>
    </span>
  </div>
  <p v-if="error" class="mw-error" role="alert">{{ error }}</p>
  <p v-if="listError" class="mw-error" role="alert">{{ listError }}</p>
  <p v-if="listError" class="mw-muted">
    {{ hasLoaded ? '以下为上次成功读取的作品占用，请重新读取后再处理。' : '数量暂不可用，读取失败不代表没有作品。' }}
  </p>
  <div class="mw-storage-workspace" :class="{ 'mw-storage-detail-open': current || detailLoading }">
    <div>
      <div v-if="loading" class="mw-loading" role="status">正在读取占用排行…</div>
      <div v-else-if="listError && !records.length" class="mw-empty">
        <p>暂时无法读取作品占用</p>
        <button class="mw-button" @click="load()">重试读取作品占用</button>
      </div>
      <div v-else-if="!records.length" class="mw-empty">
        <p>{{ attention ? '当前筛选下没有作品' : total ? '本页没有作品，请刷新列表' : '没有匹配的作品' }}</p>
        <button v-if="attention" class="mw-button" @click="clearAttention">清除筛选</button>
      </div>
      <template v-else>
        <div class="mw-storage-list-head">
          <label class="mw-storage-select-all">
            <input
              type="checkbox"
              aria-label="选择已加载全部作品"
              :checked="allSelected"
              :indeterminate.prop="partial"
              :disabled="busy || loading || !!listError"
              @change="selection.selectAll"
            />
            <span>全选已加载</span>
          </label>
          <div v-if="selected.length" class="mw-storage-selection">
            <span class="mw-selection">已选 {{ selected.length }} 部</span>
            <button
              type="button"
              class="mw-button"
              :disabled="!writable || loading || !!listError"
              @click="
                emit(
                  'generate',
                  records.filter((item) => selected.includes(item.id)),
                )
              "
            >
              生成轻量副本
            </button>
            <button
              type="button"
              class="mw-button mw-button-danger"
              :disabled="!writable || loading || !!listError"
              @click="emit('releaseBatch', selected)"
            >
              预览释放图片原件
            </button>
          </div>
        </div>
        <ManagementPagedFeed
          :items="records"
          :page="page"
          :total="total"
          :loading="loading"
          :loading-more="loadingMore"
          :append-error="appendError"
          :disabled="!!listError || !hasLoaded"
          label="作品占用分页"
          @page-change="changePage"
          @load-more="loadMore"
          @retry="loadMore"
        >
          <template #default="{ items }">
            <article
              v-for="item in items"
              :key="item.id"
              class="mw-storage-row"
              :class="{ active: current?.id === item.id }"
            >
              <input
                type="checkbox"
                :aria-label="`选择存储作品${item.title}`"
                :checked="selected.includes(item.id)"
                :disabled="busy || !!listError"
                @change="selection.toggle(item.id)"
              /><button class="mw-storage-cover" :aria-label="`查看存储明细${item.title}`" @click="inspect(item.id)">
                <ManagementImage :src="comicCoverUrl(item.id)" :alt="item.title" />
              </button>
              <div class="mw-storage-row-copy">
                <button class="mw-file-title" @click="inspect(item.id)">{{ item.title }}</button>
                <div class="mw-storage-meta">
                  <span>{{ item.inventory.chapters }} 章 · {{ item.inventory.images }} 图</span>
                  <span v-if="(item.inventory.missingLq ?? 0) > 0" class="mw-storage-flag">
                    缺 {{ item.inventory.missingLq }} 张副本
                  </span>
                  <span v-if="item.inventory.missingSourceSha256Images > 0" class="mw-storage-flag is-warn">
                    缺少原件 SHA-256 {{ item.inventory.missingSourceSha256Images }} 张
                  </span>
                </div>
                <div class="mw-storage-components">
                  <span>原件 {{ formatBytes(item.inventory.hq.bytes) }}</span
                  ><span>副本 {{ formatBytes(item.inventory.lq.bytes) }}</span
                  ><span>封面 {{ formatBytes(item.inventory.covers.bytes) }}</span>
                </div>
              </div>
              <strong>{{ formatBytes(item.totalBytes) }}</strong>
            </article>
          </template>
        </ManagementPagedFeed>
      </template>
    </div>
    <aside v-if="current || detailLoading" class="mw-storage-inspector" aria-label="作品占用详情">
      <div class="mw-storage-inspector-head">
        <h3>作品占用</h3>
        <button class="mw-icon-button" aria-label="关闭作品占用详情" @click="close">
          <ManagementIcon name="close" />
        </button>
      </div>
      <p v-if="detailLoading" role="status">正在读取明细…</p>
      <template v-else-if="current"
        ><h2>{{ current.title }}</h2>
        <strong class="mw-storage-inspector-total">{{ formatBytes(current.totalBytes) }}</strong>
        <dl class="mw-storage-inspector-grid">
          <div>
            <dt>原件</dt>
            <dd>{{ formatBytes(current.inventory.hq.bytes) }}</dd>
          </div>
          <div>
            <dt>轻量副本</dt>
            <dd>{{ formatBytes(current.inventory.lq.bytes) }}</dd>
          </div>
          <div>
            <dt>漫画封面</dt>
            <dd>{{ formatBytes(current.inventory.covers.bytes) }}</dd>
          </div>
        </dl>
        <dl v-if="currentAttention" class="mw-storage-inspector-facts">
          <div>
            <dt>可释放原件</dt>
            <dd>{{ formatBytes(current.inventory.availableBytes ?? 0) }}</dd>
          </div>
          <div>
            <dt>缺失副本</dt>
            <dd>{{ current.inventory.missingLq ?? 0 }} 张</dd>
          </div>
          <div>
            <dt>缺少原件 SHA-256</dt>
            <dd>{{ current.inventory.missingSourceSha256Images }} 张</dd>
          </div>
        </dl>
        <button
          class="mw-button"
          :disabled="!writable || !current.operations.generateLq.canSubmit"
          @click="current && emit('generate-single', current)"
        >
          生成轻量副本
        </button>
        <p v-if="!current.operations.generateLq.canSubmit" class="mw-muted">
          {{ operationBlockers(current.operations.generateLq.blockers) }}
        </p>
        <button
          class="mw-button mw-button-danger"
          :disabled="!writable"
          @click="emit('release', 'comics', current.id, current.title)"
        >
          预览释放整部图片原件
        </button>
        <p v-if="!current.operations.releaseHq.canSubmit" class="mw-muted">
          {{ operationBlockers(current.operations.releaseHq.blockers) }}
        </p>
        <h3>章节占用</h3>
        <div v-for="chapter in chapters" :key="chapter.id" class="mw-chapter-usage">
          <div class="mw-chapter-copy">
            <span>{{ chapter.title }}</span>
            <small v-if="(chapter.inventory.missingLq ?? 0) > 0">缺 {{ chapter.inventory.missingLq }} 张副本</small>
          </div>
          <strong>{{ formatBytes(chapter.totalBytes) }}</strong
          ><button
            class="mw-button"
            :disabled="!writable"
            :aria-label="'预览释放' + chapter.title + '图片原件'"
            @click="emit('release', 'chapters', chapter.id, chapter.title)"
          >
            释放原件
          </button>
        </div>
        <div class="mw-inline-actions">
          <button class="mw-button" :disabled="chapterPage <= 1" @click="inspect(current.id, chapterPage - 1)">
            上一页</button
          ><button
            class="mw-button"
            :disabled="chapterPage * 20 >= chapterTotal"
            @click="inspect(current.id, chapterPage + 1)"
          >
            下一页
          </button>
        </div></template
      >
    </aside>
  </div>
</template>

<style scoped>
.mw-storage-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--atlas-space-6);
  padding: var(--atlas-space-2) 0 var(--atlas-space-6);
  border-bottom: 1px solid var(--atlas-line);
}
.mw-storage-hero-main {
  min-width: 0;
}
.mw-storage-hero-head {
  display: flex;
  justify-content: space-between;
  gap: var(--atlas-space-4);
  align-items: flex-start;
}
.mw-storage-label {
  margin: 0 0 var(--atlas-space-2);
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-storage-figure {
  display: block;
  font-size: 30px;
  font-weight: 500;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.mw-storage-figure.is-unscanned {
  color: var(--atlas-faint);
  font-size: 22px;
}
.mw-storage-figure + p {
  margin: var(--atlas-space-2) 0 0;
}
.mw-storage-hint {
  margin: var(--atlas-space-2) 0 0;
  max-width: 58ch;
  color: var(--atlas-faint);
  font-size: 11px;
  line-height: 1.5;
}
.mw-storage-bar {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: var(--atlas-raised);
  margin-top: var(--atlas-space-4);
}
.mw-seg-hq {
  background: var(--atlas-muted);
}
.mw-seg-lq {
  background: var(--atlas-text);
}
.mw-seg-covers {
  background: var(--atlas-accent);
}
.mw-seg-other {
  background: var(--atlas-warning);
}
.mw-storage-bar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--atlas-space-2) var(--atlas-space-4);
  margin-top: var(--atlas-space-3);
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-storage-bar-legend span {
  display: inline-flex;
  align-items: center;
  gap: var(--atlas-space-2);
}
.mw-storage-bar-legend i {
  width: 8px;
  height: 8px;
  border-radius: 2px;
}
.mw-storage-note {
  margin: 12px 0 0;
  color: var(--atlas-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.mw-storage-note.is-muted {
  color: var(--atlas-faint);
}
.mw-storage-hero-side {
  display: grid;
  align-content: start;
  gap: var(--atlas-space-4);
  border-left: 1px solid var(--atlas-line);
  padding-left: var(--atlas-space-6);
}
.mw-storage-side {
  display: grid;
  gap: var(--atlas-space-1);
}
.mw-storage-side > span {
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-storage-side > strong {
  font-size: 18px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.mw-storage-side > strong.is-positive {
  color: var(--atlas-warning);
}
.mw-storage-side > strong.is-negative {
  color: var(--atlas-error);
}
.mw-storage-side > small {
  color: var(--atlas-faint);
  font-size: 11px;
  overflow-wrap: anywhere;
}
.mw-storage-free {
  height: 4px;
  margin-top: 2px;
  border-radius: 2px;
  overflow: hidden;
  background: var(--atlas-line);
}
.mw-storage-free > span {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--atlas-text);
}
.mw-observation {
  margin-top: var(--atlas-space-4);
}
.mw-storage-scan-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--atlas-space-2) var(--atlas-space-4);
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-storage-scan-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(168px, 1fr));
  gap: var(--atlas-space-3) var(--atlas-space-5);
  margin: var(--atlas-space-4) 0;
}
.mw-storage-scan-grid dt {
  color: var(--atlas-muted);
  font-size: 12px;
}
.mw-storage-scan-grid dd {
  margin: 4px 0 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.mw-storage-attention {
  padding: var(--atlas-space-5) 0 0;
}
.mw-storage-attention-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--atlas-space-3);
  margin-bottom: var(--atlas-space-2);
}
.mw-storage-attention-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.mw-storage-attention-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--atlas-space-2);
}
.mw-attention {
  display: grid;
  gap: var(--atlas-space-1);
  padding: var(--atlas-space-2) var(--atlas-space-3);
  border: 1px solid var(--atlas-line);
  border-radius: 8px;
  background: var(--atlas-surface);
  color: var(--atlas-text);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    background 160ms ease;
}
.mw-attention > span {
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-attention > strong {
  font-size: 14px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.mw-attention > small {
  color: var(--atlas-faint);
  font-size: 11px;
}
.mw-attention:hover {
  border-color: var(--atlas-control-border-hover);
}
.mw-attention.active {
  border-color: var(--atlas-accent);
  background: var(--atlas-accent-soft);
}
.mw-attention.active > span {
  color: var(--atlas-text);
}
.mw-storage-section-title {
  display: flex;
  align-items: baseline;
  gap: var(--atlas-space-3);
  min-width: 0;
  flex: 1;
}
.mw-storage-section-title h2 {
  margin: 0;
}
.mw-storage-toolbar {
  display: flex;
  align-items: center;
  gap: var(--atlas-space-2);
  flex-wrap: wrap;
}
.mw-storage-toolbar .atlas-search {
  flex: 1 1 260px;
  min-width: 220px;
  max-width: 380px;
}
.mw-storage-sort-label {
  flex: 0 0 auto;
  font-size: 12px;
  white-space: nowrap;
}
.mw-storage-sort {
  flex: 0 0 auto;
  max-width: 160px;
}
.mw-storage-list-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--atlas-space-2) var(--atlas-space-3);
  min-height: 40px;
  padding: 0 0 var(--atlas-space-2);
  border-bottom: 1px solid var(--atlas-line);
  font-size: 12px;
}
.mw-storage-select-all {
  display: inline-flex;
  align-items: center;
  gap: var(--atlas-space-2);
  color: var(--atlas-text);
  white-space: nowrap;
}
.mw-storage-select-all input {
  width: 16px;
  height: 16px;
  margin: 0;
}
.mw-storage-selection {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  flex: 0 0 auto;
  gap: var(--atlas-space-2);
  max-width: 100%;
  margin-left: auto;
}
.mw-storage-selection .mw-selection {
  flex: 0 0 auto;
  white-space: nowrap;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.mw-storage-selection .mw-button {
  flex: 0 0 auto;
}
.mw-storage-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-storage-filter button {
  display: inline-flex;
  border: 0;
  padding: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.mw-storage-workspace.mw-storage-detail-open {
  grid-template-columns: minmax(0, 1fr) 320px;
}
.mw-storage-row {
  display: grid;
  grid-template-columns: 18px 44px minmax(0, 1fr) auto;
  gap: var(--atlas-space-3);
  align-items: center;
  padding: var(--atlas-space-3);
  margin: 0 calc(var(--atlas-space-3) * -1);
  border-radius: 8px;
  border-bottom: 1px solid var(--atlas-line);
}
.mw-storage-row:hover {
  background: var(--atlas-raised);
}
.mw-storage-row.active {
  background: var(--atlas-selected);
}
.mw-storage-row > strong {
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.mw-storage-cover {
  width: 44px;
}
.mw-storage-components {
  margin-top: var(--atlas-space-1);
  font-size: 11px;
}
.mw-storage-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--atlas-space-1) var(--atlas-space-3);
  margin-top: var(--atlas-space-1);
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-storage-flag {
  color: var(--atlas-warning);
}
.mw-storage-flag.is-warn {
  color: var(--atlas-error);
}
.mw-storage-inspector {
  width: 100%;
  min-width: 0;
}
.mw-storage-inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--atlas-space-2);
  margin-bottom: var(--atlas-space-4);
}
.mw-storage-inspector-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.mw-storage-inspector h2 {
  font-size: 18px;
  line-height: 1.35;
}
.mw-storage-inspector-total {
  display: block;
  margin: var(--atlas-space-1) 0 var(--atlas-space-4);
  font-size: 22px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.mw-storage-inspector-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--atlas-space-2);
  margin: 0 0 var(--atlas-space-4);
  padding: var(--atlas-space-3) 0;
  border-top: 1px solid var(--atlas-line);
  border-bottom: 1px solid var(--atlas-line);
}
.mw-storage-inspector-grid dt,
.mw-storage-inspector-facts dt {
  color: var(--atlas-muted);
  font-size: 11px;
}
.mw-storage-inspector-grid dd,
.mw-storage-inspector-facts dd {
  margin: var(--atlas-space-1) 0 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.mw-storage-inspector-facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--atlas-space-2);
  margin: 0 0 var(--atlas-space-4);
  padding: var(--atlas-space-4);
  border: 1px solid var(--atlas-line);
  border-radius: 8px;
  background: var(--atlas-raised);
}
.mw-storage-inspector-facts dd {
  color: var(--atlas-warning);
}
.mw-chapter-copy {
  display: grid;
  gap: var(--atlas-space-1);
  min-width: 0;
}
.mw-chapter-copy small {
  color: var(--atlas-warning);
  font-size: 11px;
}
@media (min-width: 1051px) {
  .mw-storage-inspector {
    position: sticky;
    top: 16px;
    align-self: start;
    max-height: calc(100dvh - 32px);
    overflow-y: auto;
  }
}
@media (max-width: 1180px) {
  .mw-storage-hero {
    grid-template-columns: minmax(0, 1fr) 260px;
  }
  .mw-storage-workspace.mw-storage-detail-open {
    grid-template-columns: minmax(0, 1fr) 300px;
  }
}
@media (max-width: 1050px) {
  .mw-storage-workspace.mw-storage-detail-open {
    grid-template-columns: 1fr;
  }
  .mw-storage-inspector {
    position: fixed;
    right: 0;
    top: 0;
    bottom: 0;
    width: min(360px, 100vw);
    overflow-y: auto;
    z-index: 12;
    padding: 20px;
    background: var(--atlas-surface);
    border-left: 1px solid var(--atlas-line);
  }
}
@media (max-width: 760px) {
  .mw-storage-hero {
    grid-template-columns: 1fr;
    gap: 18px;
  }
  .mw-storage-hero-head {
    flex-direction: column;
  }
  .mw-storage-hero-side {
    grid-template-columns: 1fr;
    border-left: 0;
    border-top: 1px solid var(--atlas-line);
    padding: 16px 0 0;
  }
  .mw-storage-row {
    grid-template-columns: 18px 40px minmax(0, 1fr) auto;
    gap: 10px;
    padding: 12px 8px;
    margin: 0 -8px;
  }
  .mw-storage-row > strong {
    margin-left: 0;
  }
  .mw-storage-toolbar .atlas-search {
    flex-basis: 100%;
    max-width: none;
  }
  .mw-storage-inspector-facts,
  .mw-storage-inspector-grid {
    grid-template-columns: 1fr;
  }
}
</style>
