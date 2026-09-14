const { readFile, writeFile } = require('node:fs/promises')
const { NtExecutable, NtExecutableResource, Data, Resource } = require('resedit')

async function readIcons(executable, iconFile) {
  const exe = NtExecutable.from(await readFile(executable), { ignoreCert: true })
  const resources = NtExecutableResource.from(exe)
  const icons = Data.IconFile.from(await readFile(iconFile)).icons.map(item => item.data)
  const groups = Resource.IconGroupEntry.fromEntries(resources.entries)
  if (!groups.length) throw new Error('Windows EXE 缺少图标资源组')
  return { exe, resources, icons, groups }
}

async function replaceExecutableIcons(executable, iconFile) {
  const { exe, resources, icons, groups } = await readIcons(executable, iconFile)
  for (const group of groups) {
    Resource.IconGroupEntry.replaceIconsForResource(resources.entries, group.id, group.lang, icons)
  }
  resources.outputResource(exe)
  await writeFile(executable, Buffer.from(exe.generate()))
}

async function verifyExecutableIcons(executable, iconFile) {
  const { resources, icons, groups } = await readIcons(executable, iconFile)
  for (const group of groups) {
    const actual = group.getIconItemsFromEntries(resources.entries)
    if (actual.length !== icons.length || actual.some((icon, index) =>
      !icon.isRaw() || !icons[index].isRaw() || (icon.width || 256) !== icons[index].width ||
      (icon.height || 256) !== icons[index].height || !Buffer.from(icon.bin).equals(Buffer.from(icons[index].bin)))) {
      throw new Error(`Windows EXE 图标资源与品牌母版不一致：${group.id}/${group.lang}`)
    }
  }
}

module.exports = { replaceExecutableIcons, verifyExecutableIcons }
