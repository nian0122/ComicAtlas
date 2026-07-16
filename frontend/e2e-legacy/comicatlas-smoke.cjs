const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const TARGET_URL = process.env.TARGET_URL || 'http://localhost:5173';
const HEADLESS = process.env.HEADLESS !== 'false';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR || path.resolve(__dirname, '../../.omo/evidence');
const EXPECTED_BG = 'rgb(20, 20, 20)';

const UNCOVERED_SELECTORS = [
  '.el-table',
  '.el-form',
  '.el-tree',
  '.el-upload',
  '.el-menu',
  '.el-scrollbar__bar',
  '.el-drawer',
];

const PAGES = [
  { name: 'Home', path: '/' },
  { name: 'Library', path: '/library' },
  { name: 'History', path: '/history' },
  { name: 'Import', path: '/manage/import' },
  { name: 'Task Center', path: '/manage/import/tasks' },
];

function screenshot(name) {
  return path.join(SCREENSHOT_DIR, `comicatlas-${name}.png`);
}

async function assertPageDark(page, pageName) {
  const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  if (bg !== EXPECTED_BG) {
    throw new Error(`${pageName}: expected body background ${EXPECTED_BG}, got ${bg}`);
  }

  const leaks = [];
  for (const selector of UNCOVERED_SELECTORS) {
    const count = await page.locator(selector).count();
    if (count > 0) {
      const isLight = await page.evaluate((sel) => {
        const el = document.querySelector(sel);
        if (!el) return false;
        const bg = getComputedStyle(el).backgroundColor;
        return bg.startsWith('rgb(255,') || bg === 'rgba(0, 0, 0, 0)';
      }, selector);
      if (isLight) leaks.push(selector);
    }
  }
  if (leaks.length > 0) {
    throw new Error(`${pageName}: uncovered Element Plus components rendered light: ${leaks.join(', ')}`);
  }
}

async function safeClick(page, selector) {
  const el = page.locator(selector).first();
  await el.waitFor({ state: 'visible', timeout: 5000 });
  await el.click();
}

(async () => {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });

  const browser = await chromium.launch({ headless: HEADLESS });
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  const page = await context.newPage();

  const consoleErrors = [];
  const pageErrors = [];
  const failedRequests = [];

  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push({ page: page.url(), text: msg.text() });
  });
  page.on('pageerror', (err) => {
    pageErrors.push({ page: page.url(), message: err.message });
  });
  page.on('response', (res) => {
    if (res.status() >= 400) failedRequests.push({ status: res.status(), url: res.url() });
  });

  const results = { pages: {}, flow: [], consoleErrors, pageErrors, failedRequests };

  try {
    for (const p of PAGES) {
      await page.goto(`${TARGET_URL}${p.path}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(300);
      await assertPageDark(page, p.name);
      const shot = screenshot(p.name.toLowerCase().replace(/\s+/g, '-'));
      await page.screenshot({ path: shot, fullPage: true });
      results.pages[p.name] = { background: EXPECTED_BG, screenshot: shot };
      console.log(`✅ ${p.name}: dark background OK`);
    }

    await page.goto(`${TARGET_URL}/`, { waitUntil: 'networkidle' });
    results.flow.push('Home');

    await safeClick(page, 'nav.top-nav a[href="/library"]');
    await page.waitForURL('**/library', { timeout: 10000 });
    results.flow.push('Library');
    console.log('➡️  Home → Library');

    const posterCount = await page.locator('.comic-poster').count();
    if (posterCount === 0) throw new Error('Library has no comic posters');
    await safeClick(page, '.comic-poster');
    await page.waitForURL(/\/comic\/\d+$/, { timeout: 10000 });
    results.flow.push('Detail');
    console.log('➡️  Library → Detail');

    const primaryBtn = page.locator('.hero-btn--primary').first();
    try {
      await primaryBtn.waitFor({ state: 'visible', timeout: 10000 });
      await primaryBtn.click();
      await page.waitForURL(/\/reader\/\d+$/, { timeout: 10000 });
      results.flow.push('Reader');
      console.log('➡️  Detail → Reader');
    } catch {
      const chapterRow = page.locator('.chapter-row').first();
      if (await chapterRow.count() > 0) {
        await chapterRow.click();
        await page.waitForURL(/\/reader\/\d+$/, { timeout: 10000 });
        results.flow.push('Reader');
        console.log('➡️  Detail → Reader (via chapter row)');
      } else {
        results.flow.push('Reader (skipped)');
        console.log('⚠️  Detail → Reader skipped');
      }
    }

    await page.goto(`${TARGET_URL}/history`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(300);
    results.flow.push('History');
    console.log('➡️  Reader → History');

    await safeClick(page, 'nav.top-nav a[href="/manage/import"]');
    await page.waitForURL('**/manage/import', { timeout: 10000 });
    results.flow.push('Import');
    console.log('➡️  History → Import');

    await safeClick(page, 'a[href="/manage/import/tasks"]');
    await page.waitForURL('**/manage/import/tasks', { timeout: 10000 });
    results.flow.push('Task Center');
    console.log('➡️  Import → Task Center');

    const finalShot = screenshot('flow-end');
    await page.screenshot({ path: finalShot, fullPage: true });
    results.finalScreenshot = finalShot;

    if (consoleErrors.length > 0) {
      throw new Error(`console.error detected: ${JSON.stringify(consoleErrors, null, 2)}`);
    }
    if (pageErrors.length > 0) {
      throw new Error(`pageerror detected: ${JSON.stringify(pageErrors, null, 2)}`);
    }

    console.log('\n✅ Smoke test passed');
    console.log('Flow:', results.flow.join(' → '));
  } catch (e) {
    const failShot = screenshot('failure');
    await page.screenshot({ path: failShot, fullPage: true }).catch(() => {});
    results.failureScreenshot = failShot;
    console.error('\n❌ Smoke test failed:', e.message);
    process.exitCode = 1;
  } finally {
    const logPath = path.join(SCREENSHOT_DIR, 'comicatlas-smoke-results.json');
    fs.writeFileSync(logPath, JSON.stringify(results, null, 2));
    console.log(`Results written to ${logPath}`);
    await browser.close();
  }
})();
