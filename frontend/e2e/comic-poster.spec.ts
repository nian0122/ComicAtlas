import { test, expect } from '@playwright/test'

test('ComicPoster scales on hover', async ({ page }) => {
  await page.goto('/poster-test')

  const poster = page.locator('.comic-poster').first()
  await expect(poster).toBeVisible()

  await poster.hover()
  await page.waitForTimeout(300)

  const transform = await poster.evaluate(
    (el) => window.getComputedStyle(el).transform
  )

  expect(transform).toMatch(/1\.04/)
})
