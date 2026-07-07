import { test, expect } from '@playwright/test'

/**
 * History / Reading Center test
 */
test('history: continue reading restores page', async ({ page }) => {
  await page.goto('/history')
  await expect(page.locator('text=阅读中心')).toBeVisible()

  const firstCard = page.locator('.history-card').first()
  if (await firstCard.count() === 0) {
    test.skip('No history records available')
  }

  // 记录当前页码
  const chapterLine = await firstCard.locator('.chapter-line').textContent()
  const pageMatch = chapterLine?.match(/(\d+)\s*\/\s*\d+\s*页/)
  const expectedPage = pageMatch ? parseInt(pageMatch[1], 10) : 1

  // 点击继续阅读
  await firstCard.locator('button:has-text("继续阅读")').click()

  await expect(page).toHaveURL(/\/comics\/\d+\/read/)
  await expect(page.locator('.page-indicator')).toContainText(`${expectedPage} /`)
})
