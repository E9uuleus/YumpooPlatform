import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { lstat, readdir, stat } from 'node:fs/promises'
import path from 'node:path'

export async function collectRegularFiles(root) {
  const files = []

  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true })
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'))
    for (const entry of entries) {
      const candidate = path.join(directory, entry.name)
      const metadata = await lstat(candidate)
      if (metadata.isSymbolicLink()) {
        throw new Error(`M0-16 产物不得包含符号链接：${path.relative(root, candidate)}`)
      }
      if (metadata.isDirectory()) {
        await visit(candidate)
      } else if (metadata.isFile()) {
        files.push(candidate)
      } else {
        throw new Error(`M0-16 产物包含不支持的文件类型：${path.relative(root, candidate)}`)
      }
    }
  }

  await visit(root)
  return files
}

export async function fileRecord(root, file) {
  return {
    path: path.relative(root, file).replaceAll('\\', '/'),
    bytes: (await stat(file)).size,
    sha256: await sha256(file),
  }
}

export function sha256(file) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    const input = createReadStream(file)
    input.on('error', reject)
    input.on('data', (chunk) => hash.update(chunk))
    input.on('end', () => resolve(hash.digest('hex')))
  })
}

export function quotePowerShellLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

export function assertM016(condition, message) {
  if (!condition) {
    throw new Error(`M0-16 验证失败：${message}`)
  }
}
