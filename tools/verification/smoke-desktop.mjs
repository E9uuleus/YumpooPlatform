import net from 'node:net'
import { setTimeout as delay } from 'node:timers/promises'
import {
  runPnpmSync,
  spawnPnpm,
  stopProcessTree,
} from './process-utils.mjs'

async function availablePort() {
  const server = net.createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  const port = typeof address === 'object' && address ? address.port : undefined
  await new Promise((resolve) => server.close(resolve))
  if (!port) {
    throw new Error('无法分配桌面冒烟测试端口')
  }
  return port
}

async function waitForHttp(url, child, state, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (state.error) {
      throw state.error
    }
    if (child.exitCode !== null) {
      throw new Error(`Vite Preview 提前退出：${child.exitCode}`)
    }
    try {
      const response = await fetch(url)
      if (response.ok) {
        return
      }
    } catch {
      // Preview server is still starting.
    }
    await delay(200)
  }
  throw new Error(`Vite Preview 未在 ${timeoutMs}ms 内就绪：${url}`)
}

async function waitForExit(child, timeoutMs) {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      stopProcessTree(child)
      reject(new Error(`Electron 未在 ${timeoutMs}ms 内退出`))
    }, timeoutMs)

    child.once('error', (error) => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('exit', (code, signal) => {
      clearTimeout(timer)
      if (code === 0) {
        resolve()
      } else {
        reject(
          new Error(
            `Electron 冒烟测试失败，退出码：${code ?? 'unknown'}，信号：${signal ?? 'none'}`,
          ),
        )
      }
    })
  })
}

runPnpmSync(['--filter', '@yumpoo/preload-contract', 'build'])
runPnpmSync(['--filter', '@yumpoo/api-client', 'build'])
runPnpmSync(['--filter', '@yumpoo/web-app', 'build'])
runPnpmSync(['--filter', '@yumpoo/desktop-shell', 'build'])

const port = await availablePort()
const webUrl = `http://127.0.0.1:${port}`
const previewState = { error: undefined }
const preview = spawnPnpm([
  '--filter',
  '@yumpoo/web-app',
  'exec',
  'vite',
  'preview',
  '--host',
  '127.0.0.1',
  '--port',
  String(port),
  '--strictPort',
])
preview.once('error', (error) => {
  previewState.error = error
})

let electron
try {
  await waitForHttp(webUrl, preview, previewState, 20_000)
  electron = spawnPnpm(
    [
      '--filter',
      '@yumpoo/desktop-shell',
      'exec',
      'electron',
      '.',
      '--smoke-test',
      '--disable-gpu',
    ],
    {
      env: {
        ...process.env,
        YUMPOO_WEB_URL: webUrl,
      },
    },
  )
  await waitForExit(electron, 30_000)
} finally {
  stopProcessTree(electron)
  stopProcessTree(preview)
}
