const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const RESULTS_FILE = path.join(SCREENSHOT_DIR, 'admin-db-only-delete-results.json');

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
    test: 'admin-db-only-delete',
    targetUrl: `${TARGET_URL}/dashboard`,
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
    // 1. Navigate to Dashboard
    console.log(`➡️  Navigating to ${TARGET_URL}/dashboard`);
    await page.goto(`${TARGET_URL}/dashboard`, { waitUntil: 'networkidle', timeout: 30000 });
    await page.waitForTimeout(500);

    // 2. Assert "数据库维护" section heading exists
    const dbSection = page.locator('.maintenance-section').filter({ hasText: '数据库维护' });
    const sectionHeading = dbSection.locator('.section-title');
    const headingCount = await sectionHeading.count();
    assert(
      '"数据库维护" section heading exists',
      headingCount > 0,
      `Found ${headingCount} element(s)`
    );

    if (headingCount > 0) {
      const headingText = await sectionHeading.first().textContent();
      assert(
        '"数据库维护" heading text matches',
        headingText && headingText.includes('数据库维护'),
        `Text: "${headingText}"`
      );
    }

    // 3. Assert Comic ID input exists and is an el-input-number
    const inputNumber = dbSection.locator('.el-input-number');
    const inputCount = await inputNumber.count();
    assert(
      'Comic ID input (el-input-number) exists',
      inputCount > 0,
      `Found ${inputCount} el-input-number element(s)`
    );

    if (inputCount > 0) {
      // Verify it has the inner input that is part of el-input-number
      const innerInput = inputNumber.first().locator('.el-input__inner');
      const innerCount = await innerInput.count();
      assert(
        'el-input-number contains inner input (.el-input__inner)',
        innerCount > 0,
        `Found ${innerCount} inner input(s)`
      );
    }

    // 4. Assert "删除数据库记录" button exists
    const deleteBtn = dbSection.locator('.el-button--danger');
    const btnCount = await deleteBtn.count();
    assert(
      '"删除数据库记录" button exists',
      btnCount > 0,
      `Found ${btnCount} danger button(s)`
    );

    if (btnCount > 0) {
      const btnText = await deleteBtn.first().textContent();
      assert(
        '"删除数据库记录" button text matches',
        btnText && btnText.includes('删除数据库记录'),
        `Text: "${btnText}"`
      );

      // 5. Assert button is initially disabled (no comic loaded → canDelete is false)
      const isDisabled = await deleteBtn.first().isDisabled();
      assert(
        '删除按钮初始为禁用状态 (未加载漫画时)',
        isDisabled === true,
        `Disabled: ${isDisabled}`
      );
    }

    // 6. Assert the maintenance hint exists
    const hint = page.locator('.maintenance-hint');
    const hintCount = await hint.count();
    assert(
      '维护提示存在',
      hintCount > 0,
      `Found ${hintCount} hint element(s)`
    );

    if (hintCount > 0) {
      const hintText = await hint.first().textContent();
      assert(
        '维护提示包含"仅删除数据库记录"',
        hintText && hintText.includes('仅删除数据库记录'),
        `Text: "${hintText?.substring(0, 60)}..."`
      );
    }

    // 7. Screenshot of the dashboard maintenance section
    // Scroll the maintenance section into view first
    const maintenanceSection = dbSection;
    if (await maintenanceSection.count() > 0) {
      await maintenanceSection.scrollIntoViewIfNeeded();
      await page.waitForTimeout(300);
    }

    const dashboardShot = screenshotPath('admin-db-only-delete-dashboard.png');
    await page.screenshot({ path: dashboardShot, fullPage: true });
    results.screenshots.push(dashboardShot);
    console.log(`  📸 Dashboard screenshot saved: ${dashboardShot}`);

    // 8. Verify no console/page errors
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
    const failShot = screenshotPath('admin-db-only-delete-failure.png');
    await page.screenshot({ path: failShot, fullPage: true }).catch(() => {});
    results.screenshots.push(failShot);
    results.failures.push({ description: 'Uncaught exception', status: 'fail', detail: e.message });
    console.error(`\n❌ Test threw exception: ${e.message}`);
    exitCode = 1;
  } finally {
    // Determine overall result
    const passed = results.failures.length === 0;
    results.passed = passed;
    results.summary = `Assertions: ${results.assertions.length + results.failures.length}, Passed: ${results.assertions.length}, Failed: ${results.failures.length}`;

    fs.writeFileSync(RESULTS_FILE, JSON.stringify(results, null, 2));
    console.log(`\n📄 Results saved: ${RESULTS_FILE}`);

    if (passed) {
      console.log(`\n✅ admin-db-only-delete test PASSED (${results.assertions.length} assertions)`);
    } else {
      console.log(`\n❌ admin-db-only-delete test FAILED (${results.failures.length} failures)`);
    }

    await browser.close();
    process.exitCode = exitCode;
  }
})();
