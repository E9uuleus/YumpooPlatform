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
})

test('preload contract 拦截 Electron 与路径逃逸', async () => {
  const file = 'packages/preload-contract/src/illegal.ts'
  assert.equal((await boundaryMessages(file, "import 'electron'\n")).length, 1)
  assert.equal(
    (await boundaryMessages(file, "import '../../../tools/escape.mjs'\n")).length,
    1,
  )
})
