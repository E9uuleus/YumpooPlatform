const fs = require('node:fs')
const path = require('node:path')
// Runs after tsc so the build and the packaged runtime validate with the same parser.
const { DISTRIBUTION_FILE, parseDistribution } = require('../dist/main/distribution.js')

const name = process.env.YUMPOO_DISTRIBUTION || 'production'
if (!/^[a-z][a-z0-9-]{0,31}$/u.test(name)) {
  throw new Error(`YUMPOO_DISTRIBUTION 名称无效：${name}`)
}
const source = path.join(__dirname, '..', 'distribution', `${name}.json`)
const distribution = parseDistribution(JSON.parse(fs.readFileSync(source, 'utf8')))
fs.writeFileSync(
  path.join(__dirname, '..', 'dist', 'main', DISTRIBUTION_FILE),
  `${JSON.stringify(distribution, null, 2)}\n`,
  'utf8',
)
console.log(`桌面分发配置 ${name}：${distribution.origin}`)
