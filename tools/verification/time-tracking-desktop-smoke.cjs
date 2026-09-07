const { app, BrowserWindow } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..', '..');
const { TimerWindowController } = require(path.join(root, 'desktop/desktop-shell/dist/main/timer-window.js'));
const { createWindowOptions } = require(path.join(root, 'desktop/desktop-shell/dist/main/window-policy.js'));
const { installSecurityGuards } = require(path.join(root, 'desktop/desktop-shell/dist/main/security-guards.js'));
const origin = process.env.YUMPOO_TIMER_SMOKE_ORIGIN;
if (!origin || !['localhost', '127.0.0.1'].includes(new URL(origin).hostname)) throw new Error('Set a loopback YUMPOO_TIMER_SMOKE_ORIGIN');
const project = process.env.YUMPOO_TIMER_SMOKE_PROJECT_ID;
if (!project || !/^[0-9a-f-]{36}$/i.test(project)) throw new Error('Set YUMPOO_TIMER_SMOKE_PROJECT_ID');
const mode = process.argv.includes('--continue-case') ? 'continue' : 'stop';
app.setPath('userData', path.join(root, 'out', 'timer-smoke-profile-' + mode));
const result = { mode, checks: [], status: 'RUNNING' };
let main, mini, created, cookies, csrf;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(predicate, label) {
  for (let i = 0; i < 120; i++) { if (await predicate()) { result.checks.push(label); return; } await pause(250); }
  throw new Error('Timed out: ' + label);
}
async function api(route, method = 'GET', body, etag) {
  const response = await fetch(origin + '/api/v1' + route, { method, headers: { Cookie: cookies, ...(body ? { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf, 'Idempotency-Key': crypto.randomUUID(), ...(etag ? { 'If-Match': etag } : {}) } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const json = await response.json(); if (!response.ok) throw new Error('API ' + route + ': ' + response.status); return json;
}
async function click(window, text) {
  await window.webContents.executeJavaScript(`(() => { const b = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(text)} && b.getClientRects().length); if (!b) throw new Error('Missing visible button'); b.click(); })()`, true);
}
async function cleanup() {
  if (!created) return;
  const current = await api('/me/time-tracker');
  if (current.session?.id === created.id) await api('/me/time-tracker/stop', 'POST', { sessionId: created.id }, current.etag);
  const history = await api('/work-items/' + created.workItemId + '/time-sessions');
  const record = history.items.find(s => s.id === created.id);
  if (record?.stoppedAt) await api('/work-items/' + created.workItemId + '/time-sessions/' + created.id, 'DELETE', { reason: '清理 Electron 真实窗口验收记录' }, record.etag);
  created = undefined;
}
async function finish(error) {
  try { await cleanup(); } catch (failure) { error ??= failure; }
  result.status = error ? 'FAIL' : 'PASS';
  if (error) result.error = error.message;
  fs.writeFileSync(path.join(root, 'out', 'time-desktop-' + mode + '.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result));
  app.exit(error ? 1 : 0);
}
app.whenReady().then(async () => {
  const preload = path.join(root, 'desktop/desktop-shell/dist/preload/index.js');
  main = new BrowserWindow(createWindowOptions(preload, false));
  installSecurityGuards(main.webContents, origin);
  const controller = new TimerWindowController(() => main, origin, preload);
  controller.install(); controller.attachMain(main);
  await main.loadURL(origin + '/projects/' + project + '/overview'); main.show();
  await waitFor(() => { mini = BrowserWindow.getAllWindows().find(w => w !== main); return !!mini; }, '项目进入自动创建计时窗口');
  await waitFor(() => mini.webContents.executeJavaScript("!!document.querySelector('.candidate')"), '小窗口加载候选工作项');
  assert.equal(mini.isAlwaysOnTop(), true); result.checks.push('默认置顶');
  const jar = await main.webContents.session.cookies.get({ url: origin });
  cookies = jar.map(c => c.name + '=' + c.value).join('; ');
  csrf = jar.find(c => c.name.includes('csrf'))?.value;
  const initial = await api('/me/time-tracker'); assert.equal(initial.session, null, 'Do not disturb an existing timer');
  mini.close(); assert.equal(mini.isDestroyed(), false); assert.equal(mini.isVisible(), false); result.checks.push('关闭小窗口只隐藏');
  await main.webContents.executeJavaScript(`window.yumpooDesktop.timer.setProject('${project}')`);
  assert.equal(mini.isVisible(), false); result.checks.push('同项目不重复弹窗');
  await main.webContents.executeJavaScript('window.yumpooDesktop.timer.show()'); assert.equal(mini.isVisible(), true); result.checks.push('工具栏桥接复用窗口');
  await mini.webContents.executeJavaScript("document.querySelector('.candidate').click()", true);
  await waitFor(async () => { const current = await api('/me/time-tracker'); created = current.session; return !!created; }, '小窗口开始计时提交服务器');
  main.minimize(); await click(mini, '停止计时');
  await waitFor(async () => !(await api('/me/time-tracker')).session, '主窗口最小化时小窗口停止');
  await cleanup();
  await mini.webContents.executeJavaScript("document.querySelector('.candidate').click()", true);
  await waitFor(async () => { created = (await api('/me/time-tracker')).session; return !!created; }, '再次启动');
  main.close();
  await waitFor(() => main.webContents.executeJavaScript("document.body.innerText.includes('退出前处理计时')"), '关闭主窗口触发退出三选项');
  await click(main, '取消'); await pause(300); assert.equal(main.isDestroyed(), false); result.checks.push('取消保留主窗口和计时');
  main.webContents.session.webRequest.onBeforeRequest({ urls: [origin + '/api/v1/me/time-tracker/stop'] }, (_details, callback) => callback({ cancel: true }));
  main.close(); await pause(500); await click(main, '停止并退出');
  await waitFor(() => main.webContents.executeJavaScript("document.body.innerText.includes('停止失败')"), '停止请求失败保留窗口');
  assert.equal(main.isDestroyed(), false);
  main.webContents.session.webRequest.onBeforeRequest(null);
  let finalizing = false;
  app.on('will-quit', event => {
    event.preventDefault(); if (finalizing) return; finalizing = true;
    (async () => {
      const current = await api('/me/time-tracker');
      assert.equal(!!current.session, mode === 'continue');
      result.checks.push(mode === 'continue' ? '继续计时并退出保留服务端运行会话' : '停止并退出已获服务端停止确认');
      await finish();
    })().catch(finish);
  });
  main.close(); await pause(500); await click(main, mode === 'continue' ? '继续计时并退出' : '停止并退出');
}).catch(finish);
setTimeout(() => finish(new Error('Desktop smoke global timeout')), 60000).unref();
