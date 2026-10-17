import { appendFileSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs'
import path from 'node:path'

export type LogLevel = 'info' | 'warn' | 'error'
export type LogValue = string | number | boolean | null | undefined
export type LogFields = Record<string, LogValue>

export const LOG_FILE = 'desktop.log'
const MAX_FILE_BYTES = 5 * 1024 * 1024
const MAX_FILES = 3
const MAX_STRING_LENGTH = 300
// Field names that could carry session material or message content are dropped rather than masked.
const SENSITIVE_FIELD = /cookie|token|credential|secret|password|authorization|body|content|title/iu
const SENSITIVE_NAMES = new Set(['code', 'state', 'session', 'text', 'message'])

/** URLs keep scheme, host and path only: query strings and fragments may carry one-time codes. */
export function redactUrl(value: string): string {
  try {
    const url = new URL(value)
    return url.protocol === 'file:' ? 'file:' : `${url.protocol}//${url.host}${url.pathname}`
  } catch {
    return '<invalid-url>'
  }
}

export function redactFields(fields: LogFields): Record<string, string | number | boolean | null> {
  const safe: Record<string, string | number | boolean | null> = {}
  for (const [key, value] of Object.entries(fields)) {
    if (value === undefined || SENSITIVE_FIELD.test(key) || SENSITIVE_NAMES.has(key.toLowerCase())) continue
    if (typeof value !== 'string') {
      safe[key] = value
    } else {
      const text = /url$/iu.test(key) ? redactUrl(value) : value.replace(/\b[A-Za-z][A-Za-z0-9+.-]*:\/\/\S+/gu, match => redactUrl(match))
      safe[key] = text.length > MAX_STRING_LENGTH ? `${text.slice(0, MAX_STRING_LENGTH)}…` : text
    }
  }
  return safe
}

/**
 * Append-only JSON lines in userData/logs, rotated at 5 MB across three files. Logging never throws:
 * a full disk or locked file must not take the shell down with it.
 */
export class DesktopLogger {
  private readonly file: string

  constructor(private readonly directory: string, private readonly now: () => Date = () => new Date()) {
    this.file = path.join(directory, LOG_FILE)
  }

  get logDirectory(): string {
    return this.directory
  }

  info(event: string, fields: LogFields = {}): void { this.write('info', event, fields) }
  warn(event: string, fields: LogFields = {}): void { this.write('warn', event, fields) }
  error(event: string, fields: LogFields = {}): void { this.write('error', event, fields) }

  private write(level: LogLevel, event: string, fields: LogFields): void {
    const line = `${JSON.stringify({ ts: this.now().toISOString(), level, event, ...redactFields(fields) })}\n`
    try {
      mkdirSync(this.directory, { recursive: true })
      this.rotateFor(Buffer.byteLength(line))
      appendFileSync(this.file, line, 'utf8')
    } catch {
      // Diagnostics stay best-effort.
    }
  }

  private rotateFor(bytes: number): void {
    let size = 0
    try {
      size = statSync(this.file).size
    } catch {
      return
    }
    if (size + bytes <= MAX_FILE_BYTES) return
    rmSync(this.rotated(MAX_FILES - 1), { force: true })
    for (let index = MAX_FILES - 2; index >= 1; index -= 1) {
      try { renameSync(this.rotated(index), this.rotated(index + 1)) } catch { /* missing generation */ }
    }
    renameSync(this.file, this.rotated(1))
  }

  private rotated(index: number): string {
    return path.join(this.directory, `desktop.${index}.log`)
  }
}
