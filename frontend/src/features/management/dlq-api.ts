import { api } from '@/services/http'
import type { DlqMessageVO, DlqPurgeResult, DlqQueueVO, DlqReplayResult } from './types'

export const dlqApi = {
  queues: () => api.get<readonly DlqQueueVO[]>('/manage/admin/dlq/queues'),
  messages: (queueName: string, count = 20) =>
    api.get<readonly DlqMessageVO[]>(
      `/manage/admin/dlq/queues/${encodeURIComponent(queueName)}/messages`,
      { params: { count } },
    ),
  replay: (queueName: string, maxMessages = 100) =>
    api.post<DlqReplayResult>(
      `/manage/admin/dlq/queues/${encodeURIComponent(queueName)}/replay`,
      undefined,
      { params: { maxMessages } },
    ),
  purge: (queueName: string) =>
    api.delete<DlqPurgeResult>(
      `/manage/admin/dlq/queues/${encodeURIComponent(queueName)}/messages`,
    ),
}
