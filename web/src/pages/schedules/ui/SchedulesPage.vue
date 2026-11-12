<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { Schedule } from '@/entities/schedule'
import { ManagementShell } from '@/widgets/management-shell'
import { ManagementIcon, formatDate } from '@/shared/ui/management'
import { useSchedules, type IntervalUnit } from '../model/use-schedules'
const router = useRouter()
const {
  schedules,
  loading,
  error,
  notice,
  runningId,
  savingId,
  activeTasks,
  summary,
  draft,
  setDraft,
  isDirty,
  isRunning,
  triggerLabel,
  runResult,
  stateLabel,
  stateClass,
  load,
  run,
  setEnabled,
  saveInterval,
} = useSchedules()

function navigate(section: string) {
  if (section === 'timers') return
  void router.push(section === 'library' ? '/manage' : `/manage/${section}`)
}
function changeAmount(item: Schedule, event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  if (!Number.isFinite(value)) return
  setDraft(item.id, value, draft(item).unit)
}
function changeUnit(item: Schedule, event: Event) {
  setDraft(item.id, draft(item).amount, (event.target as HTMLSelectElement).value as IntervalUnit)
}
function changeEnabled(item: Schedule, event: Event) {
  void setEnabled(item, (event.target as HTMLInputElement).checked)
}
function runLabel(item: Schedule) {
  if (isRunning(item)) return '执行中'
  return runningId.value === item.id ? '正在提交' : '立即执行'
}
</script>

<template>
  <ManagementShell
    section="timers"
    :active-tasks="activeTasks"
    :busy="false"
    :inspector="false"
    :outline="false"
    @section="navigate"
  >
    <div class="mw-content">
      <div class="mw-page-heading sched-heading">
        <div>
          <h1>定时器</h1>
          <p>按计划维护资料与存储；可暂停、调整间隔或立即执行。</p>
        </div>
        <div class="mw-inline-actions">
          <span class="mw-muted" aria-live="polite">{{ summary }}</span>
          <button class="mw-button" :disabled="loading" @click="load()">
            <ManagementIcon name="refresh" :class="{ 'mw-spin': loading }" />刷新
          </button>
        </div>
      </div>

      <p v-if="notice" class="mw-muted" role="status">{{ notice }}</p>
      <div v-if="error" class="mw-error" role="alert">
        <p>{{ error }}</p>
        <button class="mw-button" @click="load()">重新读取</button>
      </div>
      <div v-if="loading && !schedules.length" class="mw-loading" role="status">正在读取定时器…</div>
      <div v-else-if="!schedules.length" class="mw-empty">
        <ManagementIcon name="clock" :size="22" />
        <h2>还没有注册定时器</h2>
        <p>新任务会随版本注册到这里；添加后可在此查看状态、执行日志并调整计划。</p>
      </div>

      <section v-else class="sched-list" aria-label="定时器">
        <article v-for="item in schedules" :key="item.id" class="sched-item">
          <header class="sched-head">
            <div class="sched-copy">
              <h2>{{ item.title }}</h2>
              <p class="mw-muted">{{ item.description }}</p>
            </div>
            <div class="sched-actions">
              <span class="sched-state" :class="stateClass(item)">{{ stateLabel(item) }}</span>
              <button
                class="mw-button mw-button-primary"
                :disabled="isRunning(item) || !!runningId || !!savingId"
                @click="run(item.id)"
              >
                <ManagementIcon name="refresh" :class="{ 'mw-spin': isRunning(item) }" />{{ runLabel(item) }}
              </button>
            </div>
          </header>

          <dl class="sched-facts">
            <div class="sched-fact">
              <dt>执行间隔</dt>
              <dd>
                <div class="sched-interval">
                  <input
                    type="number"
                    min="1"
                    step="1"
                    inputmode="numeric"
                    :value="draft(item).amount"
                    :aria-label="`${item.title}间隔数值`"
                    @input="changeAmount(item, $event)"
                  />
                  <select
                    :value="draft(item).unit"
                    :aria-label="`${item.title}间隔单位`"
                    @change="changeUnit(item, $event)"
                  >
                    <option value="seconds">秒</option>
                    <option value="minutes">分钟</option>
                    <option value="hours">小时</option>
                    <option value="days">天</option>
                  </select>
                  <button
                    v-if="isDirty(item.id)"
                    class="mw-button"
                    :disabled="!!runningId || !!savingId"
                    @click="saveInterval(item)"
                  >
                    保存
                  </button>
                </div>
              </dd>
            </div>
            <div class="sched-fact">
              <dt>下次执行</dt>
              <dd>{{ item.enabled ? (item.nextRunAt ? formatDate(item.nextRunAt) : '等待排期') : '已暂停' }}</dd>
            </div>
            <div class="sched-fact">
              <dt>上次执行</dt>
              <dd>
                <template v-if="item.lastRun"
                  >{{ formatDate(item.lastRun.finishedAt) }} · {{ item.lastRun.processed }} {{ item.unit }} ·
                  {{ runResult(item.lastRun) }}</template
                >
                <span v-else class="mw-muted">尚未执行</span>
              </dd>
            </div>
            <div class="sched-fact">
              <dt>定时执行</dt>
              <dd>
                <label class="sched-toggle">
                  <input
                    type="checkbox"
                    :checked="item.enabled"
                    :disabled="!!runningId || !!savingId"
                    @change="changeEnabled(item, $event)"
                  />
                  <span>{{ item.enabled ? '已启用' : '已暂停' }}</span>
                </label>
              </dd>
            </div>
          </dl>

          <details class="sched-log">
            <summary>
              执行日志<span class="mw-muted">最近 {{ item.runs.length }} 次</span>
            </summary>
            <p v-if="!item.runs.length" class="mw-muted sched-log-empty">还没有执行记录。</p>
            <div v-else class="sched-log-scroll">
              <table>
                <thead>
                  <tr>
                    <th scope="col">触发</th>
                    <th scope="col">开始</th>
                    <th scope="col">完成</th>
                    <th scope="col">处理</th>
                    <th scope="col">结果</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="entry in item.runs" :key="entry.id" :class="{ 'is-error': entry.error }">
                    <td>{{ triggerLabel(entry.trigger) }}</td>
                    <td>{{ formatDate(entry.startedAt) }}</td>
                    <td>{{ entry.finishedAt ? formatDate(entry.finishedAt) : '进行中' }}</td>
                    <td>{{ entry.processed }} {{ item.unit }}</td>
                    <td class="sched-result">
                      {{ runResult(entry) }}
                      <span
                        v-if="entry.error && entry.error !== 'cancelled' && entry.error !== 'interrupted'"
                        class="sched-error"
                        >{{ entry.error }}</span
                      >
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
        </article>
      </section>
      <p class="mw-muted sched-footer">定时器由代码注册、随版本增加；这里只管理已注册任务的启用、间隔与执行。</p>
    </div>
  </ManagementShell>
