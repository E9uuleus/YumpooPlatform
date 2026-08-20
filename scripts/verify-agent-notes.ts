import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { basename, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  ACTIVE_LIFECYCLES,
  ACTIVE_ROOT_FILES,
  AGENT_NOTE_CLASSES,
  ARCHIVE_DIRECTORY,
  ARCHIVE_ROOT_FILES,
  BILINGUAL_MARKDOWN_SUFFIX,
  NOTE_FILENAME_PATTERN,
  NOTE_ROOT_FILES,
  PAIRING_METADATA_SUFFIX,
  isActiveLifecycle,
  isAgentNoteClass,
  isValidCalendarDate,
  validateTextBuffer,
  type ActiveLifecycle,
} from './agent-note-policy.ts'
import { markdownHeadings, markdownProseLines } from './markdown.ts'

interface ActiveNote {
  lifecycle: ActiveLifecycle
  rel: string
  fileName: string
  abs: string
}

export interface AgentNoteVerification {
  errors: string[]
  noteCount: number
}

const REQUIRED_HEADINGS: Record<ActiveLifecycle, readonly string[]> = {
  proposed: ['## Problem', '## Proposal', '## Alternatives considered', '## Acceptance criteria', '## Risks'],
  implemented: ['## Problem', '## Decision', '## Alternatives considered', '## Consequences'],
  rejected: ['## Problem', '## Proposal', '## Alternatives considered'],
}

const STATUS_PATTERNS: Record<ActiveLifecycle, RegExp> = {
  proposed: /^Status: proposed$/,
  implemented: /^Status: implemented$/,
  rejected: /^Status: rejected — \S.*$/,
}

const BANNED_IMPLEMENTED_HEADING = /^## (?:Proposal|Plan|Migration plan|Acceptance criteria)(?:\s|$)/i

function toPosix(path: string): string {
  return path.split(sep).join('/')
}

function walkGlobalRules(root: string, current: string, errors: string[]): void {
  for (const entry of readdirSync(current, { withFileTypes: true })) {
    const abs = resolve(current, entry.name)
    const rel = toPosix(relative(root, abs))
    if (entry.name === 'INDEX.md') errors.push(`${rel}: centralized INDEX.md is forbidden`)
    if (entry.name.endsWith(BILINGUAL_MARKDOWN_SUFFIX) || entry.name.endsWith(PAIRING_METADATA_SUFFIX)) {
      errors.push(`${rel}: bilingual companions and pairing metadata are forbidden`)
    }
    if (entry.isDirectory()) walkGlobalRules(root, abs, errors)
  }
}

function validateGovernanceText(abs: string, rel: string, errors: string[]): void {
  if (!existsSync(abs)) return
  errors.push(...validateTextBuffer(rel, readFileSync(abs)).errors)
}

