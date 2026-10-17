const fs = require('node:fs')
const path = require('node:path')

// The diagnostics page ships as static files next to its compiled script; nothing else from src is copied.
const source = path.join(__dirname, '..', 'src', 'diagnostics')
const target = path.join(__dirname, '..', 'dist', 'diagnostics')
fs.mkdirSync(target, { recursive: true })
for (const name of ['diagnostics.html', 'diagnostics.css']) {
  fs.copyFileSync(path.join(source, name), path.join(target, name))
}
