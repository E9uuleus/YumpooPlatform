import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {
  blockmapName, desktopArtifactFileNames, desktopManifestName, desktopSbomComponent, desktopSbomName, installerName,
  requireUpdateDistribution, validateDesktopManifest, validateDesktopSbom, validateLatestYml,
} from '../release/desktop-policy.mjs'

// Release tests import this module, and release jobs install no packages: Node built-ins only.
function gitHead(root) {
  const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim()
  assert(/^[0-9a-f]{40}$/u.test(commit), 'Invalid Git HEAD')
  return commit
}

// Collects the desktop release artifact from the tested desktop output into out/desktop: the exact installer the
// installer smoke ran, its blockmap and latest.yml, plus an SBOM and a manifest. CI attests and uploads this directory;
// the desktop draft takes these bytes and nothing else.
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const desktopRoot = path.join(repositoryRoot, 'desktop', 'desktop-shell')

export function desktopSbom({ version, electronVersion, bundled, sourceCommit }) {
  const purl = (name, packageVersion) => `pkg:npm/${name.startsWith('@') ? `%40${name.slice(1)}` : name}@${packageVersion}`
  const component = ({ name, version: packageVersion, license }) => {
    const scoped = name.match(/^(@[^/]+)\/(.+)$/u)
    return {
      type: 'library',
      ...(scoped ? { group: scoped[1], name: scoped[2] } : { name }),
      version: packageVersion,
      purl: purl(name, packageVersion),
      ...(license ? { licenses: [{ license: /^[A-Za-z0-9.+-]+$/u.test(license) ? { id: license } : { name: license } }] } : {}),
    }
  }
  // Deterministic serial number: the same commit and version always describe the same bill of materials.
  const hash = createHash('sha256').update(`${sourceCommit}:${version}`).digest('hex')
  const serial = `${hash.slice(0, 8)}-${hash.slice(8, 12)}-5${hash.slice(13, 16)}-a${hash.slice(17, 20)}-${hash.slice(20, 32)}`
  return {
    bomFormat: 'CycloneDX',
    specVersion: '1.6',
    serialNumber: `urn:uuid:${serial}`,
    version: 1,
    metadata: {
      component: { type: 'application', ...desktopSbomComponent, version, purl: purl(`${desktopSbomComponent.group}/${desktopSbomComponent.name}`, version) },
      properties: [{ name: 'yumpoo:sourceCommit', value: sourceCommit }],
    },
    components: [
      { type: 'framework', name: 'electron', version: electronVersion, purl: purl('electron', electronVersion), licenses: [{ license: { id: 'MIT' } }],
        description: 'Electron runtime shipped inside the installer (Chromium, Node.js)' },
      ...bundled.map(component),
    ],
  }
}

export function collectDesktopRelease({ sourceRoot, outputRoot, sourceCommit, distribution, bundled }) {
  const descriptor = JSON.parse(fs.readFileSync(path.join(sourceRoot, 'desktop-installer.json'), 'utf8'))
  assert.equal(descriptor.sourceCommit, sourceCommit, 'Desktop installer was built from another commit')
  requireUpdateDistribution(distribution)
  const { version, electronVersion } = descriptor
  assert.equal(descriptor.installer.name, installerName(version), 'Unexpected installer name')
  fs.rmSync(outputRoot, { recursive: true, force: true })
  fs.mkdirSync(outputRoot, { recursive: true })
  for (const name of [installerName(version), blockmapName(version), 'latest.yml']) {
    const source = path.join(sourceRoot, name)
    assert(fs.lstatSync(source).isFile(), `Missing desktop build output: ${name}`)
    fs.copyFileSync(source, path.join(outputRoot, name))
  }
  const sbom = desktopSbom({ version, electronVersion, bundled, sourceCommit })
  validateDesktopSbom(sbom, { version, electronVersion })
  fs.writeFileSync(path.join(outputRoot, desktopSbomName), `${JSON.stringify(sbom, null, 2)}\n`, 'utf8')
  const files = describeFiles(outputRoot, desktopArtifactFileNames(version).filter(name => name !== desktopManifestName))
  const installer = files.get(installerName(version))
  assert.equal(installer.sha256, descriptor.installer.sha256, 'Installer differs from the one the installer smoke tested')
  validateLatestYml(fs.readFileSync(path.join(outputRoot, 'latest.yml'), 'utf8'), { version, installer: { name: installerName(version), ...installer } })
  const manifest = {
    schemaVersion: 1,
    sourceCommit,
    version,
    electronVersion,
    channel: distribution.update.channel,
    feed: distribution.update.feed,
    installer: installerName(version),
    files: [...files].map(([name, file]) => ({ name, bytes: file.bytes, sha256: file.sha256, ...(name === installerName(version) ? { sha512: file.sha512 } : {}) })),
  }
  validateDesktopManifest(manifest, { sourceCommit, version, distribution, files })
  fs.writeFileSync(path.join(outputRoot, desktopManifestName), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  assert.deepEqual(fs.readdirSync(outputRoot).sort(), [...desktopArtifactFileNames(version)].sort(), 'Unexpected files in the desktop release directory')
  return manifest
}

export function describeFiles(directory, names) {
  return new Map(names.map(name => {
    const data = fs.readFileSync(path.join(directory, name))
    return [name, {
      bytes: data.byteLength,
      sha256: createHash('sha256').update(data).digest('hex'),
      sha512: createHash('sha512').update(data).digest('base64'),
    }]
  }))
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const sourceRoot = path.resolve(process.env.YUMPOO_DESKTOP_OUTPUT_ROOT ?? path.join(desktopRoot, 'out', 'release'))
  const outputRoot = path.join(repositoryRoot, 'out', 'desktop')
  const manifest = collectDesktopRelease({
    sourceRoot,
    outputRoot,
    sourceCommit: gitHead(repositoryRoot),
    distribution: JSON.parse(fs.readFileSync(path.join(desktopRoot, 'distribution', 'production.json'), 'utf8')),
    bundled: JSON.parse(fs.readFileSync(path.join(desktopRoot, 'dist', 'bundled-packages.json'), 'utf8')),
  })
  console.log(`桌面发布产物已收集：out/desktop（${manifest.installer}，渠道 ${manifest.channel}）`)
}
