<template>
  <div class="settings-page">
    <header class="page-header">
      <h1 class="page-title">设置</h1>
    </header>

    <section class="settings-card">
      <h2 class="section-title">阅读默认设置</h2>

      <div class="setting-row">
        <label class="setting-label">默认画质</label>
        <el-select v-model="form.defaultQuality" class="setting-select">
          <el-option label="自动" value="auto" />
          <el-option label="原图" value="hq" />
          <el-option label="省流" value="lq" />
        </el-select>
      </div>

      <div class="setting-row">
        <label class="setting-label">默认适配</label>
        <el-select v-model="form.defaultFit" class="setting-select">
          <el-option label="自动" value="auto" />
          <el-option label="适配宽度" value="width" />
          <el-option label="适配高度" value="height" />
          <el-option label="原始尺寸" value="original" />
        </el-select>
      </div>

      <div class="setting-row">
        <label class="setting-label">默认方向</label>
        <el-select v-model="form.defaultDirection" class="setting-select">
          <el-option label="纵向" value="vertical" />
          <el-option label="横向" value="horizontal" />
        </el-select>
      </div>

      <div class="setting-actions">
        <el-button type="primary" :loading="saving" @click="handleSave">保存设置</el-button>
      </div>
    </section>

    <section class="settings-card">
      <h2 class="section-title">阅读增强</h2>
      <p class="section-desc">即时生效，与阅读器内设置同步</p>

      <div class="setting-row">
        <div class="setting-info">
          <label class="setting-label">预加载</label>
          <span class="setting-hint">提前加载邻近页面，滚动更流畅</span>
        </div>
        <el-switch v-model="readerSettings.enablePreload" />
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/services/management'
import { useReaderSettingsStore } from '@/stores/reader-settings-store'

const readerSettings = useReaderSettingsStore()

interface SettingsForm {
  defaultQuality: string
  defaultFit: string
  defaultDirection: string
}

const form = reactive<SettingsForm>({
  defaultQuality: 'auto',
  defaultFit: 'auto',
  defaultDirection: 'vertical',
})

const saving = ref(false)

async function loadSettings() {
  try {
    const res = await settingsApi.get()
    const data = res.data as SettingsForm
    if (data) Object.assign(form, data)
  } catch { /* keep defaults */ }
}

async function handleSave() {
  saving.value = true
  try {
    await settingsApi.update({ ...form })
    ElMessage.success('设置已保存')
  } catch (err: unknown) {
    const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped>
.settings-page {
  max-width: 640px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-xl);
}

.settings-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-xl);
}

.settings-card + .settings-card {
  margin-top: var(--space-lg);
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0 0 var(--space-lg);
}

.section-desc {
  font-size: 12px;
  color: var(--text-tertiary);
  margin: calc(-1 * var(--space-base)) 0 var(--space-lg);
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-base) 0;
  border-bottom: 1px solid var(--border);
}

.setting-row:last-of-type {
  border-bottom: none;
}

.setting-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.setting-label {
  font-size: 14px;
  color: var(--text-secondary);
  font-weight: 600;
}

.setting-hint {
  font-size: 12px;
  color: var(--text-tertiary);
}

.setting-select {
  width: 180px;
}

.setting-actions {
  margin-top: var(--space-xl);
  display: flex;
  justify-content: flex-end;
}
</style>
