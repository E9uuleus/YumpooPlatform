import assert from 'node:assert/strict'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { ESLint } from 'eslint'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const eslint = new ESLint({
  cwd: repositoryRoot,
  overrideConfigFile: path.join(repositoryRoot, 'eslint.config.mjs'),
})

async function boundaryMessages(filePath, source) {
  const [result] = await eslint.lintText(source, {
    filePath: path.join(repositoryRoot, filePath),
  })
  return result.messages.filter(
    (message) => message.ruleId === 'yumpoo/workspace-boundaries',
  )
}

test('Web 拦截 Node、Electron 和 desktop 相对路径依赖', async () => {
  const file = 'frontend/web-app/src/illegal.ts'
  assert.equal((await boundaryMessages(file, "import 'node:fs'\n")).length, 1)
  assert.equal((await boundaryMessages(file, "import 'electron'\n")).length, 1)
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import '../../../desktop/desktop-shell/src/main/index'\n",
      )
    ).length,
    1,
  )
  assert.equal(
    (await boundaryMessages(file, "import '../../../tools/escape.mjs'\n")).length,
    1,
  )
})

test('Web 只能类型依赖 preload contract', async () => {
  const file = 'frontend/web-app/src/contract.ts'
  assert.equal(
    (await boundaryMessages(file, "import '@yumpoo/preload-contract'\n")).length,
    1,
  )
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import type { DesktopBridge } from '@yumpoo/preload-contract'\n",
      )
    ).length,
    0,
  )
})

test('Web 可以运行时依赖 API client', async () => {
  const messages = await boundaryMessages(
    'frontend/web-app/src/api/client.ts',
    "import { createYumpooApiClient } from '@yumpoo/api-client'\nvoid createYumpooApiClient\n",
  )
  assert.equal(messages.length, 0)
})

test('API client 拦截 Node、Electron、其他工作区包和路径逃逸', async () => {
  const file = 'packages/api-client/src/illegal.ts'
  assert.equal((await boundaryMessages(file, "import 'node:fs'\n")).length, 1)
  assert.equal((await boundaryMessages(file, "import 'electron'\n")).length, 1)
  assert.equal(
    (await boundaryMessages(file, "import '@yumpoo/web-app'\n")).length,
    1,
  )
  assert.equal(
    (await boundaryMessages(file, "import '../../../tools/escape.mjs'\n")).length,
    1,
  )
  assert.equal(
    (
      await boundaryMessages(
        'packages/api-client/src/generated/illegal.ts',
        "/* eslint-disable */\nimport 'node:fs'\n",
      )
    ).length,
    1,
  )
})

test('受限代码对动态导入失败关闭', async () => {
  assert.equal(
    (
      await boundaryMessages(
        'frontend/web-app/src/dynamic.ts',
        'const target = "vue"\nvoid import(target)\n',
      )
    ).length,
    1,
  )
})

test('preload 拦截 main、Node、namespace Electron 与原始 ipcRenderer', async () => {
  const file = 'desktop/desktop-shell/src/preload/illegal.ts'
  assert.equal((await boundaryMessages(file, "import '../main/index'\n")).length, 1)
  assert.equal((await boundaryMessages(file, "import 'node:path'\n")).length, 1)
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import { ipcRenderer } from 'electron'\nvoid ipcRenderer\n",
      )
    ).length,
    1,
  )
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import * as electron from 'electron'\nvoid electron\n",
      )
    ).length,
    1,
  )
  assert.equal(
    (await boundaryMessages(file, "import '@yumpoo/api-client'\n")).length,
    1,
  )
})

test('preload 入口仅允许固定认证 IPC 包装', async () => {
  const file = 'desktop/desktop-shell/src/preload/index.ts'
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import { contextBridge, ipcRenderer } from 'electron'\n" +
          "void ipcRenderer.invoke('yumpoo:auth:start')\n" +
          "void ipcRenderer.on('yumpoo:auth:status', () => undefined)\n" +
          "void ipcRenderer.removeListener('yumpoo:auth:status', () => undefined)\n" +
          'void contextBridge\n',
      )
    ).length,
    0,
  )
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import { contextBridge, ipcRenderer } from 'electron'\n" +
          "void ipcRenderer.send('yumpoo:auth:start')\n" +
          'void contextBridge\n',
      )
    ).length,
    1,
  )
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import { contextBridge, ipcRenderer } from 'electron'\n" +
          "const channel = 'yumpoo:auth:start'\n" +
          'void ipcRenderer.invoke(channel)\n' +
          'void contextBridge\n',
      )
    ).length,
    1,
  )
  assert.equal(
    (
      await boundaryMessages(
        file,
        "import { contextBridge, ipcRenderer } from 'electron'\n" +
          "contextBridge.exposeInMainWorld('raw', ipcRenderer)\n",
      )
    ).length,
    1,
  )
})

test('Electron main 不得直接依赖 API client', async () => {
  const messages = await boundaryMessages(
    'desktop/desktop-shell/src/main/illegal.ts',
    "import '@yumpoo/api-client'\n",
  )
  assert.equal(messages.length, 1)
})

test('preload contract 拦截 Electron 与路径逃逸', async () => {
  const file = 'packages/preload-contract/src/illegal.ts'
  assert.equal((await boundaryMessages(file, "import 'electron'\n")).length, 1)
  assert.equal(
    (await boundaryMessages(file, "import '../../../tools/escape.mjs'\n")).length,
    1,
  )
})
