/** 管理端允许操作查询结果。 */
export interface MediaOperationResult {
  readonly allowed: readonly string[]
  readonly blockedReasons: Readonly<Record<string, string>>
}

/** Outbox 积压统计。 */
export interface OutboxStats {
  readonly pending: number
  readonly failed: number
  readonly total: number
}

/** MQ 积压与死信统计。 */
export interface MqStats {
  readonly available: boolean
  readonly dlqTotal: number
  readonly dlqQueues: number
  readonly queuedTotal: number
  readonly queues: readonly MqQueueStat[]
}

/** 单队列积压快照。 */
export interface MqQueueStat {
  readonly name: string
  readonly messages: number
  readonly consumers: number
  readonly dlq: boolean
}

export interface DlqQueueVO {
  readonly name: string
  readonly exchange: string
  readonly routingKey: string
  readonly originalQueue: string
  readonly messages: number
  readonly consumers: number
}

export interface DlqMessageVO {
  readonly payload: string
  readonly payloadEncoding: 'string' | 'base64'
  readonly properties: Readonly<Record<string, unknown>>
  readonly messagesRemaining: number
}

export interface DlqReplayResult {
  readonly queue: string
  readonly attempted: number
  readonly replayed: number
  readonly remaining: number
  readonly completed: boolean
  readonly error: string | null
}

export interface DlqPurgeResult {
  readonly queue: string
  readonly purged: number
}
