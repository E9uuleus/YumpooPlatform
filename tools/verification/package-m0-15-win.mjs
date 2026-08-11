import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { lstat, readFile, readdir, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { runPnpmSync } from './process-utils.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const desktopRoot = path.join(repositoryRoot, 'desktop', 'desktop-shell')
const outputRoot = path.join(desktopRoot, 'out')
const packageJsonPath = path.join(desktopRoot, 'package.json')
const asarModuleUrl = pathToFileURL(
  path.join(desktopRoot, 'node_modules', '@electron', 'asar', 'lib', 'asar.js'),
).href
const EXPECTED_ASAR_ENTRIES = new Set([
  'dist',
  'dist/main',
  'dist/main/auth-ipc.js',
  'dist/main/desktop-auth.js',
  'dist/main/index.js',
  'dist/main/protocol-client.js',
  'dist/main/security-guards.js',
  'dist/main/url-policy.js',
  'dist/main/window-policy.js',
  'dist/preload',
  'dist/preload/index.js',
  'package.json',
])

if (process.platform !== 'win32') {
  throw new Error('M0-15 Windows 产物只能在 Windows 上打包和扫描')
}

const electronVersion = await readElectronVersion()
const { listPackage } = await import(asarModuleUrl)

runPnpmSync(['--filter', '@yumpoo/desktop-shell', 'run', 'package:win'], {
  cwd: repositoryRoot,
})

const packageDirectories = (await readdir(outputRoot, { withFileTypes: true }))
  .filter((entry) => entry.isDirectory() && entry.name.endsWith('-win32-x64'))
  .map((entry) => path.join(outputRoot, entry.name))

if (packageDirectories.length !== 1) {
  throw new Error(`预期一个 Windows x64 打包目录，实际为 ${packageDirectories.length}`)
}

const packageDirectory = packageDirectories[0]
const executable = path.join(packageDirectory, 'YumpooDesktop.exe')
const asarArchive = path.join(packageDirectory, 'resources', 'app.asar')
await requireFile(executable)
await requireFile(asarArchive)

const asarEntries = new Set(
  listPackage(asarArchive, { isPack: false })
    .map((entry) => entry.trim().replaceAll('\\', '/').replace(/^\/+/, ''))
    .filter(Boolean),
)
const missingEntries = [...EXPECTED_ASAR_ENTRIES].filter(
  (entry) => !asarEntries.has(entry),
)
const unexpectedEntries = [...asarEntries].filter(
  (entry) => !EXPECTED_ASAR_ENTRIES.has(entry),
)
if (missingEntries.length > 0 || unexpectedEntries.length > 0) {
  throw new Error(
    `Electron app.asar 白名单不匹配；缺少：${missingEntries.join(', ') || '无'}；多出：${unexpectedEntries.join(', ') || '无'}`,
  )
}

const packagedFiles = await collectPackagedFiles(packageDirectory)
const artifactFiles = await Promise.all(
  packagedFiles.map(async (file) => ({
    path: path.relative(packageDirectory, file).replaceAll('\\', '/'),
    bytes: (await stat(file)).size,
    sha256: await sha256(file),
  })),
)
const manifest = {
  schemaVersion: 1,
  milestone: 'M0-15',
  generatedAt: new Date().toISOString(),
  platform: 'win32',
  arch: 'x64',
  electronVersion,
  packageDirectory: path.basename(packageDirectory),
  files: artifactFiles,
}
const manifestPath = path.join(outputRoot, 'm0-15-artifact-manifest.json')
await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

console.log(
  `M0-15 Windows x64 产物已通过白名单扫描：${path.relative(repositoryRoot, manifestPath)}`,
)

async function requireFile(file) {
  const metadata = await stat(file).catch(() => undefined)
  if (!metadata?.isFile()) {
    throw new Error(`打包产物缺少文件：${path.basename(file)}`)
  }
}

async function readElectronVersion() {
  let packageJson
  try {
    packageJson = JSON.parse(await readFile(packageJsonPath, 'utf8'))
  } catch {
    throw new Error('无法读取 Electron package.json')
  }
  const version = packageJson?.devDependencies?.electron
  if (
    typeof version !== 'string' ||
    !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u.test(version)
  ) {
    throw new Error('Electron 版本必须在 desktop package.json 中精确锁定')
  }
  return version
}

async function collectPackagedFiles(root) {
  const files = []

  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true })
    entries.sort((left, right) => left.name.localeCompare(right.name, 'en'))
    for (const entry of entries) {
      const candidate = path.join(directory, entry.name)
      const metadata = await lstat(candidate)
      if (metadata.isSymbolicLink()) {
        throw new Error(
          `Windows x64 产物不得包含符号链接：${path.relative(root, candidate)}`,
        )
      }
      if (metadata.isDirectory()) {
        await visit(candidate)
      } else if (metadata.isFile()) {
        files.push(candidate)
      } else {
        throw new Error(
          `Windows x64 产物含不支持的文件类型：${path.relative(root, candidate)}`,
        )
      }
    }
  }

  await visit(root)
  return files
}

function sha256(file) {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256')
    const input = createReadStream(file)
    input.on('error', reject)
    input.on('data', (chunk) => hash.update(chunk))
    input.on('end', () => resolve(hash.digest('hex')))
  })
}
