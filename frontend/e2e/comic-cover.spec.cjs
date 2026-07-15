const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const RESULTS_FILE = path.join(SCREENSHOT_DIR, 'comic-cover-results.json');

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
    test: 'comic-cover',
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
    if (url.includes('/api/comics/') && url.includes('/covers/candidates')) {
      apiCalls.push({ method: req.method(), url });
    }
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
    console.log(`➡️  Navigating to ${TARGET_URL}/comics`);
    await page.goto(`${TARGET_URL}/comics`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(800);

    const firstComic = page.locator('.comic-poster').first();
    const comicCount = await firstComic.count();
    assert('漫画列表中存在漫画', comicCount > 0, `Found ${comicCount} comic element(s)`);

    if (comicCount === 0) {
      throw new Error('No comics available for testing');
    }

    await firstComic.click();
    await page.waitForURL(/\/comics\/\d+$/, { timeout: 10000 });
    await page.waitForTimeout(500);

    const detailUrl = page.url();
    const comicIdMatch = detailUrl.match(/\/comics\/(\d+)$/);
    assert('已进入漫画详情页', comicIdMatch !== null, `URL: ${detailUrl}`);

    // Open more menu
    const moreBtn = page.locator('.more-btn').first();
    assert('More 按钮存在', await moreBtn.count() > 0, `Found ${await moreBtn.count()}`);
    await moreBtn.click();
    await page.waitForTimeout(200);

    const coverMenuItem = page.locator('.menu-item').filter({ hasText: '更换封面' }).first();
    assert('更换封面菜单项存在', await coverMenuItem.count() > 0, `Found ${await coverMenuItem.count()}`);
    await coverMenuItem.click();
    await page.waitForTimeout(800);

    const dialog = page.locator('.cover-dialog').first();
    assert('封面选择对话框存在', await dialog.count() > 0, `Found ${await dialog.count()}`);

    const candidates = dialog.locator('.cover-item');
    const candidateCount = await candidates.count();
    assert('封面候选已加载', candidateCount > 0, `Found ${candidateCount} candidates`);

    if (candidateCount > 0) {
      await candidates.first().click();
      await page.waitForTimeout(200);

      const saveBtn = dialog.locator('.el-button--primary').filter({ hasText: '保存' }).first();
      assert('保存按钮存在', await saveBtn.count() > 0, `Found ${await saveBtn.count()}`);

      // Don't actually save to avoid changing state permanently
      const cancelBtn = dialog.locator('.el-button').filter({ hasText: '取消' }).first();
      await cancelBtn.click();
      await page.waitForTimeout(800);
      assert('取消后对话框关闭', await dialog.count() === 0, `Dialog count: ${await dialog.count()}`);
    }

    // Screenshot
    const coverShot = screenshotPath('comic-cover-page.png');
    await page.screenshot({ path: coverShot, fullPage: true });
    results.screenshots.push(coverShot);

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
    const failShot = screenshotPath('comic-cover-failure.png');
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
      console.log(`\n✅ comic-cover test PASSED (${results.assertions.length} assertions)`);
    } else {
      console.log(`\n❌ comic-cover test FAILED (${results.failures.length} failures)`);
    }

    await browser.close();
    process.exitCode = exitCode;
  }
})();
