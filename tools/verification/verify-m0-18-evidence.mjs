import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { validateM018EvidenceContracts } from './m0-18-evidence.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const { liveEvidence } = validateM018EvidenceContracts(repositoryRoot)
const notRun = liveEvidence.filter((entry) => entry.status === 'NOT_RUN').map((entry) => entry.milestone)
console.log(`M0-18 证据契约与延期清单已通过；live NOT_RUN：${notRun.join(', ') || '无'}`)
