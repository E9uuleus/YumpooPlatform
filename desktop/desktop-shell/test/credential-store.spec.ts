import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearSessionCookies,
  DesktopCredentialStore,
  installSessionCookies,
} from '../src/main/credential-store'

const created: string[] = []
const bundle = {
  sessionCredential: 's'.repeat(43),
  csrfCredential: 'c'.repeat(43),
  absoluteExpiresAt: '2030-01-01T00:00:00Z',
}

afterEach(async () => {
  await Promise.all(created.splice(0).map((directory) => rm(directory, { recursive: true, force: true })))
})

describe('Electron 凭据加密存储', () => {
  it('磁盘文件只保存 safeStorage 密文并可恢复', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'yumpoo-credential-'))
    created.push(directory)
    const storage = {
      isEncryptionAvailable: () => true,
      encryptString: (value: string) => Buffer.from(`encrypted:${Buffer.from(value).toString('base64')}`),
      decryptString: (value: Buffer) => Buffer.from(value.toString().slice('encrypted:'.length), 'base64').toString(),
    }
    const store = new DesktopCredentialStore(directory, storage)

    await store.save(bundle, 'https://wecom-dev.yumpoo.com')

    const persisted = await readFile(path.join(directory, 'auth', 'electron-session.bin'), 'utf8')
    expect(persisted).not.toContain(bundle.sessionCredential)
    expect(persisted).not.toContain(bundle.csrfCredential)
    expect(await store.load('https://wecom-dev.yumpoo.com/path', Date.parse('2029-01-01T00:00:00Z'))).toEqual(bundle)
  })

  it('过期或无法解密时失败关闭并删除材料', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'yumpoo-credential-'))
    created.push(directory)
    const store = new DesktopCredentialStore(directory, {
      isEncryptionAvailable: () => true,
      encryptString: (value: string) => Buffer.from(value),
      decryptString: (value: Buffer) => value.toString(),
    })
    await store.save(bundle, 'https://wecom-dev.yumpoo.com')

    expect(await store.load('https://wecom-dev.yumpoo.com', Date.parse('2031-01-01T00:00:00Z'))).toBeUndefined()
    await expect(readFile(path.join(directory, 'auth', 'electron-session.bin'))).rejects.toThrow()
  })

  it('origin 变化时删除旧材料且不注入 Cookie', async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'yumpoo-credential-'))
    created.push(directory)
    const store = new DesktopCredentialStore(directory, {
      isEncryptionAvailable: () => true,
      encryptString: (value: string) => Buffer.from(value),
      decryptString: (value: Buffer) => value.toString(),
    })
    await store.save(bundle, 'https://wecom-dev.yumpoo.com')

    expect(await store.load('https://other.yumpoo.com')).toBeUndefined()
    await expect(readFile(path.join(directory, 'auth', 'electron-session.bin'))).rejects.toThrow()
  })

  it('向非持久化会话写入并同步移除两枚 Cookie', async () => {
    const cookies = { set: vi.fn(async () => undefined), remove: vi.fn(async () => undefined) }

    await installSessionCookies(cookies as never, 'https://wecom-dev.yumpoo.com/path', bundle)
    expect(cookies.set).toHaveBeenCalledTimes(2)
    expect(cookies.set.mock.calls[0]?.[0]).toMatchObject({
      name: '__Host-yumpoo-session', secure: true, httpOnly: true, path: '/',
    })
    await clearSessionCookies(cookies as never, 'https://wecom-dev.yumpoo.com/path')
    expect(cookies.remove).toHaveBeenCalledWith(
      'https://wecom-dev.yumpoo.com/', '__Host-yumpoo-session',
    )
    expect(cookies.remove).toHaveBeenCalledWith(
      'https://wecom-dev.yumpoo.com/', '__Host-yumpoo-csrf',
    )
  })
})
