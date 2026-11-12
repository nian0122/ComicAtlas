<script setup lang="ts">
import { reactive } from 'vue'
import { mediaTools } from '@/entities/system'
import { ManagementIcon, formatDate } from '@/shared/ui/management'
import { useSystem } from '../model/use-system'
const w = reactive(useSystem())
const descriptions = {
  ffmpeg: '视频转码与媒体处理',
  ffprobe: '视频流与媒体信息探测',
  cwebp: 'WebP 轻量图片生成',
  djpeg: 'JPEG 图片解码',
}
defineExpose({ refresh: w.refresh })
</script>
<template>
  <div class="system-workbench">
    <section class="system-panel">
      <div class="system-heading">
        <div>
          <h2>媒体工具</h2>
        </div>
        <button class="mw-button" :disabled="w.toolsLoading" @click="w.refresh">
          <ManagementIcon name="refresh" :class="{ 'mw-spin': w.toolsLoading }" />{{
            w.toolsLoading ? '正在查询' : '重新查询工具'
          }}
        </button>
      </div>
      <p class="mw-muted">
        {{ w.identity ? w.identity.name + ' · API ' + w.identity.apiVersion : '系统信息尚未读取'
        }}<span v-if="w.observedAt">
          · {{ w.toolsFresh ? '最近读取' : '上次读取' }} {{ formatDate(w.observedAt) }}</span
        >
      </p>
      <p v-if="w.identityError" class="mw-error" role="alert">{{ w.identityError }}</p>
      <p v-if="w.toolsError" class="mw-error" role="alert">
        {{ w.toolsError }}。{{ w.capabilities ? '下方保留上次查询结果，本次可用状态尚未核实。' : '未获得可用状态。' }}
      </p>
      <div v-if="w.capabilities" class="system-tools" :class="{ 'system-stale': !w.toolsFresh }">
        <article v-for="name in mediaTools" :key="name">
          <div>
            <strong>{{ name }}</strong
            ><span :class="w.capabilities[name].available ? 'system-available' : 'mw-muted'">{{
              w.capabilities[name].available ? '可用' : w.capabilities[name].configured ? '不可用' : '未配置'
            }}</span>
          </div>
          <p>{{ descriptions[name] }}</p>
          <small class="mw-mono">版本 {{ w.capabilities[name].version ?? '—' }}</small>
        </article>
      </div>
      <p v-else-if="w.toolsLoading" class="mw-loading">正在读取当前部署的工具状态…</p>
      <p class="mw-footnote">
        可用表示工具能够响应版本查询，不保证每种输入都能处理。具体任务仍以范围预检与实际执行结果为准。AI
        可用状态不在此查询范围内。
      </p>
    </section>
  </div>
</template>
<style scoped>
.system-workbench {
  max-width: 1100px;
  display: grid;
  gap: 24px;
  margin: 12px 0 36px;
}
.system-panel {
  border: 1px solid var(--atlas-line);
  border-radius: var(--atlas-radius-control);
  background: var(--atlas-surface);
  padding: 24px;
}
.system-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.system-heading h2 {
  font-size: 20px;
  margin: 6px 0 12px;
}
.system-tools {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 20px 0;
}
.system-tools article {
  border: 1px solid var(--atlas-line);
  padding: 18px;
  border-radius: 8px;
  min-width: 0;
}
.system-tools article > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.system-tools strong {
  font-size: 16px;
}
.system-tools p,
.system-tools small {
  color: var(--atlas-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.system-available {
  color: var(--atlas-text);
}
.system-available::before {
  content: '';
  display: inline-block;
  width: 5px;
  height: 5px;
  background: currentColor;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}
.system-stale {
  opacity: 0.6;
}
@media (max-width: 700px) {
  .system-panel {
    padding: 18px;
  }
  .system-tools {
    grid-template-columns: 1fr;
  }
}
</style>
