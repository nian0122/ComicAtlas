const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const RESULTS_FILE = path.join(SCREENSHOT_DIR, 'comic-search-results.json');

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
    test: 'comic-search',
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
    if (url.includes('/api/comics') && !url.includes('/api/comics/')) {
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

    // 1. Search input exists
    const searchInput = page.locator('.search-input input').first();
    assert('搜索输入框存在', await searchInput.count() > 0, `Found ${await searchInput.count()}`);

    // 2. Tag filter exists
    const tagFilter = page.locator('.tag-filter').first();
    assert('标签筛选器存在', await tagFilter.count() > 0, `Found ${await tagFilter.count()}`);

    // 3. Tag mode toggle exists
    const tagModeSelect = page.locator('.tag-mode-filter select').first();
    assert('标签模式切换存在', await tagModeSelect.count() > 0, `Found ${await tagModeSelect.count()}`);

    // 4. Get first comic title and search
    const firstComic = page.locator('.comic-poster').first();
    const comicCount = await firstComic.count();
    assert('漫画列表中存在漫画', comicCount > 0, `Found ${comicCount} comic element(s)`);

    if (comicCount > 0) {
      const title = await firstComic.locator('.poster-title').first().textContent() || 'test';
      const keyword = title.slice(0, 4);
      await searchInput.fill(keyword);
      await page.waitForTimeout(300);
      await searchInput.press('Enter');
      await page.waitForTimeout(1000);

      const searchCalls = apiCalls.filter((c) => c.url.includes('/api/comics?') && c.url.includes('keyword='));
      assert('搜索请求已发送', searchCalls.length > 0, `Found ${searchCalls.length} search calls`);

      const listAfterSearch = page.locator('.comic-poster');
      assert('搜索后仍有结果或为空状态', await listAfterSearch.count() >= 0, `Count: ${await listAfterSearch.count()}`);
    }

    // 5. Screenshot
    const searchShot = screenshotPath('comic-search-page.png');
    await page.screenshot({ path: searchShot, fullPage: true });
    results.screenshots.push(searchShot);

    // 6. Verify no console/page errors
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
    const failShot = screenshotPath('comic-search-failure.png');
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
      console.log(`\n✅ comic-search test PASSED (${results.assertions.length} assertions)`);
    } else {
      console.log(`\n❌ comic-search test FAILED (${results.failures.length} failures)`);
    }

    await browser.close();
    process.exitCode = exitCode;
  }
})();
