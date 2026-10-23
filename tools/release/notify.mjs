import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { notifyMonitor } from './monitor-notify.mjs'

const webhookPattern = /^https:\/\/qyapi\.weixin\.qq\.com\/cgi-bin\/webhook\/send\?key=[A-Za-z0-9-]+$/u

// Best effort: a notification failure never fails the release workflow, and the webhook key never reaches logs.
export async function notifyWeCom(env, content, send = fetch) {
  if (env.MONITOR_ENABLED === 'true') return notifyMonitor(env, content, { send })
  const url = env.WECOM_RELEASE_WEBHOOK
  if (!url) return false
  if (!webhookPattern.test(url)) {
    console.warn('WECOM_RELEASE_WEBHOOK ignored: it is not a WeCom group robot URL')
    return false
  }
  try {
    const response = await send(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ msgtype: 'markdown', markdown: { content: truncateUtf8(content) } }),
      signal: AbortSignal.timeout(10_000),
    })
    const result = await response.json()
    return response.ok && result?.errcode === 0
  } catch (error) {
    console.warn(`WeCom notification failed: ${error.name}`)
    return false
  }
}

export function truncateUtf8(value, max = 3900) {
  if (Buffer.byteLength(value) > max) value = value.replace(/[<>`*\[\]\\]/gu, '')
  let result = ''; let bytes = 0
  for (const point of value) {
    const size = Buffer.byteLength(point)
    if (bytes + size > max) return result + '\n…'
    result += point; bytes += size
  }
  return result
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await notifyWeCom(process.env, String(process.env.NOTIFY_TEXT || ''))
}
