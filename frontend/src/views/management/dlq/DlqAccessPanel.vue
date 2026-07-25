<template>
  <section class="access-panel" aria-labelledby="dlq-access-title">
    <div class="access-copy">
      <span class="access-kicker">受保护操作区</span>
      <h2 id="dlq-access-title">验证管理凭据</h2>
      <p>凭据只保存在当前页面内存中，刷新或离开页面后立即清除。</p>
    </div>

    <form class="access-form" @submit.prevent="submit">
      <label>
        <span>用户名</span>
        <el-input
          v-model="form.username"
          autocomplete="username"
          placeholder="默认开发用户为 user"
          size="large"
        />
      </label>
      <label>
        <span>密码</span>
        <el-input
          v-model="form.password"
          autocomplete="current-password"
          placeholder="输入服务启动时生成或配置的密码"
          show-password
          size="large"
          type="password"
        />
      </label>
      <p v-if="error" class="access-error" role="alert">{{ error }}</p>
      <el-button
        class="connect-button"
        :disabled="!canSubmit"
        :loading="loading"
        native-type="submit"
        size="large"
        type="primary"
      >
        连接管理接口
      </el-button>
    </form>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { DlqCredentials } from '@/services/api'

defineProps<{
  readonly loading: boolean
  readonly error: string
}>()

const emit = defineEmits<{
  connect: [credentials: DlqCredentials]
}>()

const form = reactive({
  username: 'user',
  password: '',
})

const canSubmit = computed(
  () => form.username.trim().length > 0 && form.password.length > 0,
)

function submit() {
  if (!canSubmit.value) return
  emit('connect', {
    username: form.username.trim(),
    password: form.password,
  })
}
</script>

<style scoped>
.access-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 420px);
  gap: var(--space-10);
  align-items: center;
  min-height: 320px;
  padding: var(--space-10);
  border: 1px solid var(--border);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius-md);
  background: var(--bg-secondary);
}

.access-copy {
  max-width: 540px;
}

.access-kicker {
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 800;
  letter-spacing: var(--tracking-kicker);
}

.access-copy h2 {
  margin: var(--space-3) 0 var(--space-4);
  color: var(--text-primary);
  font-family: var(--font-editorial);
  font-size: var(--text-section);
}

.access-copy p,
.access-error {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-sm);
  line-height: var(--leading-body);
}

.access-form {
  display: grid;
  gap: var(--space-4);
}

.access-form label {
  display: grid;
  gap: var(--space-2);
  color: var(--text-secondary);
  font-size: var(--text-xs);
  font-weight: 700;
}

.access-error {
  color: var(--danger);
}

.access-form :deep(.el-input__wrapper) {
  min-height: var(--control-min-size);
}

.access-form :deep(.el-input__suffix),
.access-form :deep(.el-input__password) {
  display: grid;
  min-width: var(--control-min-size);
  min-height: var(--control-min-size);
  place-items: center;
}

.connect-button {
  width: 100%;
  min-height: var(--control-min-size);
}

@media (max-width: 768px) {
  .access-panel {
    grid-template-columns: 1fr;
    gap: var(--space-6);
    padding: var(--space-6);
  }
}
</style>
