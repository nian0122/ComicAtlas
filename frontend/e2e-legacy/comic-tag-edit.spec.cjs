const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const RESULTS_FILE = path.join(SCREENSHOT_DIR, 'comic-tag-edit-results.json');

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
  const results = {
    test: 'comic-tag-edit',
    targetUrl: TARGET_URL,
    timestamp: new Date().toISOString(),
    assertions: [],
    failures: [],
    screenshots: [],
    consoleErrors,
    pageErrors,
  };

  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push({ page: page.url(), text: msg.text() });
  });
  page.on('pageerror', (err) => {
    pageErrors.push({ page: page.url(), message: err.message });
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
    // 1. Navigate to comic library and pick the first comic
    console.log(`➡️  Navigating to ${TARGET_URL}/library`);
    await page.goto(`${TARGET_URL}/library`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(800);

    const firstComic = page.locator('.comic-poster').first();
    const comicCount = await firstComic.count();
    assert('漫画列表中存在漫画', comicCount > 0, `Found ${comicCount} comic element(s)`);

    if (comicCount === 0) {
      throw new Error('No comics available for testing');
    }

    await firstComic.click();
    await page.waitForURL(/\/comic\/\d+$/, { timeout: 10000 });
    await page.waitForTimeout(500);

    const detailUrl = page.url();
    const comicIdMatch = detailUrl.match(/\/comic\/(\d+)$/);
    assert('已进入漫画详情页', comicIdMatch !== null, `URL: ${detailUrl}`);

    // 2. Navigate to management edit page
    const editUrl = `${TARGET_URL}/manage/comics/${comicIdMatch[1]}/edit`;
    console.log(`➡️  Navigating to ${editUrl}`);
    await page.goto(editUrl, { waitUntil: 'networkidle', timeout: 10000 });
    await page.waitForTimeout(500);

    assert('已进入编辑页', page.url().includes('/edit'), `URL: ${page.url()}`);

    // 3. Add a new tag via the input
    const newTagInput = page.locator('.new-tag-input input').first();
    assert('新标签输入框存在', await newTagInput.count() > 0, `Found ${await newTagInput.count()}`);

    const newTagName = `测试标签-${Date.now()}`;
    await newTagInput.fill(newTagName);
    await page.waitForTimeout(300);

    const addBtn = page.locator('.tag-block .el-button').filter({ hasText: '添加' }).first();
    assert('添加按钮存在', await addBtn.count() > 0, `Found ${await addBtn.count()}`);
    await addBtn.click();
    await page.waitForTimeout(800);

    // Fallback: use Enter key if button click didn't add the tag
    if (await page.locator('.selected-tag').filter({ hasText: newTagName }).count() === 0) {
      await newTagInput.press('Enter');
      await page.waitForTimeout(800);
    }

    const selectedTag = page.locator('.selected-tag').filter({ hasText: newTagName });
    assert('新标签已显示在已选标签中', await selectedTag.count() > 0, `Found ${await selectedTag.count()}`);

    // 4. Save
    const saveBtn = page.locator('.form-actions .el-button--primary');
    assert('保存按钮存在', await saveBtn.count() > 0, `Found ${await saveBtn.count()}`);
    await saveBtn.first().click();

    await page.waitForURL(/\/comic\/\d+$/, { timeout: 10000 });
    await page.waitForTimeout(800);

    assert('保存后返回详情页', page.url() === detailUrl, `Expected ${detailUrl}, got ${page.url()}`);

    // 5. Assert tag appears on detail page
    const tagChip = page.locator('.tag-chip').filter({ hasText: newTagName });
    assert('详情页显示新增标签', await tagChip.count() > 0, `Found ${await tagChip.count()}`);

    // 6. Screenshot
    const editShot = screenshotPath('comic-tag-edit-page.png');
    await page.screenshot({ path: editShot, fullPage: true });
    results.screenshots.push(editShot);

    // 7. Verify no console/page errors
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
    const failShot = screenshotPath('comic-tag-edit-failure.png');
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
      console.log(`\n✅ comic-tag-edit test PASSED (${results.assertions.length} assertions)`);
    } else {
      console.log(`\n❌ comic-tag-edit test FAILED (${results.failures.length} failures)`);
    }

    await browser.close();
    process.exitCode = exitCode;
  }
})();
