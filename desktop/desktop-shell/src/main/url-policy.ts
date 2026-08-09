export interface WebUrlPolicyOptions {
  readonly configuredUrl?: string | undefined
  readonly isPackaged: boolean
}

const DEVELOPMENT_URL = 'http://127.0.0.1:5173'
const DEVELOPMENT_HOSTS = new Set(['127.0.0.1', 'localhost'])

export function resolveWebAppUrl(options: WebUrlPolicyOptions): URL {
  const configuredUrl = options.configuredUrl?.trim()
  if (!configuredUrl && options.isPackaged) {
    throw new Error('生产桌面壳必须配置 YUMPOO_WEB_URL')
  }

  let webUrl: URL
  try {
    webUrl = new URL(configuredUrl || DEVELOPMENT_URL)
  } catch {
    throw new Error('YUMPOO_WEB_URL 不是有效 URL')
  }

  if (webUrl.username || webUrl.password) {
    throw new Error('YUMPOO_WEB_URL 不得包含用户名或密码')
  }

  if (options.isPackaged) {
    if (webUrl.protocol !== 'https:') {
      throw new Error('生产桌面壳只允许 HTTPS URL')
    }
  } else if (
    webUrl.protocol !== 'http:' ||
    !DEVELOPMENT_HOSTS.has(webUrl.hostname.toLowerCase())
  ) {
    throw new Error('开发桌面壳只允许 localhost 或 127.0.0.1 的 HTTP URL')
  }

  return webUrl
}
