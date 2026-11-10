import { randomUUID } from 'node:crypto'
import { test, expect, skipOnboarding } from './fixtures'

test('我的工作统一滚动、列布局及无勾选表格', async ({ page }) => {
  test.setTimeout(90_000)
  page.setDefaultTimeout(10_000)
  await page.setViewportSize({ width: 1050, height: 850 })
  await page.goto('/')
  await skipOnboarding(page)
  const auth = await page.evaluate(async () => (await fetch('/api/v1/auth/me')).json())
  const csrf = (await page.context().cookies()).find(cookie => cookie.name === '__Host-yumpoo-csrf')!.value
  async function post(path: string, data: unknown) {
    const response = await page.evaluate(async ({ path, data, csrf, id }) => {
      const result = await fetch(`/api/v1${path}`, { method: 'POST', body: JSON.stringify(data), headers: {
        'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf), 'Idempotency-Key': id,
      } })
      return { ok: result.ok, body: await result.json() }
    }, { path, data, csrf, id: randomUUID() })
    expect(response.ok, `${path}: ${JSON.stringify(response.body)}`).toBeTruthy()
    return response.body
  }
  const suffix = Date.now()
  const projects: Array<{ id: string; name: string }> = []
  for (const name of ['设计', '开发', '验证']) {
    const project = await post('/projects', { name: `整体化验收 ${name} ${suffix}` })
    projects.push(project)
    const catalog = await page.evaluate(async id => (await fetch(`/api/v1/projects/${id}/contents`)).json(), project.id)
    const data = { contentId: catalog.items[0].id, priority: null, description: null, notes: null, assigneeUserIds: [auth.user.id] }
    const parent = await post(`/projects/${project.id}/work-items`, { ...data, title: `${name}工作项一`, dueDate: '2020-01-01' })
    await post(`/work-items/${parent.id}/subitems`, { ...data, title: `${name}子项` })
    await post(`/projects/${project.id}/work-items`, { ...data, title: `${name}工作项二` })
  }
  await page.goto('/my-work')
  await skipOnboarding(page)
  const sections = projects.map(project => page.getByRole('region', { name: `${project.name}中分配给我的工作项` }))
  for (const section of sections) {
    await section.scrollIntoViewIfNeeded()
    await expect(section.locator('.work-item-table-row')).toHaveCount(2)
  }
  const scrollbar = page.getByLabel('我的工作横向滚动', { exact: true })
  await expect(scrollbar).toHaveCount(1)
  await expect(scrollbar).toBeVisible()
  const rootScrolls = page.locator(`.my-work-project[aria-label$="${suffix}中分配给我的工作项"] .monday-table > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__wrap`)
  async function expectScroll(left: number) {
    await expect.poll(() => rootScrolls.evaluateAll(nodes => nodes.map(node => Math.round(node.scrollLeft))))
      .toEqual(Array(projects.length).fill(left))
  }
  await scrollbar.evaluate(el => { el.scrollLeft = 180 })
  await expectScroll(180)
  await page.getByRole('button', { name: '刷新我的工作', exact: true }).click()
  for (const section of sections) { await section.scrollIntoViewIfNeeded(); await expect(section.locator('.work-item-table-row')).toHaveCount(2) }
  await expectScroll(180)
  const first = sections[0]!
  await first.scrollIntoViewIfNeeded()
  await first.getByRole('button', { name: '收起子项', exact: true }).click()
  await first.locator('.subitem-expand-button--has-subitems').click()
  await expect(first.locator('.subitem-table-row')).toHaveCount(1)
  await expect(page.locator('.my-work-project .monday-selection-column, .my-work-project .subitem-selection-column, .my-work-project .subitem-quick-checkbox, .my-work-project .work-item-batch-spacer')).toHaveCount(0)
  await first.getByRole('button', { name: `收起项目：${projects[0]!.name}`, exact: true }).click()
  await first.getByRole('button', { name: `展开项目：${projects[0]!.name}`, exact: true }).click()
  await expect(first.locator('.work-item-table-row')).toHaveCount(2)
  await expectScroll(180)
  await page.getByRole('button', { name: '我的工作列设置' }).click()
  const columnMenu = page.locator('.my-work-columns')
  await columnMenu.getByLabel('最后更新时间', { exact: true }).uncheck()
  await columnMenu.getByLabel('主表冻结至', { exact: true }).selectOption('1')
  await page.getByRole('button', { name: '我的工作列设置' }).click()
  for (const section of sections) {
    await expect(section.locator('.work-item-group-columns .monday-column--updatedAt')).toHaveCount(0)
    await expect(section.locator('.work-item-group-columns .monday-column--assignee')).toHaveClass(/el-table-fixed-column--left/)
  }
  await scrollbar.evaluate(el => { el.scrollLeft = 0 })
  await expectScroll(0)
  await first.scrollIntoViewIfNeeded()
  await first.locator('.work-item-group-columns .monday-column--status').hover()
  await first.getByRole('button', { name: '状态列菜单', exact: true }).click()
  await page.getByRole('menuitem', { name: '折叠', exact: true }).click()
  for (const section of sections) {
    await expect(section.locator('.work-item-group-columns').getByRole('button', { name: '展开状态列', exact: true })).toHaveCount(1)
    await expect(section.locator('.monday-subitem-table').getByRole('button', { name: '展开状态列', exact: true })).toHaveCount(1)
  }
  await first.locator('.monday-subitem-table').getByRole('button', { name: '展开状态列', exact: true }).click()
  for (const section of sections) await expect(section.getByRole('button', { name: '展开状态列', exact: true })).toHaveCount(0)
  await page.reload(); await skipOnboarding(page)
  await page.getByRole('button', { name: '我的工作列设置' }).click()
  await expect(columnMenu.getByLabel('最后更新时间', { exact: true })).not.toBeChecked()
  await expect(columnMenu.getByLabel('主表冻结至', { exact: true })).toHaveValue('1')
  await page.getByRole('button', { name: '我的工作列设置' }).click()
  for (const section of sections) { await section.scrollIntoViewIfNeeded(); await expect(section.locator('.work-item-table-row')).toHaveCount(2) }
  await page.setViewportSize({ width: 800, height: 700 })
  await scrollbar.evaluate(el => { el.scrollLeft = 200 })
  await expectScroll(200)
  // 项目表格仍保留多选；页面共享设置不会覆盖项目布局。
  await page.goto(`/projects/${projects[0]!.id}/overview`)
  await skipOnboarding(page)
  await expect(page.locator('.monday-selection-column').first()).toBeVisible()
  await expect(page.locator('.monday-column-header--updatedAt').first()).toBeVisible()
})
