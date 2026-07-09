# 工具栏自动隐藏（滚动驱动）

**日期**：2026-07-09
**范围**：Phase II 最小 demo 第一部分
**关联**：`frontend/src/components/reader/ReaderViewport.vue`

---

## 目标

阅读过程中向下翻页时自动隐藏顶部工具栏，增加可阅读区域；向上回翻时自动显示，便于操作。

---

## 行为规则

| 条件 | 行为 |
|------|------|
| 向下滚动累计 ≥ 50px | 隐藏工具栏（`settings.showToolbar = false`） |
| 向上滚动累计 ≥ 50px | 显示工具栏（`settings.showToolbar = true`） |
| scrollTop < 50px（接近顶部） | 始终显示工具栏 |
| 方向改变 | 累计距离清零 |
| 300ms 无滚动 | 累计距离清零 |

---

## 技术方案

### 改动文件

`frontend/src/components/reader/ReaderViewport.vue`

### 新增状态

```ts
let lastScrollTop = 0
let scrollAccumulator = 0
let lastScrollDirection: 'up' | 'down' | null = null
let accumulatorTimer: number | null = null
```

### onScroll 逻辑

在现有 `onScroll` 函数开头（`isProgrammaticScroll` 检查之前）增加：

```
1. 获取 currentScrollTop
2. 如果 currentScrollTop < 50 → 重置状态，强制显示工具栏，return
3. 计算 delta = currentScrollTop - lastScrollTop
4. 判断方向：delta > 0 为 'down'，delta < 0 为 'up'
5. 如果方向改变 → reset accumulator 和 direction
6. scrollAccumulator += Math.abs(delta)
7. 如果 scrollAccumulator >= 50：
   - 方向='down' → settings.showToolbar = false
   - 方向='up'   → settings.showToolbar = true
   - reset accumulator
8. reset 300ms timer（超时后清零 accumulator）
9. lastScrollTop = currentScrollTop
```

### 关键细节

- **不拦截手动触发**：`settings.toggleToolbar()` 仍可通过 Setting 菜单手动切换，手动切换后自动隐藏逻辑暂停（一次 showToolbar 手动变更后，等下次方向改变再重新开始自动逻辑）。  
  简化实现：手动触发后直接重置 accumulator，避免冲突。
- **`isProgrammaticScroll`**：键盘翻页触发的滚动已标记，会被 `onScroll` 跳过，不触发显隐。
- **`onBeforeUnmount`**：清除 `accumulatorTimer`。

---

## 不改动的

- `ReaderSettingsStore`：已有 `showToolbar` 和 `toggleToolbar()`
- `ReaderToolbar.vue`：已有 `toolbar-hidden` CSS class
- `ReaderPage.vue`：不感知此功能

---

## 验收标准

- [ ] 向下翻页持续滚动 → 工具栏淡出隐藏
- [ ] 向上翻页持续滚动 → 工具栏淡入显示
- [ ] 回到顶部时工具栏始终可见
- [ ] 键盘翻页不触发自动显隐
- [ ] Setting 菜单手动切换仍有效
- [ ] 组件销毁时 timer 被清除

---

## 风险

- 与 RecycleScroller 的 `onScroll` 共用一个处理函数，需注意不破坏现有 `deriveCurrentPage` 逻辑。
- `scrollTop < 50` 的阈值可能与 content 较短的情况冲突（但漫画章节通常有足够高度）。
