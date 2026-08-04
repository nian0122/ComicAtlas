<template>
  <el-dialog
    :model-value="modelValue"
    title="危险操作确认"
    width="480px"
    align-center
    destroy-on-close
    class="danger-dialog"
    role="alertdialog"
    aria-describedby="danger-dialog-desc"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    :data-testid="dataTestId"
    @update:model-value="onUpdateModel"
  >
    <div class="danger-dialog-body">
      <p id="danger-dialog-desc" class="danger-dialog-desc">
        即将执行 <strong class="action-label">{{ actionLabel }}</strong>，此操作不可撤销。
        请输入漫画标题 <em class="title-em">{{ title }}</em> 以确认：
      </p>
      <el-input
        v-model="input"
        class="confirm-input"
        data-testid="danger-confirm-input"
        :placeholder="`输入「${title}」`"
        maxlength="255"
        @keyup.enter="onConfirm"
      />
    </div>
    <template #footer>
      <el-button :disabled="busy" @click="onCancel">取消</el-button>
      <el-button
        type="danger"
        class="confirm-btn"
        data-testid="danger-confirm-btn"
        :disabled="input !== title || busy"
        :loading="busy"
        @click="onConfirm"
      >
        确认执行
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  modelValue: boolean
  title: string
  actionLabel: string
  busy: boolean
  dataTestId?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()

const input = ref('')

watch(
  () => props.modelValue,
  (open) => {
    if (open) input.value = ''
  },
)

function onUpdateModel(value: boolean): void {
  if (props.busy) return
  emit('update:modelValue', value)
}

function onCancel(): void {
  if (props.busy) return
  emit('update:modelValue', false)
}

function onConfirm(): void {
  if (input.value !== props.title || props.busy) return
  emit('confirm')
}
</script>

<style scoped>
.danger-dialog-body {
  display: grid;
  gap: var(--space-4);
}

.danger-dialog-desc {
  margin: 0;
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--text-secondary);
}

.action-label {
  color: var(--danger);
  font-weight: 700;
}

.title-em {
  color: var(--text-primary);
  font-style: normal;
  font-weight: 700;
}
</style>
