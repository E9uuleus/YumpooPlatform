export interface WebUrlPolicyOptions {
  readonly configuredUrl?: string | undefined
  readonly isPackaged: boolean
  /** Required when packaged: the origin from the baked-in distribution config. */
  readonly distributionOrigin?: string | undefined
}

const DEVELOPMENT_URL = 'http://127.0.0.1:18173'
const DEVELOPMENT_HOSTS = new Set(['127.0.0.1', 'localhost'])

export function resolveWebAppUrl(options: WebUrlPolicyOptions): URL {
  if (options.isPackaged) {
    if (!options.distributionOrigin) {
      throw new Error('打包桌面壳缺少分发配置')
    }
    const webUrl = new URL(options.distributionOrigin)
    if (webUrl.protocol !== 'https:' || webUrl.username || webUrl.password) {
      throw new Error('生产桌面壳只允许不含凭据的 HTTPS URL')
    }
    return webUrl
  }

  let webUrl: URL
  try {
    webUrl = new URL(options.configuredUrl?.trim() || DEVELOPMENT_URL)
  } catch {
    throw new Error('YUMPOO_WEB_URL 不是有效 URL')
  }

  if (webUrl.username || webUrl.password) {
    throw new Error('YUMPOO_WEB_URL 不得包含用户名或密码')
  }

  if (
    webUrl.protocol !== 'http:' ||
    !DEVELOPMENT_HOSTS.has(webUrl.hostname.toLowerCase())
  ) {
    throw new Error('开发桌面壳只允许 localhost 或 127.0.0.1 的 HTTP URL')
  }

  return webUrl
}
