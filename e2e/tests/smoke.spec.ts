import { test, expect } from '@playwright/test'

/**
 * Smoke test: 验证最基础的路由和页面渲染
 * 首页 → Library → Comic Detail → Reader → 返回
 */
test('smoke: navigate through core pages', async ({ page }) => {
  // 1. 打开首页，应重定向到 /comics
  await page.goto('/')
  await expect(page).toHaveURL(/\/comics/)
  await expect(page.locator('h1.page-title:has-text("漫画库")')).toBeVisible()

  const smallComicCard = page.locator('.comic-card').filter({ hasText: 'host-path-test' }).first()
  if (await smallComicCard.count() > 0) {
    await smallComicCard.click()
    await expect(page).toHaveURL(/\/comics\/\d+/)
    await expect(page.locator('.comic-title')).toBeVisible()

    // 3. 点击开始/继续阅读进入 Reader
    const readBtn = page.locator('button:has-text("阅读")').first()
    await readBtn.click()
    await expect(page).toHaveURL(/\/comics\/\d+\/read/)
    await expect(page.locator('.reader-toolbar')).toBeVisible()

    // 4. 返回按钮回到 Detail
    await page.locator('.tool-btn').first().click()
    await expect(page).toHaveURL(/\/comics\/\d+/)
  }
})
