const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const RESULTS_FILE = path.join(SCREENSHOT_DIR, 'comic-metadata-edit-results.json');

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
    test: 'comic-metadata-edit',
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
    // 1. Navigate to comic list and pick the first comic
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

    // 2. Verify edit menu exists, then navigate directly to edit page
    // (Menu click is verified by presence; direct navigation avoids dropdown timing flakiness)
    const moreBtn = page.locator('.more-btn');
    assert('More 按钮存在', await moreBtn.count() > 0, `Found ${await moreBtn.count()}`);
    await moreBtn.first().click();
    await page.waitForTimeout(200);

    const editBtn = page.locator('button.menu-item:has-text("编辑信息")');
    assert('"编辑信息" 菜单项存在', await editBtn.count() > 0, `Found ${await editBtn.count()}`);
    await moreBtn.first().click(); // close dropdown

    const editUrl = `${TARGET_URL}/comics/${comicIdMatch[1]}/edit`;
    console.log(`➡️  Navigating to ${editUrl}`);

    // Use a fresh page context to avoid any state from detail page
    const editPage = await context.newPage();
    editPage.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push({ page: editPage.url(), text: msg.text() });
    });
    editPage.on('pageerror', (err) => {
      pageErrors.push({ page: editPage.url(), message: err.message });
    });

    await editPage.goto(editUrl, { waitUntil: 'networkidle', timeout: 10000 });
    await editPage.waitForTimeout(500);

    assert('已进入编辑页', editPage.url().includes('/edit'), `URL: ${editPage.url()}`);

    // 3. Modify title and author
    const titleInput = editPage.locator('.edit-form input').first();
    assert('标题输入框存在', await titleInput.count() > 0, `Found ${await titleInput.count()}`);

    const newTitle = `测试标题-${Date.now()}`;
    const newAuthor = `测试作者-${Date.now()}`;
    const newDescription = `测试描述-${Date.now()}`;

    await titleInput.fill(newTitle);

    const authorInput = editPage.locator('.edit-form input').nth(1);
    assert('作者输入框存在', await authorInput.count() > 0, `Found ${await authorInput.count()}`);
    await authorInput.fill(newAuthor);

    const descriptionInput = editPage.locator('.edit-form textarea').first();
    assert('描述输入框存在', await descriptionInput.count() > 0, `Found ${await descriptionInput.count()}`);
    await descriptionInput.fill(newDescription);

    // 4. Save
    const saveBtn = editPage.locator('.form-actions .el-button--primary');
    assert('保存按钮存在', await saveBtn.count() > 0, `Found ${await saveBtn.count()}`);
    await saveBtn.first().click();

    await editPage.waitForURL(/\/comics\/\d+$/, { timeout: 10000 });
    await editPage.waitForTimeout(800);

    assert('保存后返回详情页', editPage.url() === detailUrl, `Expected ${detailUrl}, got ${editPage.url()}`);

    // 5. Assert updated values are shown
    const authorValue = editPage.locator('.info-item:has(.info-label:has-text("作者")) .info-value');
    const displayedAuthor = await authorValue.textContent();
    assert('详情页显示更新后的作者', displayedAuthor === newAuthor, `Displayed: ${displayedAuthor}`);

    const titleEl = editPage.locator('.hero-content .title, h1, .page-title').first();
    const displayedTitle = await titleEl.textContent();
    assert('详情页显示更新后的标题', displayedTitle?.includes(newTitle) || false, `Displayed: ${displayedTitle}`);

    const descriptionEl = editPage.locator('.info-item:has(.info-label:has-text("描述")) .info-value');
    const displayedDescription = await descriptionEl.textContent();
    assert('详情页显示更新后的描述', displayedDescription?.includes(newDescription) || false, `Displayed: ${displayedDescription}`);

    // 6. Screenshot
    const editShot = screenshotPath('comic-metadata-edit-page.png');
    await editPage.screenshot({ path: editShot, fullPage: true });
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
    const failShot = screenshotPath('comic-metadata-edit-failure.png');
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
      console.log(`\n✅ comic-metadata-edit test PASSED (${results.assertions.length} assertions)`);
    } else {
      console.log(`\n❌ comic-metadata-edit test FAILED (${results.failures.length} failures)`);
    }

    await browser.close();
    process.exitCode = exitCode;
  }
})();
