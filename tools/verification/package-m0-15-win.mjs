import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { lstat, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { gitHead } from './m0-18-utils.mjs'
import { runPnpmSync } from './process-utils.mjs'
import { readFuseWire, readVersionStrings, verifyExecutableIcons } from './desktop-executable.mjs'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const desktopRoot = path.join(repositoryRoot, 'desktop', 'desktop-shell')
const desktopOutputRoot = path.join(desktopRoot, 'out')
const outputRoot = process.env.YUMPOO_M015_OUTPUT_ROOT
  ? path.resolve(process.env.YUMPOO_M015_OUTPUT_ROOT)
  : path.join(
      desktopOutputRoot,
      `.m0-15-package-${process.pid}-${Date.now()}`,
    )
const packageJsonPath = path.join(desktopRoot, 'package.json')
const asarModuleUrl = pathToFileURL(
  path.join(desktopRoot, 'node_modules', '@electron', 'asar', 'lib', 'asar.js'),
).href
const PACKAGE_DIRECTORY = 'win-unpacked'
const INSTALLER_DESCRIPTOR = 'desktop-installer.json'
// The main process is one esbuild bundle; its inlined dependencies' notices ship as THIRD_PARTY_LICENSES.txt.
const EXPECTED_ASAR_ENTRIES = new Set([
  'assets',
  'assets/application.png',
  'dist',
  'dist/diagnostics',
  'dist/diagnostics/diagnostics.css',
  'dist/diagnostics/diagnostics.html',
  'dist/diagnostics/page.js',
  'dist/main',
  'dist/main/THIRD_PARTY_LICENSES.txt',
  'dist/main/distribution.json',
  'dist/main/index.js',
  'dist/preload',
  'dist/preload/diagnostics.js',
  'dist/preload/index.js',
  'package.json',
])
const EXPECTED_RESOURCES = ['app.asar']
// Hardening fuses; the others keep Electron defaults.
const EXPECTED_FUSES = {
  RunAsNode: false,
  EnableCookieEncryption: true,
  EnableNodeOptionsEnvironmentVariable: false,
  EnableNodeCliInspectArguments: false,
  EnableEmbeddedAsarIntegrityValidation: true,
  OnlyLoadAppFromAsar: true,
  GrantFileProtocolExtraPrivileges: false,
}

if (process.platform !== 'win32') {
  throw new Error('M0-15 Windows 产物只能在 Windows 上打包和扫描')
}
if (
  outputRoot !== desktopOutputRoot &&
  !outputRoot.startsWith(`${desktopOutputRoot}${path.sep}`)
) {
  throw new Error('M0-15 打包输出路径必须位于 desktop out 目录内')
}

const { electronVersion, desktopVersion } = await readDesktopVersions()
const { listPackage, extractFile } = await import(asarModuleUrl)

// A reused output root (the Windows gate uses a fixed one) must not leave an older installer to be picked up.
for (const entry of await readdir(outputRoot).catch(() => [])) {
  if (entry === PACKAGE_DIRECTORY || /^YumpooDesktop-Setup-.+\.exe(?:\.blockmap)?$/u.test(entry)
    || ['m0-15-artifact-manifest.json', INSTALLER_DESCRIPTOR].includes(entry) || /^builder-.+\.ya?ml$/u.test(entry)) {
    await rm(path.join(outputRoot, entry), { recursive: true, force: true })
  }
}

runPnpmSync(['--filter', '@yumpoo/desktop-shell', 'run', 'package:win'], {
  cwd: repositoryRoot,
  env: {
    ...process.env,
    YUMPOO_M015_OUTPUT_ROOT: outputRoot,
  },
})

const packageDirectory = path.join(outputRoot, PACKAGE_DIRECTORY)
const installerName = `YumpooDesktop-Setup-${desktopVersion}-x64.exe`
const installers = (await readdir(outputRoot, { withFileTypes: true }))
  .filter((entry) => entry.isFile() && /^YumpooDesktop-Setup-.+\.exe$/u.test(entry.name))
  .map((entry) => entry.name)
if (installers.length !== 1 || installers[0] !== installerName) {
  throw new Error(`预期唯一安装包 ${installerName}，实际为 ${installers.join(', ') || '无'}`)
}
const installer = path.join(outputRoot, installerName)
const executable = path.join(packageDirectory, 'YumpooDesktop.exe')
const asarArchive = path.join(packageDirectory, 'resources', 'app.asar')
await requireFile(executable)
await requireFile(asarArchive)
await requireFile(installer)
await requireFile(`${installer}.blockmap`)
await verifyExecutableIcons(executable, path.join(desktopRoot, 'assets', 'application.ico'))
await verifyVersionStrings(executable)
await verifyFuses(executable)
const trackedDistribution = JSON.parse(await readFile(path.join(desktopRoot, 'distribution', 'production.json'), 'utf8'))
// app-update.yml exists only once the production distribution names an update feed.
const expectedResources = trackedDistribution.update ? [...EXPECTED_RESOURCES, 'app-update.yml'].sort() : EXPECTED_RESOURCES
const resources = (await readdir(path.join(packageDirectory, 'resources'))).sort()
if (resources.join() !== expectedResources.join()) {
  throw new Error(`Windows x64 resources 目录白名单不匹配：${resources.join(', ')}`)
}
if (!extractFile(asarArchive, 'assets/application.png').equals(await readFile(path.join(desktopRoot, 'assets', 'application.png')))) {
  throw new Error('Electron app.asar 中的窗口和托盘图标与品牌母版不一致')
}

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
const packagedDistribution = JSON.parse(extractFile(asarArchive, path.join('dist', 'main', 'distribution.json')).toString('utf8'))
if (JSON.stringify(packagedDistribution) !== JSON.stringify(trackedDistribution)) {
  throw new Error('app.asar 中的分发配置与 distribution/production.json 不一致')
}
const packagedManifest = JSON.parse(extractFile(asarArchive, 'package.json').toString('utf8'))
if (packagedManifest.dependencies || packagedManifest.devDependencies || packagedManifest.scripts) {
  throw new Error('app.asar 中的 package.json 不得携带依赖或脚本声明')
}

const sourceCommit = gitHead(repositoryRoot)
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
  sourceCommit,
  platform: 'win32',
  arch: 'x64',
  electronVersion,
  packageDirectory: PACKAGE_DIRECTORY,
  files: artifactFiles,
}
const manifestPath = path.join(outputRoot, 'm0-15-artifact-manifest.json')
await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
const descriptor = {
  schemaVersion: 1,
  sourceCommit,
  version: desktopVersion,
  electronVersion,
  installer: { name: installerName, bytes: (await stat(installer)).size, sha256: await sha256(installer) },
}
await writeFile(path.join(outputRoot, INSTALLER_DESCRIPTOR), `${JSON.stringify(descriptor, null, 2)}\n`, 'utf8')

