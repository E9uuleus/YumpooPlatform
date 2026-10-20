import assert from 'node:assert/strict'
import test from 'node:test'
import { collectReleaseEntries, commitSection, desktopNotes, desktopReleaseBody, exemptPull, parseReleaseNotes, releaseNotes } from './release-notes.mjs'
import { notifyWeCom } from './notify.mjs'

const sha = 'a'.repeat(40)
const run = { id: 3001, run_attempt: 2 }
const empty = { entries: [], pulls: [], dependencies: [], missing: [] }

test('commit subjects still group the promotion comment by conventional type', () => {
  assert.equal(commitSection('feat(timer): x (TT-1)'), '新功能')
  assert.equal(commitSection('fix!: breaking fix'), '问题修复')
  assert.equal(commitSection('build(deps): bump'), 'CI 与构建')
  assert.equal(commitSection('Merge branch dev'), '其他')
})

test('release notes parse one strict section and ignore the template guidance', () => {
  const parsed = parseReleaseNotes([
    '改了什么。', '', '## 发布说明', '<!-- - 类别｜范围：描述 -->',
    '- 新增｜桌面：托盘左键直接打开快捷工时小窗口。',
    '- 修复 | Web : 时间显示为 12:30 而不是 00:30。',
    '- 安全｜服务端：修复 CVE-2026-1234 并统一按 UTF-8 读取。',
    '', '## 验证', '- 不是发布说明',
  ].join('\n'))
  assert.deepEqual(parsed.errors, [])
  assert.deepEqual(parsed.entries, [
    { category: '新增', scope: '桌面', text: '托盘左键直接打开快捷工时小窗口。' },
    { category: '修复', scope: 'Web', text: '时间显示为 12:30 而不是 00:30。' },
    { category: '安全', scope: '服务端', text: '修复 CVE-2026-1234 并统一按 UTF-8 读取。' },
  ])
  assert.deepEqual(parseReleaseNotes('## 发布说明\n- 无\n'), { entries: [], none: true, errors: [] })
})

test('release notes reject missing, empty, malformed and leaking entries', () => {
  const errors = body => parseReleaseNotes(body).errors
  assert.deepEqual(errors('没有段落'), ['缺少「## 发布说明」段'])
  assert.deepEqual(errors('## 发布说明\n- 无\n## 发布说明\n- 无'), ['「## 发布说明」段只能有一个'])
  assert.match(errors('## 发布说明\n<!-- 只有注释 -->')[0], /段为空/u)
  assert.match(errors('## 发布说明\n托盘修复')[0], /不是条目/u)
  assert.match(errors('## 发布说明\n- 托盘修复了')[0], /格式应为/u)
  assert.match(errors('## 发布说明\n- 优化｜桌面：托盘左键更快打开。')[0], /类别只能是/u)
  assert.match(errors('## 发布说明\n- 修复｜前端：托盘左键更快打开。')[0], /范围只能是/u)
  assert.match(errors('## 发布说明\n- 修复｜桌面：修复')[0], /5–120/u)
  assert.match(errors('## 发布说明\n- 修复｜桌面：修复登录与附件问题 (BF-1～8)')[0], /步骤编号/u)
  assert.match(errors('## 发布说明\n- 修复｜桌面：同步 REL-02 的修复内容')[0], /步骤编号/u)
  assert.match(errors('## 发布说明\n- 修复｜桌面：fix(desktop): 托盘修复')[0], /type\(scope\)/u)
  assert.match(errors('## 发布说明\n- 修复｜桌面：回退 e903ba9e 的托盘改动')[0], /SHA/u)
  assert.match(errors('## 发布说明\n- 修复｜桌面：托盘修复，详见 #166')[0], /PR 引用/u)
  assert.match(errors('## 发布说明\n- 无\n- 修复｜桌面：托盘左键更快打开。').at(-1), /唯一条目/u)
  assert.match(errors(`## 发布说明\n${'- 修复｜桌面：托盘左键更快打开。\n'.repeat(11)}`).at(-1), /最多 10 条/u)
})

test('only dependency bumps, back-merges and dev promotions are exempt', () => {
  assert.equal(exemptPull({ user: { login: 'dependabot[bot]' }, head: { ref: 'dependabot/npm/x' }, base: { ref: 'dev' } }), 'dependabot')
  assert.equal(exemptPull({ user: { login: 'github-actions[bot]' }, head: { ref: 'backmerge/e903ba9ec605' }, base: { ref: 'dev' } }), 'backmerge')
  assert.equal(exemptPull({ user: { login: 'm' }, head: { ref: 'dev' }, base: { ref: 'main' } }), 'promotion')
  assert.equal(exemptPull({ user: { login: 'm' }, head: { ref: 'hotfix/feed' }, base: { ref: 'main' } }), null)
  assert.equal(exemptPull({ user: { login: 'm' }, head: { ref: 'fix/x' }, base: { ref: 'dev' } }), null)
})

