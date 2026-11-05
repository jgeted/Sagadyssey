const { chromium } = require("C:/Users/jgeted/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright");
const { pathToFileURL } = require("url");
const path = require("path");

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
  });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });

  const demoUrl = pathToFileURL(path.resolve(__dirname, "index.html")).href;
  const shots = "C:/Users/jgeted/.codex/visualizations/2026/09/19/01a0b9e6-c645-7e02-98be-3c47889beeae";
  await page.goto(demoUrl);
  await page.locator("#view-person.active").waitFor();
  await page.waitForTimeout(350);
  await page.screenshot({ path: `${shots}/world-v2-person.png`, fullPage: true });

  await page.locator('[data-view="social"]').click();
  await page.locator("#view-social.active").waitFor();
  await page.waitForTimeout(350);
  await page.screenshot({ path: `${shots}/world-v2-social.png`, fullPage: true });

  await page.locator('[data-view="economy"]').click();
  await page.locator("#view-economy.active").waitFor();
  await page.waitForTimeout(350);
  await page.screenshot({ path: `${shots}/world-v2-economy.png`, fullPage: true });

  await page.locator('[data-view="person"]').click();
  await page.locator("#talkAction").click();
  await page.locator("#tradeAction").click();
  await page.locator("#tradeModal:not([hidden])").waitFor();
  await page.locator("#commitTrade").click();
  await page.locator("#contractAction").click();
  await page.locator('[data-view="economy"]').click();
  await page.locator("#acceptOrderAction").click();
  await page.locator("#view-combat.active").waitFor();
  await page.waitForTimeout(350);
  await page.screenshot({ path: `${shots}/world-v2-combat.png`, fullPage: true });

  await page.locator('[data-order="escort"]').click();
  await page.locator('[data-view="social"]').click();
  await page.waitForTimeout(350);
  await page.screenshot({ path: `${shots}/world-v2-outcome.png`, fullPage: true });

  const result = {
    flow: (await page.locator("#socialConsequence").textContent()).includes("护送履约") ? "passed" : "failed",
    north: await page.locator("#northStockMetric").textContent(),
    east: await page.locator("#eastStockMetric").textContent(),
    combatResolved: await page.locator("#afterAction").textContent(),
    systemGlimpses: await page.locator("#systemGlimpses .system-glimpse").count(),
    consoleErrors: errors
  };
  await page.setViewportSize({ width: 1180, height: 760 });
  await page.reload();
  await page.locator("#view-person.active").waitFor();
  await page.waitForTimeout(350);
  result.compactLayout = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth,
    tabsVisible: [...document.querySelectorAll(".system-tab")].every((button) => button.getBoundingClientRect().height > 0),
    actionsVisible: document.querySelector(".context-actions").getBoundingClientRect().bottom <= window.innerHeight
  }));
  await page.screenshot({ path: `${shots}/world-v2-compact.png`, fullPage: true });
  process.stdout.write(JSON.stringify(result, null, 2));
  await browser.close();
  if (errors.length || result.flow !== "passed" || result.systemGlimpses !== 3) process.exitCode = 1;
})();
