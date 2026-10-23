const fs = require('node:fs')
const path = require('node:path')

const dist = path.join(__dirname, '..', 'dist')
const inventoryFile = path.join(dist, 'bundled-packages.json')
const licensesFile = path.join(dist, 'diagnostics', 'menu-licenses.json')
const licenses = JSON.parse(fs.readFileSync(licensesFile, 'utf8'))
const inventory = new Map(JSON.parse(fs.readFileSync(inventoryFile, 'utf8')).map(entry => [`${entry.name}@${entry.version}`, entry]))
for (const entry of licenses) {
  if (!entry.name || !entry.version || !entry.identifier || !entry.text) throw new Error('菜单依赖缺少许可证信息')
  inventory.set(`${entry.name}@${entry.version}`, { name: entry.name, version: entry.version, license: entry.identifier })
}
const fonts = path.join(__dirname, '../../..', 'frontend/web-app/src/assets/fonts')
const notices = licenses.map(entry => `${entry.name}@${entry.version} (${entry.identifier})\n\n${entry.text}`)
for (const name of fs.readdirSync(fonts).filter(name => /^OFL-.*\.txt$/.test(name))) {
  notices.push(`${name}\n\n${fs.readFileSync(path.join(fonts, name), 'utf8')}`)
}
fs.appendFileSync(path.join(dist, 'main', 'THIRD_PARTY_LICENSES.txt'), `\n\nPackaged tray menu and fonts\n\n${notices.join('\n\n----------------------------------------\n\n')}\n`, 'utf8')
fs.writeFileSync(inventoryFile, `${JSON.stringify([...inventory.values()].sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version)), null, 2)}\n`, 'utf8')
fs.unlinkSync(licensesFile)
