import { test, expect } from '@playwright/test'

async function openReader(page: any) {
  await page.goto('/comics')
  const smallComicCard = page.locator('.comic-card').filter({ hasText: 'host-path-test' }).first()
  await expect(smallComicCard).toBeVisible()
  await smallComicCard.click()

  await expect(page).toHaveURL(/\/comics\/\d+/)
  const readBtn = page.locator('button:has-text("阅读")').first()
  await readBtn.click()

  await expect(page).toHaveURL(/\/comics\/\d+\/read/)
  await expect(page.locator('.reader-toolbar')).toBeVisible()
  await expect(page.locator('.reader-state')).not.toBeVisible()

  const indicator = page.locator('.page-indicator')
  await expect(indicator).toContainText(/\d+\s*\/\s*[1-9]\d*/)
  return indicator
}

/**
 * Reader test: 进入 Reader 翻页，离开后 History 应更新
 * 依赖：Library 中至少有一本可阅读的漫画
 */
test('reader: flip pages and save progress', async ({ page }) => {
  const indicator = await openReader(page)
  const startText = await indicator.textContent()
  const startMatch = startText?.match(/(\d+)\s*\/\s*(\d+)/)
  expect(startMatch).not.toBeNull()
  const startPage = parseInt(startMatch![1], 10)
  const totalPages = parseInt(startMatch![2], 10)
  expect(totalPages).toBeGreaterThanOrEqual(4)

  const endExpected = Math.min(startPage + 3, totalPages)
  for (let i = 0; i < 3 && startPage + i < totalPages; i++) {
    await page.keyboard.press('ArrowRight')
    await page.waitForTimeout(300)
  }
  await expect(indicator).toContainText(`${endExpected} /`)

  await page.waitForTimeout(2000)
  await page.goto('/history')
  await expect(page.locator('text=阅读中心')).toBeVisible()

  const targetHistory = page.locator('.history-card').filter({ hasText: 'host-path-test' }).first()
  await expect(targetHistory).toBeVisible()
  await expect(targetHistory.locator('.chapter-line')).toContainText(`${endExpected} /`)
})

/**
 * Reader test: Zoom 控制
 */
test('reader: zoom control changes scale', async ({ page }) => {
  await openReader(page)

  const zoomValue = page.locator('.zoom-value')
  await expect(zoomValue).toContainText('100%')

  await page.locator('.zoom-group button').filter({ hasText: '+' }).first().click()
  await expect(zoomValue).toContainText('125%')

  await page.locator('.zoom-group button').filter({ hasText: '-' }).first().click()
  await expect(zoomValue).toContainText('100%')

  // Ctrl+滚轮放大
  await page.keyboard.down('Control')
  await page.mouse.wheel(0, -100)
  await page.keyboard.up('Control')
  await page.waitForTimeout(200)
  await expect(zoomValue).toContainText('125%')
})

/**
 * Reader test: Fit 模式切换不崩溃
 */
test('reader: fit mode changes layout', async ({ page }) => {
  await openReader(page)

  const fitSelect = page.locator('.toolbar-right .el-select').nth(1)
  await fitSelect.click()
  await page.locator('.el-select-dropdown__item:has-text("适配高")').click()
  await page.waitForTimeout(300)

  await fitSelect.click()
  await page.locator('.el-select-dropdown__item:has-text("原始")').click()
  await page.waitForTimeout(300)

  const indicator = page.locator('.page-indicator')
  await expect(indicator).toContainText(/\d+\s*\/\s*\d+/)
})

/**
 * Reader test: 阅读方向切换
 */
test('reader: reading direction switching', async ({ page }) => {
  await openReader(page)

  const directionSelect = page.locator('.toolbar-right .el-select').nth(2)
  await directionSelect.click()
  await page.locator('.el-select-dropdown__item:has-text("横向")').click()
  await page.waitForTimeout(300)

  const indicator = page.locator('.page-indicator')
  await expect(indicator).toContainText(/\d+\s*\/\s*\d+/)

  await directionSelect.click()
  await page.locator('.el-select-dropdown__item:has-text("纵向")').click()
  await page.waitForTimeout(300)
  await expect(indicator).toContainText(/\d+\s*\/\s*\d+/)
})

/**
 * Reader test: 滚动驱动工具栏自动隐藏
 */
test('reader: toolbar auto-hide on scroll', async ({ page }) => {
  await openReader(page)

  const toolbar = page.locator('.reader-toolbar')
  await expect(toolbar).not.toHaveClass(/toolbar-hidden/)

  await page.mouse.wheel(0, 300)
  await page.waitForTimeout(200)
  await page.mouse.wheel(0, 200)
  await page.waitForTimeout(200)
  await expect(toolbar).toHaveClass(/toolbar-hidden/)

  await page.mouse.wheel(0, -200)
  await page.waitForTimeout(200)
  await page.mouse.wheel(0, -200)
  await page.waitForTimeout(200)
  await expect(toolbar).not.toHaveClass(/toolbar-hidden/)
})
