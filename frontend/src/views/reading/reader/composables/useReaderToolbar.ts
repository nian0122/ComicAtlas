import { computed, ref, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { useAutoHide } from './useAutoHide'

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
  /** 设置抽屉打开（暂停自动隐藏计时） */
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
  /** 4s 无操作超时 */
  AutoHideTimeout: 3,
  /** Android 返回键（Overlay Stack 原则：优先关闭最上层） */
  AndroidBack: 4,
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
  },
  [ReaderUiState.TOOLBAR]: {
    /** 工具栏可见时点击中央 → 收起工具栏回到全屏 */
    [ReaderAction.TapCenter]: ReaderUiState.IMMERSIVE,
    /** 4s 无操作超时 → 自动收起工具栏 */
    [ReaderAction.AutoHideTimeout]: ReaderUiState.IMMERSIVE,
    /** 点击 ⋯ 按钮 → 打开设置抽屉 */
    [ReaderAction.OpenSettings]: ReaderUiState.SETTINGS,
    /** 工具栏可见时按返回键 → 先收起工具栏（不退出） */
    [ReaderAction.AndroidBack]: ReaderUiState.IMMERSIVE,
  },
  [ReaderUiState.SETTINGS]: {
    /** 关闭设置抽屉 → 回到工具栏（重启自动隐藏计时） */
    [ReaderAction.CloseSettings]: ReaderUiState.TOOLBAR,
    /** 设置打开时按返回键 → 关闭最上层 Overlay 回到工具栏 */
    [ReaderAction.AndroidBack]: ReaderUiState.TOOLBAR,
  },
}

/** useReaderToolbar 可选配置 */
export interface UseReaderToolbarOptions {
  /** 工具栏无操作自动隐藏毫秒数，透传给 useAutoHide，默认 4000 */
  autoHideTimeout?: number
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
 * 内部组合 useAutoHide 执行自动隐藏副作用（按迁移后的新状态触发）：
 * - 进入 TOOLBAR：常规路径 autoHide.show()（重启完整倒计时）；
 *   CloseSettings 迁移改用 autoHide.resume()，与 SETTINGS 期间的 pause() 配对
 * - 进入 IMMERSIVE：autoHide.hide()
 * - 进入 SETTINGS：autoHide.pause()（抽屉打开期间冻结倒计时）
 *
 * 超时接线：sync watch 监听 autoHide.visible，翻转为 false 且当前仍处于
 * TOOLBAR 态时派发 AutoHideTimeout（TOOLBAR → IMMERSIVE）。
 *
 * 防死循环设计：dispatch 先写 state.value、后执行副作用，因此进入
 * IMMERSIVE 时 hide() 触发的 watch 回调读到的已是 IMMERSIVE，守卫不成立；
 * 超时派发路径中 hide() 再次置 false 属于同值写入，不会重复触发 watch。
 */
export function useReaderToolbar(options: UseReaderToolbarOptions = {}): ReaderToolbarControls {
  /** 初始态：全屏沉浸阅读 */
  const state = ref<ReaderUiState>(ReaderUiState.IMMERSIVE)
  const autoHide = useAutoHide(options.autoHideTimeout)

  /** 迁移完成后的副作用（依据动作 + 新状态，规则见函数头注释） */
  const applySideEffects = (action: ReaderAction, next: ReaderUiState): void => {
    switch (next) {
      case ReaderUiState.TOOLBAR:
        if (action === ReaderAction.CloseSettings) {
          autoHide.resume()
        } else {
          autoHide.show()
        }
        break
      case ReaderUiState.IMMERSIVE:
        autoHide.hide()
        break
      case ReaderUiState.SETTINGS:
        autoHide.pause()
        break
    }
  }

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
    // 先写状态、后跑副作用——该顺序是防死循环的关键（见函数头注释）
    state.value = next
    applySideEffects(action, next)
  }

  // 自动隐藏倒计时到点：visible 翻 false 且仍在 TOOLBAR 态 → 派发超时动作。
  // sync flush 保证超时迁移即时完成，不依赖组件渲染周期。
  watch(
    autoHide.visible,
    (visible) => {
      if (!visible && state.value === ReaderUiState.TOOLBAR) {
        dispatch(ReaderAction.AutoHideTimeout)
      }
    },
    { flush: 'sync' },
  )

  const toolbarVisible = computed(() => state.value !== ReaderUiState.IMMERSIVE)
  const isToolbar = computed(() => state.value === ReaderUiState.TOOLBAR)
  const isSettings = computed(() => state.value === ReaderUiState.SETTINGS)

  return { state, dispatch, toolbarVisible, isToolbar, isSettings }
}