</template>

<style scoped>
.sched-list {
  margin-top: 18px;
  border-top: 1px solid var(--atlas-line);
}
.sched-item {
  padding: 22px 0;
  border-bottom: 1px solid var(--atlas-line);
}
.sched-head {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: flex-start;
}
.sched-copy h2 {
  margin: 0 0 5px;
  font-size: 16px;
  font-weight: 600;
}
.sched-copy p {
  margin: 0;
  max-width: 62ch;
}
.sched-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.sched-state {
  font-size: 11px;
  padding: 4px 9px;
  border: 1px solid var(--atlas-line);
  border-radius: 999px;
  color: var(--atlas-muted);
}
.sched-state.is-running {
  color: var(--atlas-text);
  border-color: var(--atlas-accent);
}
.sched-state.is-paused {
  color: var(--atlas-warning);
}
.sched-state.is-error {
  color: var(--atlas-error);
  border-color: var(--atlas-error);
}
.sched-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 16px 24px;
  margin: 18px 0 0;
  font-size: 12px;
}
.sched-fact dt {
  color: var(--atlas-muted);
  margin-bottom: 5px;
}
.sched-fact dd {
  margin: 0;
  color: var(--atlas-text);
}
.sched-interval {
  display: flex;
  align-items: center;
  gap: 6px;
}
.sched-interval input,
.sched-interval select {
  height: 32px;
  border: 1px solid var(--atlas-control-border);
  border-radius: 6px;
  background: var(--atlas-control-fill);
  color: var(--atlas-text);
  padding: 0 8px;
  font: inherit;
}
.sched-interval input {
  width: 5.5ch;
}
.sched-interval input:focus-visible,
.sched-interval select:focus-visible {
  outline: 2px solid var(--atlas-control-focus);
  outline-offset: 1px;
}
.sched-interval .mw-button {
  height: 32px;
}
.sched-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.sched-toggle input {
  width: 16px;
  height: 16px;
  accent-color: var(--atlas-accent);
}
.sched-log {
  margin-top: 16px;
}
.sched-log summary {
  display: flex;
  align-items: baseline;
  gap: 10px;
  cursor: pointer;
  color: var(--atlas-text);
  font-size: 12px;
}
.sched-log-empty {
  margin: 10px 0 0;
}
.sched-log-scroll {
  overflow-x: auto;
}
.sched-log table {
  width: 100%;
  border-collapse: collapse;
  margin-top: 10px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.sched-log th,
.sched-log td {
  text-align: left;
  padding: 7px 10px 7px 0;
  border-bottom: 1px solid var(--atlas-line);
  white-space: nowrap;
}
.sched-log th {
  color: var(--atlas-faint);
  font-weight: 500;
}
.sched-log tr.is-error td {
  color: var(--atlas-error);
}
.sched-result {
  white-space: normal;
  min-width: 160px;
}
.sched-error {
  display: block;
  margin-top: 3px;
  color: var(--atlas-error);
}
.sched-footer {
  margin: 18px 0 0;
}
@media (max-width: 760px) {
  .sched-heading {
    flex-direction: column;
    gap: 12px;
  }
  .sched-heading .mw-inline-actions {
    max-width: none;
    justify-content: space-between;
  }
  .sched-head {
    flex-direction: column;
  }
  .sched-actions {
    width: 100%;
    justify-content: space-between;
  }
  .sched-log th,
  .sched-log td {
    padding-right: 14px;
  }
}
</style>
