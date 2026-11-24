import type { Inventory } from './types'

export type StorageTone = 'good' | 'warn' | 'muted'

export interface StorageState {
  label: string
  tone: StorageTone
  detail: string
}

/** 原件登记状态只描述资料登记，不证明磁盘文件实际存在。 */
export function describeHqState(inventory: Inventory, status: string): StorageState {
  const total = inventory.images + inventory.videos
  const registered = inventory.hq.files
  switch (status) {
    case 'available':
      return { label: '原件登记齐全', tone: 'good', detail: `${total} 个媒体文件均有原件登记` }
    case 'mixed':
      return {
        label: `原件 ${registered}/${total}`,
        tone: 'warn',
        detail: `${Math.max(0, total - registered)} 个媒体文件缺少原件登记`,
      }
    case 'releasing':
      return {
        label: '原件释放中',
        tone: 'warn',
        detail: inventory.releasingImages ? `${inventory.releasingImages} 张图片正在释放` : '正在释放图片原件',
      }
    case 'released':
      return { label: '原件已释放', tone: 'muted', detail: '原件已释放，阅读会优先使用轻量副本' }
    case 'empty':
      return { label: '无原件登记', tone: 'muted', detail: '尚未登记图片或视频原件' }
    default:
      return { label: `原件状态未识别（${status}）`, tone: 'muted', detail: '服务端返回了未识别的原件状态' }
  }
}

export function describeLqState(inventory: Inventory, status: string): StorageState {
  const total = inventory.images
  const ready = inventory.currentLqImages
  const missing = inventory.missingLq ?? Math.max(0, total - ready)
  switch (status) {
    case 'ready':
      return { label: '副本已就绪', tone: 'good', detail: `${total} 张图片均有有效轻量副本` }
    case 'partial':
      return { label: `副本 ${ready}/${total}`, tone: 'warn', detail: `${missing} 张图片缺少有效轻量副本` }
    case 'missing':
      return { label: '副本未就绪', tone: 'warn', detail: `${missing || total} 张图片缺少有效轻量副本` }
    case 'empty':
      return { label: '无副本', tone: 'muted', detail: '没有需要生成轻量副本的图片' }
    default:
      return { label: `副本状态未识别（${status}）`, tone: 'muted', detail: '服务端返回了未识别的副本状态' }
  }
}

/** 单个媒体沿用后端的原件／副本状态码，未知状态直接显示，不猜测。 */
export function describeMediaState(kind: 'hq' | 'lq', status: string): StorageState {
  const labels: Record<string, { label: string; tone: StorageTone }> = {
    available: { label: '原件已登记', tone: 'good' },
    ready: { label: '副本已登记', tone: 'good' },
    mixed: { label: '部分原件已释放', tone: 'warn' },
    releasing: { label: '原件释放中', tone: 'warn' },
    pending: { label: '副本排队中', tone: 'warn' },
    processing: { label: '副本处理中', tone: 'warn' },
    failed: { label: '副本处理失败', tone: 'warn' },
    released: { label: '原件已释放', tone: 'muted' },
    missing: { label: kind === 'hq' ? '原件缺失' : '副本未就绪', tone: 'warn' },
    none: { label: '无副本', tone: 'muted' },
  }
  const known = labels[status]
  if (known) return { ...known, detail: '' }
  return {
    label: `${kind === 'hq' ? '原件' : '副本'}状态未识别（${status}）`,
    tone: 'muted',
    detail: '服务端返回了未识别的状态',
  }
}

/** storageState 是后端最近一次实体核对的观测结果，不代表实时磁盘。 */
export function describeObservedState(status: string | undefined): StorageState {
  switch (status) {
    case 'present':
      return { label: '存在', tone: 'good', detail: '上次核对时文件存在' }
    case 'missing':
      return { label: '缺失', tone: 'warn', detail: '上次核对未找到文件' }
    case 'changed':
      return { label: '已变化', tone: 'warn', detail: '上次核对发现内容变化' }
    case 'invalid':
      return { label: '无法校验', tone: 'warn', detail: '上次核对无法读取文件' }
    case 'released':
      return { label: '已释放', tone: 'muted', detail: '原件已释放' }
    case undefined:
    case '':
      return { label: '未核对', tone: 'muted', detail: '还没有实体核对记录' }
    default:
      return { label: `未识别核对状态（${status}）`, tone: 'muted', detail: '服务端返回了未识别的核对状态' }
  }
}
