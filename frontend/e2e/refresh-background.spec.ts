import { expect, test } from '@playwright/test'

test('刷新提交阶段保持暗色背景避免白屏闪烁', async ({ page }) => {
  await page.goto('/manage/storage', { waitUntil: 'commit' })
  await expect.poll(async () => page.evaluate(() => document.querySelector('#critical-theme')?.textContent ?? '')).toContain('#080808')
  expect(await page.evaluate(() => ({
    body: document.body.getBoundingClientRect().height,
    app: document.querySelector('#app')?.getBoundingClientRect().height ?? 0,
    viewport: window.innerHeight,
  }))).toMatchObject({ viewport: 720, body: 720, app: 720 })
  await page.reload({ waitUntil: 'commit' })
  await expect.poll(async () => page.evaluate(() => document.querySelector('#critical-theme')?.textContent ?? '')).toContain('#080808')
  expect(await page.evaluate(() => ({
    body: document.body.getBoundingClientRect().height,
    app: document.querySelector('#app')?.getBoundingClientRect().height ?? 0,
    viewport: window.innerHeight,
  }))).toMatchObject({ viewport: 720, body: 720, app: 720 })
})