console.log(
  `M0-15 Windows x64 产物已通过白名单扫描：${path.relative(repositoryRoot, manifestPath)}；安装包 ${installerName} sha256=${descriptor.installer.sha256}`,
)

async function verifyVersionStrings(file) {
  const strings = await readVersionStrings(file)
  const expected = {
    CompanyName: 'Yumpoo',
    FileDescription: 'Yumpoo Desktop',
    ProductName: 'Yumpoo Desktop',
    InternalName: 'YumpooDesktop',
    FileVersion: desktopVersion,
  }
  for (const [name, value] of Object.entries(expected)) {
    if (strings[name] !== value) {
      throw new Error(`Windows EXE 版本资源 ${name}=${strings[name] ?? '缺失'}，应为 ${value}`)
    }
  }
  if (!String(strings.ProductVersion).startsWith(desktopVersion)) {
    throw new Error(`Windows EXE 版本资源 ProductVersion=${strings.ProductVersion}，应以 ${desktopVersion} 开头`)
  }
  if (Object.values(strings).some((value) => /electron|github/iu.test(String(value)))) {
    throw new Error('Windows EXE 版本资源仍含 Electron 或 GitHub 默认值')
  }
}

async function verifyFuses(file) {
  const fuses = await readFuseWire(file)
  for (const [name, value] of Object.entries(EXPECTED_FUSES)) {
    if (fuses[name] !== value) {
      throw new Error(`Electron fuse ${name}=${fuses[name]}，应为 ${value}`)
    }
  }
}

async function requireFile(file) {
  const metadata = await stat(file).catch(() => undefined)
  if (!metadata?.isFile()) {
    throw new Error(`打包产物缺少文件：${path.basename(file)}`)
  }
}

async function readDesktopVersions() {
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
  if (typeof packageJson.version !== 'string' || !/^\d+\.\d+\.\d+$/u.test(packageJson.version)) {
    throw new Error('desktop package.json version 必须是 X.Y.Z')
  }
  return { electronVersion: version, desktopVersion: packageJson.version }
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
