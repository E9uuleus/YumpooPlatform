const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const esbuild = require('esbuild')

// The main process ships as one bundle: runtime dependencies (electron-updater and its tree) are inlined, so the
// asar never carries node_modules and its whitelist stays exact. Types are checked separately by tsc.
const root = path.join(__dirname, '..')
const dist = path.join(root, 'dist')
const PERMISSIVE_LICENSES = new Set(['MIT', 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0'])
fs.rmSync(dist, { recursive: true, force: true })

const main = esbuild.buildSync({
  entryPoints: [path.join(root, 'src', 'main', 'index.ts')],
  outfile: path.join(dist, 'main', 'index.js'),
  bundle: true,
  platform: 'node',
  target: 'node24',
  format: 'cjs',
  external: ['electron'],
  legalComments: 'none',
  metafile: true,
  absWorkingDir: root,
  logLevel: 'warning',
})
const bundled = writeThirdPartyLicenses(main.metafile, path.join(dist, 'main', 'THIRD_PARTY_LICENSES.txt'))
// The release SBOM lists exactly these inlined packages plus the Electron runtime; the file sits outside the asar.
fs.writeFileSync(path.join(dist, 'bundled-packages.json'), `${JSON.stringify(bundled, null, 2)}\n`, 'utf8')
// Sandboxed preloads cannot require relative modules; each entry is already a single file plus type-only imports.
esbuild.buildSync({
  entryPoints: ['index', 'diagnostics', 'menu'].map(name => path.join(root, 'src', 'preload', `${name}.ts`)),
  outdir: path.join(dist, 'preload'),
  bundle: true,
  platform: 'node',
  target: 'node24',
  format: 'cjs',
  external: ['electron'],
  logLevel: 'warning',
})
esbuild.buildSync({
  entryPoints: [path.join(root, 'src', 'diagnostics', 'page.ts')],
  outfile: path.join(dist, 'diagnostics', 'page.js'),
  bundle: true,
  platform: 'browser',
  target: 'chrome140',
  format: 'iife',
  logLevel: 'warning',
})
for (const name of ['diagnostics.html', 'diagnostics.css', 'launch.html', 'launch.css']) {
  fs.copyFileSync(path.join(root, 'src', 'diagnostics', name), path.join(dist, 'diagnostics', name))
}
// The launch screen shows the same exported brand mark as the window and tray icon.
fs.copyFileSync(path.join(root, 'assets', 'application.png'), path.join(dist, 'diagnostics', 'mark.png'))

// The baked-in distribution is validated with the same parser the packaged runtime uses.
const parserFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-distribution-')), 'distribution.js')
esbuild.buildSync({ entryPoints: [path.join(root, 'src', 'main', 'distribution.ts')], outfile: parserFile, bundle: true, platform: 'node', format: 'cjs' })
const { DISTRIBUTION_FILE, parseDistribution } = require(parserFile)
fs.rmSync(path.dirname(parserFile), { recursive: true, force: true })
const name = process.env.YUMPOO_DISTRIBUTION || 'production'
if (!/^[a-z][a-z0-9-]{0,31}$/u.test(name)) throw new Error(`YUMPOO_DISTRIBUTION 名称无效：${name}`)
// Update end-to-end tests point at a generated file with throwaway keys; release builds always use a tracked one.
const source = process.env.YUMPOO_DISTRIBUTION_FILE ? path.resolve(process.env.YUMPOO_DISTRIBUTION_FILE) : path.join(root, 'distribution', `${name}.json`)
const distribution = parseDistribution(JSON.parse(fs.readFileSync(source, 'utf8')))
fs.writeFileSync(path.join(dist, 'main', DISTRIBUTION_FILE), `${JSON.stringify(distribution, null, 2)}\n`, 'utf8')
console.log(`桌面分发配置：${distribution.origin}${distribution.update ? `，更新源 ${distribution.update.feed}` : '，未启用应用内更新'}`)

/** Inlined packages lose their own LICENSE files, so their notices travel next to the bundle instead. */
function writeThirdPartyLicenses(metafile, target) {
  const packages = new Map()
  for (const input of Object.keys(metafile.inputs)) {
    if (!input.includes('node_modules')) continue
    let directory = path.dirname(path.resolve(root, input))
    while (!fs.existsSync(path.join(directory, 'package.json')) || !JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8')).name) {
      directory = path.dirname(directory)
    }
    const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'))
    const id = `${manifest.name}@${manifest.version}`
    if (packages.has(id)) continue
    const licenseFile = fs.readdirSync(directory).find(name => /^(licen[cs]e|copying)(\.(md|txt))?$/iu.test(name))
    if (!licenseFile && !PERMISSIVE_LICENSES.has(manifest.license)) throw new Error(`${id} 既没有许可证文件，也不是已知的宽松许可证，不能打包`)
    const author = typeof manifest.author === 'string' ? manifest.author : manifest.author?.name
    packages.set(id, {
      name: manifest.name,
      version: manifest.version,
      license: manifest.license,
      text: licenseFile
        ? fs.readFileSync(path.join(directory, licenseFile), 'utf8').trim()
        : `Licensed under ${manifest.license} as declared in its package.json${author ? ` by ${author}` : ''}; the package ships no license file.`,
    })
  }
  const sections = [...packages].sort(([left], [right]) => left.localeCompare(right))
    .map(([id, entry]) => `${id} (${entry.license})\n\n${entry.text}`)
  const separator = `\n\n${'-'.repeat(40)}\n\n`
  fs.writeFileSync(target, `Third-party software bundled into the Yumpoo Desktop main process.\n\n${sections.join(separator)}\n`, 'utf8')
  return [...packages.values()].map(({ name, version, license }) => ({ name, version, license }))
    .sort((left, right) => left.name.localeCompare(right.name) || left.version.localeCompare(right.version))
}
