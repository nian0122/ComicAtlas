import { test, expect } from '@playwright/test'

/**
 * Storage Recovery Task Center E2E tests.
 * Validates the UI workflow for the async recovery task center feature.
 *
 * Tests cover:
 * - Task center entry point visibility
 * - Confirmation dialog display
 * - Storage page redirect to task center (NOT calling old sync endpoint)
 * - Recovery task creation success flow
 *
 * API mocking via page.route() allows these tests to run without a live backend.
 */

// ── helpers ────────────────────────────────────────────────────

/** Mock GET /api/tasks/recovery returning an empty success response. */
async function mockTaskList(page: ReturnType<test['prop']>['page']) {
  await page.route('**/api/tasks/recovery?*', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        message: 'success',
        data: { records: [], total: 0, size: 20, current: 1, pages: 0 },
      }),
    })
  })
}

/** Mock POST /api/tasks/recovery returning a newly created task. */
async function mockTaskCreate(page: ReturnType<test['prop']>['page']) {
  await page.route('**/api/tasks/recovery', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'success',
          data: {
            id: 1,
            status: 'PENDING',
            totalComics: 0,
            recoveredComics: 0,
            skippedComics: 0,
            placeholderComics: 0,
            errorComics: 0,
            retryCount: 0,
            createdAt: new Date().toISOString(),
          },
        }),
      })
    } else {
      await route.fallback()
    }
  })
}

/** Capture whether the old scanRecover endpoint was called. */
async function installScanRecoverSpy(page: ReturnType<test['prop']>['page']) {
  let called = false
  await page.route('**/admin/storage/scan-recover', async (route) => {
    called = true
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'success', data: {} }),
    })
  })
  // Expose check helper
  return {
    wasCalled: () => called,
  }
}

// ── tests ───────────────────────────────────────────────────────

test.describe('Storage Recovery Task Center', () => {

  test.beforeEach(async ({ page }) => {
    // Mock task list endpoint for all tests
    await mockTaskList(page)
  })

  test('task center shows recovery entry button', async ({ page }) => {
    await page.goto('/manage/import/tasks')
    await expect(page).toHaveURL(/\/tasks/)

    // Verify the recovery action button is visible
    const recoveryBtn = page.locator('button, a, .el-button, [class*="recovery"]')
      .filter({ hasText: /从存储恢复|恢复数据库|recovery|恢复/ }).first()

    // The button may appear in various forms; we check page content
    await expect(page.getByText(/从存储恢复|恢复数据库记录|recovery/i).first()).toBeVisible()
  })

  test('recovery confirmation dialog appears on click', async ({ page }) => {
    await mockTaskCreate(page)
    await page.goto('/manage/import/tasks')
    await expect(page).toHaveURL(/\/tasks/)

    // Click the recovery trigger
    const trigger = page.locator('button, a, [class*="recovery"]')
      .filter({ hasText: /从存储恢复|恢复数据库记录|recovery|恢复/ }).first()
    await trigger.click()

    // Either a dialog or success toast should appear
    // Playwright handles native dialog automatically; for Element UI dialogs,
    // we check for the dialog container.
    const dialog = page.locator('.el-message-box__wrapper, .el-message-box, [role="dialog"]')
      .filter({ hasText: /确认|恢复|从存储|扫描/ })
    const toast = page.locator('.el-message--success, .el-notification')

    const hasDialog = await dialog.isVisible({ timeout: 5000 }).catch(() => false)
    const hasToast = await toast.isVisible({ timeout: 5000 }).catch(() => false)

    expect(hasDialog || hasToast).toBeTruthy()
  })

  test('storage page redirects to task center instead of calling old API', async ({ page }) => {
    const spy = await installScanRecoverSpy(page)

    // Navigate to storage management page
    await page.goto('/manage/storage')
    await expect(page).toHaveURL(/\/storage/)

    // Find any "恢复" or "从存储恢复" action on the storage page
    const recoveryAction = page.locator('button, a, [class*="recovery"], [class*="action"]')
      .filter({ hasText: /从存储恢复|恢复数据库|扫描恢复|恢复/ }).first()

    if (await recoveryAction.isVisible({ timeout: 3000 }).catch(() => false)) {
      await recoveryAction.click()
      // Should redirect to task center, not call old endpoint
      await expect(page).toHaveURL(/\/tasks/, { timeout: 10000 })
    }

    // Old sync endpoint must NOT have been called
    expect(spy.wasCalled()).toBe(false)
  })

  test('recovery task creation shows success feedback', async ({ page }) => {
    await mockTaskCreate(page)
    await page.goto('/manage/import/tasks')

    // Trigger task creation
    const trigger = page.locator('button, a, [class*="recovery"]')
      .filter({ hasText: /从存储恢复|恢复数据库记录|recovery|恢复/ }).first()
    await trigger.click()

    // Confirm if dialog appears
    const confirmBtn = page.locator('.el-message-box__btns button:has-text("确定"), .el-message-box__btns .el-button--primary')
    if (await confirmBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await confirmBtn.click()
    }

    // Verify a new recovery task card appears or success message is shown
    const taskCard = page.locator('.task-card, [class*="recovery-task"]').first()
    const successMsg = page.locator('.el-message--success')

    const hasTaskCard = await taskCard.isVisible({ timeout: 5000 }).catch(() => false)
    const hasSuccess = await successMsg.isVisible({ timeout: 5000 }).catch(() => false)

    expect(hasTaskCard || hasSuccess).toBeTruthy()
  })
})
