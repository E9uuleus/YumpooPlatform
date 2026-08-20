import { resolve } from 'node:path'

export const AGENT_NOTE_ROOT = resolve(import.meta.dirname, '../.agents/notes')

export const ACTIVE_LIFECYCLES = ['proposed', 'implemented', 'rejected'] as const

export const AGENT_NOTE_CLASSES = [
  'architecture',
  'product',
  'data',
  'security',
  'process',
  'testing',
] as const

export const ARCHIVE_DIRECTORY = 'archived'

export const NOTE_ROOT_FILES = new Set(['AGENTS.md', 'README.md'])
export const ACTIVE_ROOT_FILES = new Set(['AGENTS.md'])
export const ARCHIVE_ROOT_FILES = new Set(['AGENTS.md', 'manifest.json'])

export const BILINGUAL_MARKDOWN_SUFFIX = ['.zh', '.md'].join('')
export const PAIRING_METADATA_SUFFIX = ['.i18n', '.yaml'].join('')

export const NOTE_FILENAME_PATTERN = /^(\d{4}-\d{2}-\d{2})-([a-z0-9]+(?:-[a-z0-9]+)*)\.md$/

export type ActiveLifecycle = (typeof ACTIVE_LIFECYCLES)[number]
export type AgentNoteClass = (typeof AGENT_NOTE_CLASSES)[number]

export function isActiveLifecycle(value: string): value is ActiveLifecycle {
  return (ACTIVE_LIFECYCLES as readonly string[]).includes(value)
}

export function isAgentNoteClass(value: string): value is AgentNoteClass {
  return (AGENT_NOTE_CLASSES as readonly string[]).includes(value)
}

export function isValidCalendarDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (match?.[1] === undefined || match[2] === undefined || match[3] === undefined) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day
}

export function validateTextBuffer(path: string, content: Buffer): { source?: string; errors: string[] } {
  const errors: string[] = []
  if (content.length >= 3 && content[0] === 0xef && content[1] === 0xbb && content[2] === 0xbf) {
    errors.push(`${path}: UTF-8 BOM is forbidden`)
  }
  if (content.includes(0x0d)) errors.push(`${path}: CRLF or CR line endings are forbidden; use LF`)
  if (content.length === 0 || content[content.length - 1] !== 0x0a) {
    errors.push(`${path}: file must end with LF`)
  }
  try {
    return { source: new TextDecoder('utf-8', { fatal: true }).decode(content), errors }
  } catch {
    errors.push(`${path}: file is not valid UTF-8`)
    return { errors }
  }
}
