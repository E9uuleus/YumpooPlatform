import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { crc32 } from 'node:zlib'
import { afterEach, describe, expect, it } from 'vitest'
import { collectBundleEntries, createZip } from '../src/main/diagnostic-bundle'

let directory: string | undefined
afterEach(() => { if (directory) rmSync(directory, { recursive: true, force: true }) })

/** Reads a store-only ZIP back through its central directory. */
function readZip(zip: Buffer): Map<string, Buffer> {
  const end = zip.byteLength - 22
  expect(zip.readUInt32LE(end)).toBe(0x06054b50)
  const count = zip.readUInt16LE(end + 10)
  let central = zip.readUInt32LE(end + 16)
  const files = new Map<string, Buffer>()
  for (let index = 0; index < count; index += 1) {
    expect(zip.readUInt32LE(central)).toBe(0x02014b50)
    const size = zip.readUInt32LE(central + 24)
    const nameLength = zip.readUInt16LE(central + 28)
    const offset = zip.readUInt32LE(central + 42)
    const name = zip.subarray(central + 46, central + 46 + nameLength).toString('utf8')
    expect(zip.readUInt32LE(offset)).toBe(0x04034b50)
    const dataStart = offset + 30 + zip.readUInt16LE(offset + 26)
    const data = zip.subarray(dataStart, dataStart + size)
    expect(crc32(data)).toBe(zip.readUInt32LE(central + 16))
    files.set(name, data)
    central += 46 + nameLength
  }
  return files
}

describe('诊断包', () => {
  it('ZIP 可按中央目录完整读回，文件名使用 UTF-8', () => {
    const zip = createZip([
      { name: 'diagnostics.json', data: Buffer.from('{}') },
      { name: 'logs/桌面.log', data: Buffer.from('line\n') },
      { name: 'empty.txt', data: Buffer.alloc(0) },
    ], new Date(2026, 9, 17, 12, 30, 10))
    const files = readZip(zip)
    expect([...files.keys()]).toEqual(['diagnostics.json', 'logs/桌面.log', 'empty.txt'])
    expect(files.get('logs/桌面.log')?.toString()).toBe('line\n')
  })

  it('只收集报告、轮转日志和最新五个崩溃转储', () => {
    directory = mkdtempSync(path.join(os.tmpdir(), 'yumpoo-bundle-'))
    const logs = path.join(directory, 'logs')
    const crashes = path.join(directory, 'Crashpad')
    mkdirSync(logs)
    mkdirSync(path.join(crashes, 'reports'), { recursive: true })
    for (const name of ['desktop.log', 'desktop.1.log', 'desktop.2.log', 'other.log', 'desktop.log.tmp']) writeFileSync(path.join(logs, name), name)
    for (let index = 0; index < 7; index += 1) {
      const file = path.join(crashes, 'reports', `dump-${index}.dmp`)
      writeFileSync(file, `dump ${index}`)
      utimesSync(file, new Date(2026, 0, 1 + index), new Date(2026, 0, 1 + index))
    }
    writeFileSync(path.join(crashes, 'settings.dat'), 'not a dump')

    const entries = collectBundleEntries({ logDirectory: logs, crashDirectory: crashes, reportJson: '{"schemaVersion":1}' })
    expect(entries.map(entry => entry.name)).toEqual([
      'diagnostics.json',
      'logs/desktop.1.log', 'logs/desktop.2.log', 'logs/desktop.log',
      'crashes/dump-6.dmp', 'crashes/dump-5.dmp', 'crashes/dump-4.dmp', 'crashes/dump-3.dmp', 'crashes/dump-2.dmp',
    ])
  })

  it('目录缺失时只包含报告', () => {
    expect(collectBundleEntries({ logDirectory: '/missing/logs', crashDirectory: '/missing/crashes', reportJson: '{}' })
      .map(entry => entry.name)).toEqual(['diagnostics.json'])
  })
})
