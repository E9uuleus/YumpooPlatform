import { readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { crc32 } from 'node:zlib'

export interface BundleEntry {
  readonly name: string
  readonly data: Buffer
}

const MAX_DUMPS = 5
const MAX_DUMP_BYTES = 20 * 1024 * 1024
const MAX_BUNDLE_BYTES = 100 * 1024 * 1024

/**
 * The bundle a person sends to an administrator: the diagnostics report, the rotated logs and the newest
 * local crash dumps. Logs are already redacted when written; no cookie, credential or preference file is read.
 */
export function collectBundleEntries(options: { logDirectory: string; crashDirectory: string; reportJson: string }): BundleEntry[] {
  const entries: BundleEntry[] = [{ name: 'diagnostics.json', data: Buffer.from(options.reportJson, 'utf8') }]
  let total = entries[0]!.data.byteLength
  for (const file of listFiles(options.logDirectory, name => /^desktop(?:\.\d)?\.log$/u.test(name), false)) {
    const data = readFileSync(file.path)
    if (total + data.byteLength > MAX_BUNDLE_BYTES) break
    entries.push({ name: `logs/${path.basename(file.path)}`, data })
    total += data.byteLength
  }
  const dumps = listFiles(options.crashDirectory, name => name.toLowerCase().endsWith('.dmp'), true)
    .filter(file => file.size <= MAX_DUMP_BYTES)
    .sort((left, right) => right.modified - left.modified)
    .slice(0, MAX_DUMPS)
  for (const file of dumps) {
    if (total + file.size > MAX_BUNDLE_BYTES) break
    entries.push({ name: `crashes/${path.basename(file.path)}`, data: readFileSync(file.path) })
    total += file.size
  }
  return entries
}

function listFiles(directory: string, include: (name: string) => boolean, recursive: boolean): Array<{ path: string; size: number; modified: number }> {
  let names: string[]
  try {
    names = readdirSync(directory)
  } catch {
    return []
  }
  const files: Array<{ path: string; size: number; modified: number }> = []
  for (const name of names.sort()) {
    const candidate = path.join(directory, name)
    let metadata
    try {
      metadata = statSync(candidate)
    } catch {
      continue
    }
    if (metadata.isDirectory()) {
      if (recursive) files.push(...listFiles(candidate, include, true))
    } else if (metadata.isFile() && include(name)) {
      files.push({ path: candidate, size: metadata.size, modified: metadata.mtimeMs })
    }
  }
  return files
}

/** Minimal store-only ZIP (no compression, UTF-8 names) so exporting needs no extra runtime dependency. */
export function createZip(entries: readonly BundleEntry[], date: Date = new Date()): Buffer {
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2)
  const dosDate = ((Math.max(date.getFullYear(), 1980) - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8')
    const checksum = crc32(entry.data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6)
    local.writeUInt16LE(0, 8)
    local.writeUInt16LE(dosTime, 10)
    local.writeUInt16LE(dosDate, 12)
    local.writeUInt32LE(checksum, 14)
    local.writeUInt32LE(entry.data.byteLength, 18)
    local.writeUInt32LE(entry.data.byteLength, 22)
    local.writeUInt16LE(name.byteLength, 26)
    local.writeUInt16LE(0, 28)
    locals.push(local, name, entry.data)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt16LE(0, 10)
    central.writeUInt16LE(dosTime, 12)
    central.writeUInt16LE(dosDate, 14)
    central.writeUInt32LE(checksum, 16)
    central.writeUInt32LE(entry.data.byteLength, 20)
    central.writeUInt32LE(entry.data.byteLength, 24)
    central.writeUInt16LE(name.byteLength, 28)
    central.writeUInt32LE(offset, 42)
    centrals.push(central, name)
    offset += local.byteLength + name.byteLength + entry.data.byteLength
  }
  const directory = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(directory.byteLength, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}
