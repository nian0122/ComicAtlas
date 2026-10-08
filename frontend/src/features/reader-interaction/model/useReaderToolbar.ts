import { computed, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'

/**
 * Reader 工具栏状态机（设计规范 §3）。
 *
 * 三态：IMMERSIVE（全屏阅读）↔ TOOLBAR（工具栏可见）↔ SETTINGS（设置抽屉）。
 * 所有状态切换必须经由 TRANSITIONS 表驱动，禁止直接 setState。
 *
 * 注：项目 tsconfig 启用 erasableSyntaxOnly，禁用 enum 语法，
 * 故采用数值 as const 对象 + 同名类型别名（语义等价于数值枚举，
 * ReaderUiState.IMMERSIVE === 0 成立）。
 */

/** 阅读器 UI 状态（数值常量，等价数值枚举） */
export const ReaderUiState = {
  /** 全屏阅读，无工具栏 */
  IMMERSIVE: 0,
  /** 工具栏 + 底部导航可见 */
  TOOLBAR: 1,
  /** 设置抽屉打开 */
  SETTINGS: 2,
} as const

export type ReaderUiState = (typeof ReaderUiState)[keyof typeof ReaderUiState]

/** 阅读器交互动作（数值常量，等价数值枚举） */
export const ReaderAction = {
  /** 点击页面中央 */
  TapCenter: 0,
  /** 点击 ⋯ 按钮打开设置 */
  OpenSettings: 1,
  /** 关闭设置（× / 遮罩点击 / 下滑） */
  CloseSettings: 2,
  /** Android 返回键（Overlay Stack 原则：优先关闭最上层） */
  AndroidBack: 4,
  /** 移动端上滑隐藏工具栏 */
  SwipeUp: 5,
  /** 移动端下滑唤出工具栏 */
  SwipeDown: 6,
} as const

export type ReaderAction = (typeof ReaderAction)[keyof typeof ReaderAction]

/**
 * 状态转换表：TRANSITIONS[当前状态][动作] → 下一状态。
 *
 * 'EXIT' 为特殊哨兵值，表示退出 Reader（离开页面而非状态切换）。
 * 表中未列出的 (状态, 动作) 组合视为非法转换，dispatch 时应忽略。
 */
export const TRANSITIONS: Record<string, Record<string, ReaderUiState | 'EXIT'>> = {
  [ReaderUiState.IMMERSIVE]: {
    /** 全屏阅读中点击中央 → 唤出工具栏 */
    [ReaderAction.TapCenter]: ReaderUiState.TOOLBAR,
    /** 全屏阅读中按返回键 → 退出 Reader */
    [ReaderAction.AndroidBack]: 'EXIT',
    /** 沉浸阅读中下滑 → 唤出工具栏 */
    [ReaderAction.SwipeDown]: ReaderUiState.TOOLBAR,
  },
  [ReaderUiState.TOOLBAR]: {
    /** 工具栏可见时点击中央 → 收起工具栏回到全屏 */
    [ReaderAction.TapCenter]: ReaderUiState.IMMERSIVE,
    /** 点击 ⋯ 按钮 → 打开设置抽屉 */
    [ReaderAction.OpenSettings]: ReaderUiState.SETTINGS,
    /** 工具栏可见时按返回键 → 先收起工具栏（不退出） */
    [ReaderAction.AndroidBack]: ReaderUiState.IMMERSIVE,
    /** 工具栏可见时上滑 → 回到沉浸阅读 */
    [ReaderAction.SwipeUp]: ReaderUiState.IMMERSIVE,
    /** 下滑保持工具栏可见 */
    [ReaderAction.SwipeDown]: ReaderUiState.TOOLBAR,
  },
  [ReaderUiState.SETTINGS]: {
    /** 关闭设置抽屉 → 回到工具栏 */
    [ReaderAction.CloseSettings]: ReaderUiState.TOOLBAR,
    /** 设置打开时按返回键 → 关闭最上层 Overlay 回到工具栏 */
    [ReaderAction.AndroidBack]: ReaderUiState.TOOLBAR,
  },
}

/** useReaderToolbar 可选配置 */
export interface UseReaderToolbarOptions {
  /**
   * EXIT 哨兵回调。
   *
   * dispatch 查表命中 'EXIT'（即 IMMERSIVE 下 AndroidBack）时同步调用。
   * 状态机自身不做任何导航——路由跳转 / history 处理由 ReaderPage
   * 在此回调内完成；未提供时 EXIT 信号被静默忽略。
   */
  onExit?: () => void
}

/** useReaderToolbar 返回的控制句柄 */
export interface ReaderToolbarControls {
  /** 当前 UI 状态。只读语义：变更必须经由 dispatch，禁止直接赋值 */
  state: Ref<ReaderUiState>
  /** 按 TRANSITIONS 表派发动作，表中未定义的组合静默忽略 */
  dispatch: (action: ReaderAction) => void
  /** 工具栏层是否可见（TOOLBAR / SETTINGS 两态均为 true） */
  toolbarVisible: ComputedRef<boolean>
  /** 是否处于 TOOLBAR 态 */
  isToolbar: ComputedRef<boolean>
  /** 设置抽屉是否打开 */
  isSettings: ComputedRef<boolean>
}

/**
 * Reader 工具栏状态机 composable（设计规范 §3）。
 *
 * 唯一事实来源是 TRANSITIONS 表：dispatch 只查表迁移，绝不硬编码状态跳转。
 * 工具栏不会因无操作自动收起；进入沉浸态只通过点击中央、上滑或用户返回操作。
 */
export function useReaderToolbar(options: UseReaderToolbarOptions = {}): ReaderToolbarControls {
  /** 初始态：全屏沉浸阅读 */
  const state = ref<ReaderUiState>(ReaderUiState.IMMERSIVE)

  const dispatch = (action: ReaderAction): void => {
    const next = TRANSITIONS[state.value]?.[action]
    // 表中未定义 → 非法迁移，静默忽略
    if (next === undefined) {
      return
    }
    // EXIT 哨兵：仅通知外部，不改状态、不做导航
    if (next === 'EXIT') {
      options.onExit?.()
      return
    }
    state.value = next
  }

  const toolbarVisible = computed(() => state.value !== ReaderUiState.IMMERSIVE)
  const isToolbar = computed(() => state.value === ReaderUiState.TOOLBAR)
  const isSettings = computed(() => state.value === ReaderUiState.SETTINGS)

  return { state, dispatch, toolbarVisible, isToolbar, isSettings }
}
