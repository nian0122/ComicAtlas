const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const RESULTS_FILE = path.join(SCREENSHOT_DIR, 'storage-scan-results.json');

function screenshotPath(name) {
  return path.join(SCREENSHOT_DIR, name);
}

(async () => {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

  const browser = await chromium.launch({ headless: HEADLESS });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  const consoleErrors = [];
  const pageErrors = [];
  const apiCalls = [];
  const results = {
    test: 'storage-scan',
    targetUrl: TARGET_URL,
    timestamp: new Date().toISOString(),
    assertions: [],
    failures: [],
    screenshots: [],
    consoleErrors,
    pageErrors,
    apiCalls,
  };

  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push({ page: page.url(), text: msg.text() });
  });
  page.on('pageerror', (err) => {
    pageErrors.push({ page: page.url(), message: err.message });
  });
  page.on('request', (req) => {
    const url = req.url();
    if (url.includes('/api/admin/storage/scan-recover')) {
      apiCalls.push({ method: req.method(), url });
    }
  });

  await page.route('**/api/admin/storage/scan-recover', async (route) => {
    const request = route.request();
    console.log(`🛡️ Intercepted scan request: ${request.method()} ${request.url()}`);
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          scannedComics: 3,
          existingComics: 1,
          restoredComics: 1,
          placeholderComics: 1,
          restoredChapters: 2,
          restoredPages: 20,
          placeholders: ['漫画 999999'],
          errors: [],
        },
        message: 'ok',
      }),
    });
  });

  function assert(description, condition, detail) {
    if (condition) {
      results.assertions.push({ description, status: 'pass', detail });
      console.log(`  ✅ ${description}`);
    } else {
      results.failures.push({ description, status: 'fail', detail });
      console.log(`  ❌ ${description}`);
    }
  }

  let exitCode = 0;

  try {
    console.log(`➡️  Navigating to ${TARGET_URL}/dashboard`);
    await page.goto(`${TARGET_URL}/dashboard`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(800);

    const scanSection = page.locator('.maintenance-section').filter({ hasText: '存储扫描恢复' }).first();
    assert('存储扫描恢复区块存在', await scanSection.count() > 0, `Found ${await scanSection.count()}`);

    const scanBtn = scanSection.locator('button').filter({ hasText: '开始扫描' }).first();
    assert('开始扫描按钮存在', await scanBtn.count() > 0, `Found ${await scanBtn.count()}`);

    await scanBtn.click();
    await page.waitForTimeout(2000);

    assert('扫描 API 已调用', apiCalls.length > 0, `Found ${apiCalls.length} scan calls`);

    const scanResult = scanSection.locator('.scan-stats').first();
    assert('扫描结果已显示', await scanResult.count() > 0, `Found ${await scanResult.count()}`);

    // Verify placeholder list and actions
    const placeholderItem = scanSection.locator('.placeholder-item').first();
    assert('占位漫画列表项存在', await placeholderItem.count() > 0, `Found ${await placeholderItem.count()}`);

    const editBtn = placeholderItem.locator('button').filter({ hasText: '编辑' }).first();
    assert('占位漫画编辑按钮存在', await editBtn.count() > 0, `Found ${await editBtn.count()}`);

    const deleteBtn = placeholderItem.locator('button').filter({ hasText: '删除' }).first();
    assert('占位漫画删除按钮存在', await deleteBtn.count() > 0, `Found ${await deleteBtn.count()}`);

    // Verify clicking edit navigates to edit page
    await editBtn.click();
    try {
      await page.waitForURL('**/comics/999999/edit', { timeout: 5000 });
      assert('编辑按钮跳转到漫画编辑页', true, `URL: ${page.url()}`);
    } catch (e) {
      assert('编辑按钮跳转到漫画编辑页', false, `URL: ${page.url()}, error: ${e.message}`);
    }

    // Screenshot
    const scanShot = screenshotPath('storage-scan-page.png');
    await page.screenshot({ path: scanShot, fullPage: true });
    results.screenshots.push(scanShot);

    // Verify no console/page errors
    if (consoleErrors.length > 0) {
      results.failures.push({
        description: 'No console errors',
        status: 'fail',
        detail: JSON.stringify(consoleErrors),
      });
      console.log(`  ❌ Console errors detected: ${consoleErrors.length}`);
    } else {
      results.assertions.push({ description: 'No console errors', status: 'pass', detail: null });
      console.log('  ✅ No console errors');
    }

    if (pageErrors.length > 0) {
      results.failures.push({
        description: 'No page errors',
        status: 'fail',
        detail: JSON.stringify(pageErrors),
      });
      console.log(`  ❌ Page errors detected: ${pageErrors.length}`);
    } else {
      results.assertions.push({ description: 'No page errors', status: 'pass', detail: null });
      console.log('  ✅ No page errors');
    }

  } catch (e) {
    const failShot = screenshotPath('storage-scan-failure.png');
    await page.screenshot({ path: failShot, fullPage: true }).catch(() => {});
    results.screenshots.push(failShot);
    results.failures.push({ description: 'Uncaught exception', status: 'fail', detail: e.message });
    console.error(`\n❌ Test threw exception: ${e.message}`);
    exitCode = 1;
  } finally {
    const passed = results.failures.length === 0;
    results.passed = passed;
    results.summary = `Assertions: ${results.assertions.length + results.failures.length}, Passed: ${results.assertions.length}, Failed: ${results.failures.length}`;

    fs.writeFileSync(RESULTS_FILE, JSON.stringify(results, null, 2));
    console.log(`\n📄 Results saved: ${RESULTS_FILE}`);

    if (passed) {
      console.log(`\n✅ storage-scan test PASSED (${results.assertions.length} assertions)`);
    } else {
      console.log(`\n❌ storage-scan test FAILED (${results.failures.length} failures)`);
    }

    await browser.close();
    process.exitCode = exitCode;
  }
})();
