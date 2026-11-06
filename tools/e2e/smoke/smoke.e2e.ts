import { test, expect, skipOnboarding } from './fixtures'

test.describe.configure({ mode: 'serial' })
const timestamp = new Date().toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai' }).replace(/[-:]/g, '').replace(' ', '-')
const projectName = `E2E 冒烟 ${timestamp}`
let projectUrl = ''

test('首页登录', async ({ page }) => {
  await page.goto('/')
  await skipOnboarding(page)
  await expect(page.getByRole('heading', { level: 1 })).toContainText('本地测试管理员')
  for (const name of ['首页', '工作台', '团队', '公司管理', '运维']) {
    await expect(page.getByRole('navigation').getByRole('button', { name, exact: true })).toBeVisible()
  }
})

test('创建项目', async ({ page }) => {
  await page.goto('/workspace')
  await skipOnboarding(page)
  await page.locator('[data-tour="project-create"]').click()
  const dialog = page.getByRole('dialog', { name: '创建项目', exact: true })
  await dialog.getByLabel('项目名称', { exact: true }).fill(projectName)
  await dialog.getByRole('button', { name: '创建项目', exact: true }).click()
  await expect(page).toHaveURL(/\/projects\/[0-9a-f-]{36}\/overview$/)
  projectUrl = page.url()
  await expect(page.getByRole('heading', { name: projectName, exact: true })).toBeVisible()
})

test('工作项与详情', async ({ page }) => {
  await page.goto(projectUrl)
  await skipOnboarding(page)
  await page.getByRole('button', { name: '添加工作项', exact: true }).click()
  await page.getByPlaceholder('添加工作项', { exact: true }).fill('冒烟工作项')
  await page.getByPlaceholder('添加工作项', { exact: true }).press('Enter')
  await page.getByRole('button', { name: '打开工作项详情：冒烟工作项', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '工作项详情', exact: true })
  await expect(drawer.getByText('冒烟工作项', { exact: true })).toBeVisible()
  await drawer.getByRole('button', { name: /添加描述/ }).click()
  await expect(drawer.locator('.ProseMirror').first()).toBeVisible()
})

test('仪表板', async ({ page }) => {
  const response = await page.request.get('/api/v1/auth/me')
  expect(response.ok()).toBeTruthy()
  const authentication = await response.json()
  await page.goto(`/workspace/${authentication.user.workspaceSlug}/dashboards`)
  await skipOnboarding(page)
  await page.getByRole('button', { name: '连接第一个项目', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '连接项目', exact: true })
  await dialog.getByText(projectName, { exact: true }).click()
  await expect(dialog.getByRole('checkbox', { name: projectName })).toBeChecked()
  await dialog.getByRole('button', { name: '应用 · 1 个项目', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboards\/[0-9a-f-]{36}$/)
  const card = page.locator('.dashboard-grid > .grid-stack-item').first()
  await card.hover()
  const handle = card.locator('> .ui-resizable-se')
  await expect(handle).toBeVisible()
  // 生产 CSP 拦截 GridStack 自带的 data: 图标，手柄须改用同源资源。
  await expect(handle).toHaveCSS('background-image', 'none')
  await expect(handle).toHaveCSS('mask-image', /\/assets\/resize-handle-[\w-]+\.svg"\)$/u)
})

test('我的一天', async ({ page }) => {
  const response = await page.request.get('/api/v1/auth/me')
  expect(response.ok()).toBeTruthy()
  const authentication = await response.json()
  const today = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' })
  await page.goto(`/workspace/${authentication.user.workspaceSlug}/my-time/day/${today}`)
  await expect(page.locator('.day-header').getByRole('heading', { level: 1 })).toBeVisible()
})

test('收件箱', async ({ page }) => {
  await page.goto('/inbox')
  await expect(page.getByRole('heading', { level: 1, name: '收件箱', exact: true })).toBeVisible()
})

test('管理后台', async ({ page }) => {
  await page.goto('/admin/company/overview')
  await skipOnboarding(page)
  await expect(page.locator('[data-tour="company-profile"]')).toBeVisible()
  await page.goto('/admin/operations/overview')
  await expect(page.locator('.operations-page')).toBeVisible()
  await expect(page.locator('.operations-page .ops-banner')).toBeVisible()
  await expect(page.locator('.operations-page .inline-error')).toHaveCount(0)
  await expect(page.getByRole('main')).not.toContainText('页面不存在')
})

test('文档中心', async ({ page }) => {
  await page.goto('/docs')
  await expect(page.getByRole('heading', { level: 1, name: 'Yumpoo 工作中台', exact: true })).toBeVisible()
  await page.keyboard.press('Control+K')
  await page.getByPlaceholder('搜索文档，例如 并行计时、工作日历', { exact: true }).fill('并行计时')
  await expect(page.getByRole('option').first()).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/docs\/guide\/time-tracking#/)
  await expect(page.getByRole('heading', { level: 1, name: '计时与工时', exact: true })).toBeVisible()
})
