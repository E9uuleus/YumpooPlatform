import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

// Read-only inspection of the packaged Windows executable; the inspection libraries are desktop devDependencies.
const desktopRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'desktop', 'desktop-shell')
const desktopRequire = createRequire(path.join(desktopRoot, 'package.json'))
const { NtExecutable, NtExecutableResource, Data, Resource } = desktopRequire('resedit')

async function readResources(executable) {
  const exe = NtExecutable.from(await readFile(executable), { ignoreCert: true })
  return NtExecutableResource.from(exe)
}

export async function verifyExecutableIcons(executable, iconFile) {
  const resources = await readResources(executable)
  const icons = Data.IconFile.from(await readFile(iconFile)).icons.map(item => item.data)
  const groups = Resource.IconGroupEntry.fromEntries(resources.entries)
  if (!groups.length) throw new Error('Windows EXE 缺少图标资源组')
  for (const group of groups) {
    const actual = group.getIconItemsFromEntries(resources.entries)
    if (actual.length !== icons.length || actual.some((icon, index) =>
      !icon.isRaw() || !icons[index].isRaw() || (icon.width || 256) !== icons[index].width ||
      (icon.height || 256) !== icons[index].height || !Buffer.from(icon.bin).equals(Buffer.from(icons[index].bin)))) {
      throw new Error(`Windows EXE 图标资源与品牌母版不一致：${group.id}/${group.lang}`)
    }
  }
}

export async function readVersionStrings(executable) {
  const resources = await readResources(executable)
  const [versionInfo, ...others] = Resource.VersionInfo.fromEntries(resources.entries)
  if (!versionInfo || others.length) throw new Error('Windows EXE 版本资源数量不为 1')
  const [language, ...otherLanguages] = versionInfo.getAllLanguagesForStringValues()
  if (!language || otherLanguages.length) throw new Error('Windows EXE 版本资源语言数量不为 1')
  return versionInfo.getStringValues(language)
}

export async function readFuseWire(executable) {
  const fuses = await import(pathToFileURL(desktopRequire.resolve('@electron/fuses')).href)
  const wire = await fuses.getCurrentFuseWire(executable)
  return Object.fromEntries(Object.entries(fuses.FuseV1Options)
    .filter(([name]) => Number.isNaN(Number(name)))
    .map(([name, index]) => [name, wire[index] === fuses.FuseState.ENABLE ? true
      : wire[index] === fuses.FuseState.DISABLE ? false : 'unset']))
}
