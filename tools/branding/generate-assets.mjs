import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { Resvg } from '@resvg/resvg-js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const master = readFileSync(path.join(root, 'tools/branding/logo-master.svg'), 'utf8')
const render = (svg, width) => new Resvg(svg, { fitTo: { mode: 'width', value: width } }).render().asPng()
const texture = render(master, 512).toString('base64')
const logo = master.replace(/<image\b[^>]*\/>/u,
  `<image id="yp-brand-texture" x="125" y="123" width="1187" height="871" href="data:image/png;base64,${texture}"/>`)
const square = master.replace('125 123 1187 871', '125 -35 1187 1187')
const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
const images = sizes.map(size => render(square, size))
const directory = Buffer.alloc(6 + sizes.length * 16)
directory.writeUInt16LE(1, 2)
directory.writeUInt16LE(sizes.length, 4)
let offset = directory.length
for (const [index, size] of sizes.entries()) {
  const entry = 6 + index * 16
  directory[entry] = size % 256
  directory[entry + 1] = size % 256
  directory.writeUInt16LE(1, entry + 4)
  directory.writeUInt16LE(32, entry + 6)
  directory.writeUInt32LE(images[index].length, entry + 8)
  directory.writeUInt32LE(offset, entry + 12)
  offset += images[index].length
}
const ico = Buffer.concat([directory, ...images])
const outputs = new Map([
  ['frontend/web-app/src/assets/brand/logo.svg', Buffer.from(logo)],
  ['frontend/web-app/public/brand/favicon.svg', Buffer.from(logo.replace('125 123 1187 871', '125 -35 1187 1187'))],
  ['frontend/web-app/public/brand/favicon.ico', ico],
  ['desktop/desktop-shell/assets/application.png', images.at(-1)],
  ['desktop/desktop-shell/assets/application.ico', ico],
])
for (const [relative, bytes] of outputs) {
  const target = path.join(root, relative)
  if (process.argv.includes('--check')) {
    if (!readFileSync(target).equals(bytes)) throw new Error(`品牌资产与母版不一致：${relative}；请运行 pnpm generate:brand-assets`)
  } else {
    mkdirSync(path.dirname(target), { recursive: true })
    writeFileSync(target, bytes)
  }
}
console.log(`Brand assets ${process.argv.includes('--check') ? 'verified' : 'generated'}: ${outputs.size} files; ICO sizes: ${sizes.join(', ')}`)