test('entries come from each PR once, in commit order, and gaps are reported instead of failing', async () => {
  const pull = (number, body, extra = {}) => ({ number, title: `PR ${number}`, merged_at: '2026-10-20T00:00:00Z', body, user: { login: 'm' }, head: { ref: 'fix/x' }, base: { ref: 'dev' }, ...extra })
  const pulls = {
    1: [pull(11, '## 发布说明\n- 修复｜桌面：托盘小窗口不再显示 404。')],
    2: [pull(11, '## 发布说明\n- 修复｜桌面：托盘小窗口不再显示 404。')],
    3: [pull(12, '旧正文')],
    4: [pull(13, '', { user: { login: 'dependabot[bot]' } })],
    5: [],
    6: [pull(14, '', { head: { ref: 'backmerge/x' } }), pull(15, '## 发布说明\n- 无', { head: { ref: 'hotfix/feed' }, base: { ref: 'main' } })],
    7: [pull(16, '## 发布说明\n- 新增｜Web：表格记住分组。', { merged_at: null })],
  }
  const github = { get: endpoint => pulls[endpoint.match(/^\/commits\/(\d)\1+\/pulls$/u)[1]] }
  const commit = (digit, extra = {}) => ({ sha: digit.repeat(40), parents: [{}], commit: { message: `subject ${digit}\n\nbody` }, ...extra })
  const collected = await collectReleaseEntries(github, [commit('1'), commit('2'), commit('8', { parents: [{}, {}] }), commit('3'), commit('4'), commit('5'), commit('6'), commit('7')])
  assert.deepEqual(collected.entries, [{ category: '修复', scope: '桌面', text: '托盘小窗口不再显示 404。', pull: 11 }])
  assert.deepEqual(collected.pulls, [11, 12, 13, 15])
  assert.deepEqual(collected.dependencies, [13])
  assert.deepEqual(collected.missing, [
    { label: '#12 PR 12', reason: '缺少「## 发布说明」段' },
    { label: '`55555555` subject 5', reason: '没有关联的 PR' },
    { label: '`77777777` subject 7', reason: '没有关联的 PR' },
  ])
})

const collected = {
  entries: [
    { category: '新增', scope: '桌面', text: '托盘左键直接打开快捷工时小窗口。', pull: 166 },
    { category: '修复', scope: 'Web', text: '表格刷新后保留分组与展开。', pull: 165 },
    { category: '运维', scope: '服务端', text: '发布后管理员核对 Nginx 配置。', pull: 171 },
    { category: '运维', scope: '桌面', text: '先发布服务端，再发布桌面 1.1.1。', pull: 171 },
  ],
  pulls: [165, 166, 171, 172], dependencies: [172], missing: [{ label: '#170 旧 PR', reason: '缺少「## 发布说明」段' }],
}

