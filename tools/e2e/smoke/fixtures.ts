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
  if (await skip.isVisible()) await skip.click()
  await page.addLocatorHandler(skip, async () => { await skip.click() })
  const closeTour = page.locator('.el-tour').getByRole('button', { name: '关闭此对话框', exact: true })
  if (await closeTour.isVisible()) await closeTour.click()
  await page.addLocatorHandler(closeTour, async () => { await closeTour.click() })
}

export { expect }
