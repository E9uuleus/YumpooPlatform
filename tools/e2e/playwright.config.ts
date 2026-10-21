import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './smoke',
  testMatch: '**/*.e2e.ts',
  outputDir: '../../out/e2e/results',
  reporter: [['list'], ['html', { outputFolder: '../../out/e2e/report', open: 'never' }]],
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 30_000,
  use: {
    baseURL: process.env.YUMPOO_E2E_BASE_URL ?? 'http://127.0.0.1:18174',
    channel: process.env.YUMPOO_E2E_BROWSER ?? (process.platform === 'win32' ? 'msedge' : 'chrome'),
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
