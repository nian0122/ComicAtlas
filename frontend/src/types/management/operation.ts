import type { OperationName, TargetType } from './enums'

/**
 * 允许操作领域（T17）——对应后端 MediaOperationController + OperationPolicyService
 */

/** 某个目标当前允许的操作集合 */
export interface AllowedOperations {
  readonly allowed: readonly OperationName[]
  /** blockedReasons[operation] = 阻塞原因文案；key 可为 "*"（全部阻塞） */
  readonly blockedReasons: Readonly<Record<string, string>>
}

/** 操作目标：可对漫画/章节/媒体查询允许操作 */
export type OperationTarget =
  | { readonly targetType: Extract<TargetType, 'COMIC'>; readonly targetId: number }
  | { readonly targetType: Extract<TargetType, 'CHAPTER'>; readonly targetId: number }
  | { readonly targetType: Extract<TargetType, 'MEDIA'>; readonly targetId: number }

/** 目标类型 → 对应操作端点 URL 片段 */
export function operationPath(target: OperationTarget): string {
  const targetType = target.targetType
  switch (targetType) {
    case 'COMIC':
      return `/management/operations/comics/${target.targetId}`
    case 'CHAPTER':
      return `/management/operations/chapters/${target.targetId}`
    case 'MEDIA':
      return `/management/operations/media/${target.targetId}`
    default:
      return assertNever(targetType)
  }
}

/** 穷举 switch 兜底（与 types/management/enums.ts 同名导出保持一致） */
function assertNever(value: never): never {
  throw new Error(`Unexpected targetType: ${String(value)}`)
}
