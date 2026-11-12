export type ScheduleTrigger = 'startup' | 'schedule' | 'manual' | 'auto'

export interface ScheduleRun {
  id: string
  scheduleId: string
  trigger: ScheduleTrigger
  startedAt: string
  finishedAt: string | null
  processed: number
  error: string | null
}

export interface Schedule {
  id: string
  title: string
  description: string
  unit: string
  intervalSeconds: number
  enabled: boolean
  running: boolean
  nextRunAt: string | null
  lastRun: ScheduleRun | null
  runs: ScheduleRun[]
}

export interface ScheduleUpdate {
  enabled?: boolean
  intervalSeconds?: number
}
