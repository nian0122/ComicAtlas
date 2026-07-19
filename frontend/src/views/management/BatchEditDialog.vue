<template>
  <el-dialog
    v-model="visible"
    title="批量编辑"
    width="480px"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <el-form label-width="60px">
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
    <p style="color: #909399; font-size: 13px; margin: 0;">
      将为选中的 <strong>{{ comicIds.length }}</strong> 部漫画统一设置
    </p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onConfirm">确认</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useCategoryStore } from '@/stores/management/category'
import { useTagStore } from '@/stores/tag-store'
import { comicApi } from '@/services/api'
import type { BatchComicUpdateDTO } from '@/types'

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
  if (categoryId.value === null && addTagIds.value.length === 0) {
    ElMessage.warning('请至少选择分类或标签')
    return
  }
  saving.value = true
  try {
    const data: BatchComicUpdateDTO = {
      comicIds: props.comicIds,
      categoryId: categoryId.value,
      addTagIds: addTagIds.value.length > 0 ? addTagIds.value : undefined,
    }
    const res = await comicApi.batchUpdate(data)
    const result = res.data
    if (result.succeeded === 0) {
      ElMessage.error('所有漫画更新失败')
    } else if (result.failed && result.failed.length > 0) {
      ElMessage.warning(`完成：${result.succeeded} 部成功，${result.failed.length} 部失败`)
    } else {
      ElMessage.success(`已为 ${result.succeeded} 部漫画更新`)
    }
    emit('saved')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  } finally {
    saving.value = false
  }
}
</script>