function validateActiveNote(note: ActiveNote, errors: string[]): void {
  const content = readFileSync(note.abs)
  const decoded = validateTextBuffer(note.rel, content)
  errors.push(...decoded.errors)
  if (decoded.source === undefined) return

  const lines = decoded.source.split('\n')
  const fail = (message: string): void => {
    errors.push(`${note.rel}: ${message}`)
  }

  if (!/^# Agent Note: \S/.test(lines[0] ?? '')) fail('line 1 must be `# Agent Note: <title>`')
  if (lines[1] !== '') fail('line 2 must be blank')
  if (!STATUS_PATTERNS[note.lifecycle].test(lines[2] ?? '')) {
    fail(`line 3 does not match lifecycle ${note.lifecycle}`)
  }
  if (lines[3] !== '') fail('line 4 must be blank')

  const statusLines = markdownProseLines(decoded.source)
    .filter(line => line.raw.trimStart().startsWith('Status:'))
  if (statusLines.length !== 1 || statusLines[0]?.line !== 3) {
    fail('the line-3 Status entry must be the only Status entry outside code and comments')
  }

  const h2s = markdownHeadings(decoded.source)
    .filter(heading => heading.depth === 2)
    .map(heading => heading.raw)
  if (h2s[0] !== '## Problem') fail('the first H2 section must be `## Problem`')
  for (const required of REQUIRED_HEADINGS[note.lifecycle]) {
    const count = h2s.filter(heading => heading === required).length
    if (count !== 1) fail(`required section ${JSON.stringify(required)} must appear exactly once (found ${count})`)
  }
  if (note.lifecycle === 'implemented') {
    for (const heading of h2s.filter(value => BANNED_IMPLEMENTED_HEADING.test(value))) {
      fail(`proposal-era section ${JSON.stringify(heading)} is forbidden in implemented notes`)
    }
  }
}

export function verifyAgentNotes(repoRoot: string = resolve(import.meta.dirname, '..')): AgentNoteVerification {
  const notesRoot = resolve(repoRoot, '.agents/notes')
  const errors: string[] = []
  const notes: ActiveNote[] = []

  if (!existsSync(notesRoot)) return { errors: ['.agents/notes: directory is missing'], noteCount: 0 }
  walkGlobalRules(notesRoot, notesRoot, errors)

  for (const entry of readdirSync(notesRoot, { withFileTypes: true })) {
    if (entry.isFile()) {
      if (!NOTE_ROOT_FILES.has(entry.name)) errors.push(`${entry.name}: unexpected file at Agent Note root`)
      continue
    }
    if (!entry.isDirectory()) {
      errors.push(`${entry.name}: only regular files and allowed directories may exist at Agent Note root`)
      continue
    }
    if (!isActiveLifecycle(entry.name) && entry.name !== ARCHIVE_DIRECTORY) {
      errors.push(`${entry.name}/: unknown lifecycle directory`)
    }
  }

  for (const required of NOTE_ROOT_FILES) {
    if (!existsSync(resolve(notesRoot, required))) errors.push(`${required}: required Agent Note root file is missing`)
  }
  validateGovernanceText(resolve(notesRoot, 'AGENTS.md'), 'AGENTS.md', errors)
  validateGovernanceText(resolve(notesRoot, 'README.md'), 'README.md', errors)

  for (const lifecycle of ACTIVE_LIFECYCLES) {
    const lifecycleRoot = resolve(notesRoot, lifecycle)
    if (!existsSync(lifecycleRoot)) {
      errors.push(`${lifecycle}/: required lifecycle directory is missing`)
      continue
    }
    if (!existsSync(resolve(lifecycleRoot, 'AGENTS.md'))) errors.push(`${lifecycle}/AGENTS.md: required file is missing`)
    validateGovernanceText(resolve(lifecycleRoot, 'AGENTS.md'), `${lifecycle}/AGENTS.md`, errors)

    for (const entry of readdirSync(lifecycleRoot, { withFileTypes: true })) {
      if (entry.isFile()) {
        if (!ACTIVE_ROOT_FILES.has(entry.name)) errors.push(`${lifecycle}/${entry.name}: Agent Notes must live under a class directory`)
        continue
      }
      if (!entry.isDirectory()) {
        errors.push(`${lifecycle}/${entry.name}: only regular files and class directories are allowed`)
        continue
      }
      if (!isAgentNoteClass(entry.name)) {
        errors.push(`${lifecycle}/${entry.name}/: unknown Agent Note class`)
        continue
      }
      const classRoot = resolve(lifecycleRoot, entry.name)
      for (const child of readdirSync(classRoot, { withFileTypes: true })) {
        const rel = `${lifecycle}/${entry.name}/${child.name}`
        if (!child.isFile()) {
          errors.push(`${rel}: class directories contain regular files only`)
          continue
        }
        if (!child.name.endsWith('.md')) {
          errors.push(`${rel}: Agent Note must be a Markdown file`)
          continue
        }
        const match = NOTE_FILENAME_PATTERN.exec(child.name)
        if (match?.[1] === undefined) {
          errors.push(`${rel}: filename must be yyyy-mm-dd followed by an ASCII lowercase kebab-case topic`)
          continue
        }
        if (!isValidCalendarDate(match[1])) {
          errors.push(`${rel}: filename contains an invalid calendar date`)
          continue
        }
        notes.push({ lifecycle, rel, fileName: child.name, abs: resolve(classRoot, child.name) })
      }
    }
  }

  const byIdentity = new Map<string, string[]>()
  for (const note of notes) {
    const matches = byIdentity.get(note.fileName) ?? []
    matches.push(note.rel)
    byIdentity.set(note.fileName, matches)
  }
  for (const [identity, matches] of byIdentity) {
    if (matches.length > 1) errors.push(`${identity}: duplicate active identity at ${matches.join(', ')}`)
  }

  const archiveRoot = resolve(notesRoot, ARCHIVE_DIRECTORY)
  if (!existsSync(archiveRoot)) {
    errors.push(`${ARCHIVE_DIRECTORY}/: required archive directory is missing`)
  } else {
    for (const entry of readdirSync(archiveRoot, { withFileTypes: true })) {
      if (entry.isFile()) {
        if (!ARCHIVE_ROOT_FILES.has(entry.name)) errors.push(`${ARCHIVE_DIRECTORY}/${entry.name}: unexpected archive root file`)
        continue
      }
      if (!entry.isDirectory() || !isAgentNoteClass(entry.name)) continue
      for (const child of readdirSync(resolve(archiveRoot, entry.name), { withFileTypes: true })) {
        if (child.isFile() && child.name.endsWith('.md') && byIdentity.has(child.name)) {
          errors.push(`${ARCHIVE_DIRECTORY}/${entry.name}/${child.name}: archived identity also exists in the active tree`)
        }
      }
    }
    if (!existsSync(resolve(archiveRoot, 'AGENTS.md'))) errors.push(`${ARCHIVE_DIRECTORY}/AGENTS.md: required file is missing`)
    validateGovernanceText(resolve(archiveRoot, 'AGENTS.md'), `${ARCHIVE_DIRECTORY}/AGENTS.md`, errors)
  }

  for (const note of notes) validateActiveNote(note, errors)
  return { errors: errors.sort(), noteCount: notes.length }
}

function runCli(): void {
  const result = verifyAgentNotes()
  if (result.errors.length === 0) {
    console.log(`verify-agent-notes: ${result.noteCount} active Agent Note(s) checked.`)
    return
  }
  console.error('verify-agent-notes: violations found:')
  for (const error of result.errors) console.error(`  ${error}`)
  process.exitCode = 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) runCli()
