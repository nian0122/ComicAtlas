import type {
  EnumValue,
  ManagementTaskStatus,
  OperationName,
  TargetType,
  TaskStage,
  TaskType,
} from './enums'

/**
 * 管理任务领域（T17）——对应后端 ManagementTaskController
 *
 * `ManagementTaskEntry` / `ManagementTaskItemEntry` 是边界解析后的规范化模型：
 * 所有枚举字段统一为 `EnumValue<T>`（known | unknown），禁止裸 string。
 */

/** MyBatis-Plus IPage 序列化形状 */
export interface PaginatedPage<T> {
  readonly records: readonly T[]
  readonly total: number
  readonly size: number
  readonly current: number
  readonly pages: number
}

/** 规范化管理任务（ManagementTaskResponse） */
export interface ManagementTaskEntry {
  readonly id: number
  readonly taskType: EnumValue<TaskType>
  readonly operation: EnumValue<OperationName>
  readonly targetType: EnumValue<TargetType>
  readonly batchId: string
  readonly isBatch: boolean
  readonly status: EnumValue<ManagementTaskStatus>
  readonly stage: EnumValue<TaskStage> | null
  readonly progress: number
  readonly totalCount: number
  readonly successCount: number
  readonly failureCount: number
  readonly cancelledCount: number
  readonly errorMessage: string
  readonly attempt: number
  readonly version: number
  readonly createdAt: string
  readonly updatedAt: string
  readonly startedAt: string
  readonly completedAt: string
}

/** 规范化管理任务条目（ManagementTaskItemResponse） */
export interface ManagementTaskItemEntry {
  readonly id: number
  readonly taskId: number
  readonly targetType: EnumValue<TargetType>
  readonly targetId: number
  readonly operationType: EnumValue<TaskType>
  readonly status: EnumValue<ManagementTaskStatus>
  readonly attempt: number
  readonly progress: number
  readonly resultRefType: string
  readonly resultRefId: number
  readonly errorMessage: string
  readonly version: number
  readonly createdAt: string
  readonly updatedAt: string
  readonly startedAt: string
  readonly completedAt: string
}

/** 创建管理任务请求（CreateManagementTaskRequest） */
export interface CreateManagementTaskRequest {
  readonly taskType: TaskType
  readonly operation: string
  readonly targetType?: TargetType
  readonly batchId?: string
  readonly targets?: readonly {
    readonly targetType: string
    readonly targetId: number
    readonly operationType?: TaskType
  }[]
}

/** 任务列表查询参数 */
export interface ManagementTaskListQuery {
  readonly page?: number
  readonly size?: number
  readonly type?: TaskType
  readonly status?: ManagementTaskStatus
  readonly batchId?: string
  readonly targetType?: TargetType
  readonly targetId?: number
}

/** 操作提交结果（OperationSubmitResult） */
export interface OperationSubmitResult {
  readonly taskId: number | null
  readonly taskType: TaskType
  readonly status: ManagementTaskStatus
  readonly itemCount: number
}
