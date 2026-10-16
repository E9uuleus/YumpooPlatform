import path from 'node:path'
import { pathToFileURL } from 'node:url'

const webhookPattern = /^https:\/\/qyapi\.weixin\.qq\.com\/cgi-bin\/webhook\/send\?key=[A-Za-z0-9-]+$/u

// Best effort: a notification failure never fails the release workflow, and the webhook key never reaches logs.
export async function notifyWeCom(env, content, send = fetch) {
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
      body: JSON.stringify({ msgtype: 'markdown', markdown: { content } }),
      signal: AbortSignal.timeout(10_000),
    })
    return response.ok
  } catch (error) {
    console.warn(`WeCom notification failed: ${error.name}`)
    return false
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await notifyWeCom(process.env, String(process.env.NOTIFY_TEXT || '').slice(0, 3000))
}
