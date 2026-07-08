import { test, expect } from '@playwright/test'

/**
 * Reader test: 进入 Reader 翻页，离开后 History 应更新
 * 依赖：Library 中至少有一本可阅读的漫画
 */
test('reader: flip pages and save progress', async ({ page }) => {
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
