import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { DesktopLogger, redactFields, redactUrl } from '../src/main/logger'

const directories: string[] = []
function temporaryDirectory(): string {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-logger-'))
  directories.push(directory)
  return directory
}

afterEach(() => {
  for (const directory of directories.splice(0)) rmSync(directory, { recursive: true, force: true })
})

describe('桌面日志', () => {
  it('URL 只保留协议、主机和路径', () => {
    expect(redactUrl('https://yumpoo.example.com/api/v1/me?token=secret#frag')).toBe('https://yumpoo.example.com/api/v1/me')
    expect(redactUrl('yumpoo://auth/callback?code=abc&state=def')).toBe('yumpoo://auth/callback')
    expect(redactUrl('file:///C:/Users/me/AppData/x.html')).toBe('file:')
    expect(redactUrl('not a url')).toBe('<invalid-url>')
  })

  it('丢弃可能携带凭据或正文的字段，并清理文本中的 URL 查询串', () => {
    expect(redactFields({
      cookie: 'a', sessionCredential: 'b', csrfToken: 'c', code: 'd', state: 'e', body: 'f', title: 'g', message: 'h',
      errorCode: -105,
      reason: 'crashed',
      url: 'https://yumpoo.example.com/path?code=1',
      error: 'net::ERR_FAILED loading https://yumpoo.example.com/x?state=2 now',
      skipped: undefined,
      long: 'x'.repeat(400),
    })).toEqual({
      errorCode: -105,
      reason: 'crashed',
      url: 'https://yumpoo.example.com/path',
      error: 'net::ERR_FAILED loading https://yumpoo.example.com/x now',
      long: `${'x'.repeat(300)}…`,
    })
  })

  it('按行写入 JSON 并在超过 5 MB 时轮转为最多三个文件', () => {
    const directory = temporaryDirectory()
    const logger = new DesktopLogger(directory, () => new Date('2026-10-17T00:00:00.000Z'))
    logger.info('startup', { version: '1.0.0' })
    const first = JSON.parse(readFileSync(path.join(directory, 'desktop.log'), 'utf8').trim())
    expect(first).toEqual({ ts: '2026-10-17T00:00:00.000Z', level: 'info', event: 'startup', version: '1.0.0' })

    const chunk = 'y'.repeat(290)
    const fields = Object.fromEntries(Array.from({ length: 10 }, (_, index) => [`field${index}`, chunk]))
    for (let index = 0; index < 6_000; index += 1) logger.warn('filler', fields)
    expect(readdirSync(directory).sort()).toEqual(['desktop.1.log', 'desktop.2.log', 'desktop.log'])
    for (const name of readdirSync(directory)) {
      expect(readFileSync(path.join(directory, name)).byteLength).toBeLessThanOrEqual(5 * 1024 * 1024)
    }
  })

  it('目录不可写时不抛出', () => {
    const directory = temporaryDirectory()
    new DesktopLogger(directory).info('create')
    // The log directory path runs through an existing file, so it can never be created.
    const logger = new DesktopLogger(path.join(directory, 'desktop.log', 'nested'))
    expect(() => logger.error('cannot-write')).not.toThrow()
  })
})
