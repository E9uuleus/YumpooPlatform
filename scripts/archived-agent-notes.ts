import { createHash } from 'node:crypto'
import {
  NOTE_FILENAME_PATTERN,
  isAgentNoteClass,
  isValidCalendarDate,
  validateTextBuffer,
} from './agent-note-policy.ts'

export interface ArchiveManifest {
  version: 1
  files: Readonly<Record<string, string>>
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isArchivePath(path: string): boolean {
  const match = /^([^/]+)\/(.+\.md)$/.exec(path)
  if (match?.[1] === undefined || match[2] === undefined || !isAgentNoteClass(match[1])) return false
  const fileMatch = NOTE_FILENAME_PATTERN.exec(match[2])
  return fileMatch?.[1] !== undefined && isValidCalendarDate(fileMatch[1])
}

export function archiveContentHash(content: Buffer): string {
  return `sha256:${createHash('sha256').update(content).digest('hex')}`
}

export function parseArchiveManifest(content: string): ArchiveManifest {
  const value: unknown = JSON.parse(content)
  if (!isRecord(value)) throw new Error('expected a JSON object')
  if (Object.keys(value).sort().join(',') !== 'files,version') {
    throw new Error('expected exactly the fields `version` and `files`')
  }
  if (value.version !== 1) throw new Error('unsupported manifest version')
  if (!isRecord(value.files)) throw new Error('`files` must be an object')
  const files: Record<string, string> = {}
  for (const [path, hash] of Object.entries(value.files)) {
    if (!isArchivePath(path)) throw new Error(`invalid archived path ${JSON.stringify(path)}`)
    if (typeof hash !== 'string' || !/^sha256:[0-9a-f]{64}$/.test(hash)) {
      throw new Error(`invalid content hash for ${path}`)
    }
    files[path] = hash
  }
  return { version: 1, files }
}

export function renderArchiveManifest(files: Readonly<Record<string, string>>): string {
  return `${JSON.stringify({
    version: 1,
    files: Object.fromEntries(Object.entries(files).sort(([left], [right]) => left.localeCompare(right))),
  }, null, 2)}\n`
}

export function validateArchiveManifestExtension(
  baseline: ArchiveManifest,
  current: ArchiveManifest,
): string[] {
  const errors: string[] = []
  for (const [path, expected] of Object.entries(baseline.files)) {
    const actual = current.files[path]
    if (actual === undefined) errors.push(`${path}: sealed manifest entry is missing`)
    else if (actual !== expected) errors.push(`${path}: sealed manifest hash changed`)
  }
  return errors
}

export function validateArchiveArtifacts(artifacts: ReadonlyMap<string, Buffer>): string[] {
  const errors: string[] = []
  for (const [path, content] of artifacts) {
    const pathMatch = /^([^/]+)\/(.+\.md)$/.exec(path)
    if (pathMatch?.[1] === undefined || pathMatch[2] === undefined) {
      errors.push(`${path}: expected {class}/yyyy-mm-dd-topic.md`)
      continue
    }
    if (!isAgentNoteClass(pathMatch[1])) {
      errors.push(`${path}: unknown Agent Note class`)
      continue
    }
    const fileMatch = NOTE_FILENAME_PATTERN.exec(pathMatch[2])
    if (fileMatch?.[1] === undefined) {
      errors.push(`${path}: invalid archived Agent Note filename`)
      continue
    }
    if (!isValidCalendarDate(fileMatch[1])) {
      errors.push(`${path}: filename contains an invalid calendar date`)
      continue
    }

    const decoded = validateTextBuffer(path, content)
    errors.push(...decoded.errors)
    if (decoded.source === undefined) continue
    const lines = decoded.source.split('\n')
    if (!/^# Agent Note: \S/.test(lines[0] ?? '')) errors.push(`${path}: line 1 must be an Agent Note title`)
    if (lines[1] !== '') errors.push(`${path}: line 2 must be blank`)
    if (lines[2] !== 'Status: implemented') errors.push(`${path}: line 3 must be Status: implemented`)
    const archived = /^Archived: (\d{4}-\d{2}-\d{2})$/.exec(lines[3] ?? '')?.[1]
    if (archived === undefined || !isValidCalendarDate(archived)) {
      errors.push(`${path}: line 4 must contain a valid archive date`)
    } else if (archived < fileMatch[1]) {
      errors.push(`${path}: archive date predates the note filename`)
    }
    if (lines[4] !== '') errors.push(`${path}: line 5 must be blank`)
  }
  return errors
}

export function extendArchiveManifest(
  existing: ArchiveManifest,
  artifacts: ReadonlyMap<string, Buffer>,
): { files: Record<string, string>; added: string[]; errors: string[] } {
  const files: Record<string, string> = { ...existing.files }
  const errors: string[] = []
  for (const [path, expected] of Object.entries(existing.files)) {
    const content = artifacts.get(path)
    if (content === undefined) errors.push(`${path}: sealed archived file is missing`)
    else if (archiveContentHash(content) !== expected) errors.push(`${path}: sealed archived content changed`)
  }
  const added: string[] = []
  for (const [path, content] of [...artifacts].sort(([left], [right]) => left.localeCompare(right))) {
    if (files[path] !== undefined) continue
    files[path] = archiveContentHash(content)
    added.push(path)
  }
  return { files, added, errors }
}
