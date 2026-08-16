import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { validateM113Evidence } from './m1-13-evidence.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const requireReport = process.argv.includes('--require-report')
const { liveEvidence } = validateM113Evidence(repositoryRoot, { requireReport })
const pending = liveEvidence
  .filter((entry) => entry.status !== 'PASS')
  .map((entry) => `${entry.milestone}:${entry.status}`)

console.log(`M1-13 acceptance evidence validated; M6-01 pending: ${pending.join(', ') || 'none'}`)
