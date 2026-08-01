import { test, expect } from '@playwright/test'

/**
 * Import workflow test: 创建导入任务并在 Task Center 看到
 * 注意：需要真实存在 sourcePath 才能 SUCCESS；本测试主要验证表单和跳转。
 */
test('import: create a DIRECTORY import task', async ({ page }) => {
  await page.goto('/import')
  await expect(page.locator('text=导入漫画')).toBeVisible()

  // 选择 DIRECTORY
  await page.locator('label:has-text("本地目录")').click()

  // 输入路径
  await page.locator('.path-input').fill('D:/manga/hq/test-comic')

  // 提交
  await page.locator('button:has-text("开始导入")').click()

  // 应跳转到 Task Center
  await expect(page).toHaveURL(/\/tasks/)
  await expect(page.locator('text=任务中心')).toBeVisible()

  // 至少出现一个新任务卡
  await expect(page.locator('.task-card').first()).toBeVisible()
})
