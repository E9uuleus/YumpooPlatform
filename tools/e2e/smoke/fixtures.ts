import { test as base, expect, type ConsoleMessage, type Page, type Response } from '@playwright/test'

export const test = base.extend({
  page: async ({ page }, use) => {
    const errors: string[] = []
    const pageError = (error: Error) => errors.push(`pageerror: ${error.message}`)
    const consoleError = (message: ConsoleMessage) => {
      if (message.type() === 'error') errors.push(`console: ${message.text()}`)
    }
    const serverError = (response: Response) => {
      if (response.url().includes('/api/') && response.status() >= 500) {
        errors.push(`HTTP ${response.status()}: ${response.url()}`)
      }
    }
    page.on('pageerror', pageError)
    page.on('console', consoleError)
    page.on('response', serverError)
    await use(page)
    page.off('pageerror', pageError)
    page.off('console', consoleError)
    page.off('response', serverError)
    expect(errors, '浏览器运行错误').toEqual([])
  },
})

export async function skipOnboarding(page: Page) {
  const skip = page.getByRole('button', { name: '跳过', exact: true })
  const closeTour = page.locator('.el-tour').getByRole('button', { name: '关闭此对话框', exact: true })
  for (const button of [skip, closeTour]) {
    // 关闭动画期间按钮仍算可见；等它消失再注册处理器，否则处理器会点击正在消失的按钮并一直等到用例超时。
    if (await button.isVisible()) {
      await button.click()
      await button.waitFor({ state: 'hidden' })
    }
    await page.addLocatorHandler(button, async () => { await button.click() })
  }
}

export { expect }