test('the server body is layered: updates, upgrade impact, then release facts', () => {
  const comparison = {
    commits: [],
    files: [
      { filename: 'backend/src/main/resources/db/migration/workitem/V80__x.sql', status: 'added' },
      { filename: 'backend/src/main/resources/db/migration/workitem/V81__y.sql', status: 'added' },
      { filename: 'backend/src/main/resources/db/migration/workitem/V79__z.sql', status: 'modified' },
      { filename: 'contracts/openapi/yumpoo-v1.yaml', status: 'modified' },
      { filename: 'deployment/windows/cd/YumpooDeployment.psm1', status: 'modified' },
    ],
  }
  const inventory = [{ file: 'workitem/V80__x.sql', classification: 'expand' }, { file: 'workitem/V81__y.sql', classification: 'breaking' }]
  const notes = releaseNotes({ tag: 'server-v2026.10.21.1', sourceCommit: sha, run, previousTag: 'server-v2026.10.20.2', comparison, collected, inventory })
  const order = ['## 服务端 server-v2026.10.21.1', '### 更新内容', '### 升级影响', '### 发布信息', '> 本草稿由 Prepare Release']
  assert.deepEqual(order.map(text => notes.indexOf(text)).toSorted((left, right) => left - right), order.map(text => notes.indexOf(text)))
  assert.match(notes, /新增 1 · 改进 0 · 修复 1 · 安全 0 · 需要管理员操作：是/u)
  assert.match(notes, /\*\*新增\*\*\n\n- 桌面｜托盘左键直接打开快捷工时小窗口。（#166）\n\n\*\*修复\*\*\n\n- Web｜表格刷新后保留分组与展开。（#165）/u)
  assert.doesNotMatch(notes.slice(0, notes.indexOf('### 升级影响')), /运维|Nginx/u)
  assert.match(notes, /`V80__x\.sql`（只扩展，可快速回退）、`V81__y\.sql`（\*\*破坏性，不可快速回退\*\*）/u)
  assert.doesNotMatch(notes, /V79__z/u)
  assert.match(notes, /- API \/ 事件契约：`contracts\/openapi\/yumpoo-v1\.yaml` 有变化/u)
  assert.match(notes, /- 部署引擎：\*\*有变化.*Update-YumpooEngine\.ps1/u)
  assert.match(notes, /- 管理员操作：\n {2}- 服务端｜发布后管理员核对 Nginx 配置。（#171）\n {2}- 桌面｜先发布服务端/u)
  assert.match(notes, /- 回退：含破坏性迁移，不能用 Server Rollback 快速回退/u)
  assert.match(notes, /actions\/runs\/3001\/attempts\/2/u)
  assert.match(notes, /compare\/server-v2026\.10\.20\.2\.\.\.a{40}/u)
  assert.match(notes, /- 包含 PR：#165、#166、#171、#172\n- 依赖更新：1 项（#172）\n- 未提供发布说明：1 项.*\n {2}- #170 旧 PR：缺少「## 发布说明」段/u)
  assert.doesNotMatch(notes, /截断/u)
})

test('the server body states quiet ranges, the first release and truncation', () => {
  const quiet = releaseNotes({ tag: 'server-v2026.10.21.1', sourceCommit: sha, run, previousTag: 'server-v2026.10.20.2', comparison: { commits: [], files: [] }, collected: empty })
  assert.match(quiet, /- 本版本没有面向使用者的变更。/u)
  assert.match(quiet, /- 数据库迁移：无\n- API \/ 事件契约：无变更\n- 部署引擎：无变化\n- 管理员操作：无\n- 回退：可用 Server Rollback 回退到 `server-v2026\.10\.20\.2`/u)
  assert.match(quiet, /需要管理员操作：否/u)
  const first = releaseNotes({ tag: 'server-v2026.10.21.1', sourceCommit: sha, run, previousTag: undefined, comparison: null, collected: empty })
  assert.match(first, /首个 main 发布版本，不生成逐条变更/u)
  assert.match(first, /- 上一版本：无（首个 main 发布版本）/u)
  assert.doesNotMatch(first, /对比范围/u)
  const truncated = releaseNotes({ tag: 'server-v2026.10.21.1', sourceCommit: sha, run, previousTag: 'server-v2026.10.20.2',
    comparison: { commits: [], files: Array.from({ length: 300 }, (_, index) => ({ filename: `f${index}`, status: 'modified' })) }, collected: empty })
  assert.match(truncated, /截断/u)
})

test('the desktop body and release.json notes keep only 桌面 entries and never show operator steps to users', () => {
  const body = desktopReleaseBody({ tag: 'desktop-v1.1.1', version: '1.1.1', sourceCommit: sha, run, previous: { tag_name: 'desktop-v1.1.0' }, collected })
  assert.match(body, /^## 桌面端 1\.1\.1\n\n新增 1 · 改进 0 · 修复 0 · 安全 0 · 需要管理员操作：是/u)
  assert.doesNotMatch(body.slice(0, body.indexOf('### 发布信息')), /表格刷新|Nginx/u)
  assert.match(body, /- 管理员操作：\n {2}- 桌面｜先发布服务端，再发布桌面 1\.1\.1。（#171）\n- 回退：桌面不能回退，只能用更高版本前滚。/u)
  assert.match(body, /- 上一版本：desktop-v1\.1\.0\n- 对比范围：.*compare\/desktop-v1\.1\.0\.\.\.a{40}/u)
  assert.match(body, /### 发布前试装\n\n- 下载本草稿的 `YumpooDesktop-Setup-1\.1\.1-x64\.exe`/u)
  assert.deepEqual(desktopNotes(collected, { tag_name: 'desktop-v1.1.0' }), ['新增：托盘左键直接打开快捷工时小窗口。'])
  assert.deepEqual(desktopNotes(empty, { tag_name: 'desktop-v1.1.0' }), ['桌面客户端维护更新'])
  assert.deepEqual(desktopNotes(empty, undefined), ['首个安装版：按用户安装，支持自动更新、诊断与修复和安全模式'])
  const first = desktopReleaseBody({ tag: 'desktop-v1.0.0', version: '1.0.0', sourceCommit: sha, run, previous: undefined, collected: empty })
  assert.match(first, /- 首个安装版：按用户安装/u)
  assert.match(first, /- 上一版本：无（首个经发布渠道分发的版本）/u)
})

test('GitHub-side notifications accept only the robot URL and never fail the workflow', async () => {
  const calls = []
  const send = async (url, options) => { calls.push({ url, body: JSON.parse(options.body) }); return { ok: true } }
  assert.equal(await notifyWeCom({}, 'x', send), false)
  for (const url of ['https://example.invalid/cgi-bin/webhook/send?key=a', 'https://qyapi.weixin.qq.com:443/cgi-bin/webhook/send?key=a']) {
    assert.equal(await notifyWeCom({ WECOM_RELEASE_WEBHOOK: url }, 'x', send), false)
  }
  assert.deepEqual(calls, [])
  assert.equal(await notifyWeCom({ WECOM_RELEASE_WEBHOOK: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc-123' }, '**草稿就绪**', send), true)
  assert.deepEqual(calls[0].body, { msgtype: 'markdown', markdown: { content: '**草稿就绪**' } })
  assert.equal(await notifyWeCom({ WECOM_RELEASE_WEBHOOK: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc' }, 'x', async () => { throw new TypeError('network down') }), false)
})
