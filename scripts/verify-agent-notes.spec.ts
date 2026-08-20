import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import {
  ACTIVE_LIFECYCLES,
  BILINGUAL_MARKDOWN_SUFFIX,
  PAIRING_METADATA_SUFFIX,
} from './agent-note-policy.ts'
import { verifyAgentNotes } from './verify-agent-notes.ts'
import {
  documentAnchors,
  findLinkViolations,
  isArchivedArtifactSource,
} from './verify-md-links.ts'

const tempRoots: string[] = []

afterEach(() => {
  for (const root of tempRoots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function write(path: string, content: string | Buffer): void {
  mkdirSync(resolve(path, '..'), { recursive: true })
  writeFileSync(path, content)
}

function fixtureRoot(): string {
  const root = mkdtempSync(resolve(tmpdir(), 'yumpoo-agent-notes-'))
  tempRoots.push(root)
  const notesRoot = resolve(root, '.agents/notes')
  write(resolve(notesRoot, 'AGENTS.md'), '# Rules\n')
  write(resolve(notesRoot, 'README.md'), '# Agent Notes\n')
  for (const lifecycle of ACTIVE_LIFECYCLES) {
    write(resolve(notesRoot, lifecycle, 'AGENTS.md'), `# ${lifecycle}\n`)
  }
  write(resolve(notesRoot, 'archived/AGENTS.md'), '# Archived\n')
  write(resolve(notesRoot, 'archived/manifest.json'), '{\n  "version": 1,\n  "files": {}\n}\n')
  return root
}

function implementedNote(extra = ''): string {
  return `# Agent Note: Example\n\nStatus: implemented\n\n## Problem\n\nProblem.\n\n## Decision\n\nDecision.\n\n${extra}## Alternatives considered\n\nAlternative.\n\n## Consequences\n\nConsequence.\n`
}

function proposedNote(): string {
  return '# Agent Note: Example\n\nStatus: proposed\n\n## Problem\n\nProblem.\n\n## Proposal\n\nProposal.\n\n## Alternatives considered\n\nAlternative.\n\n## Acceptance criteria\n\nDone.\n\n## Risks\n\nRisk.\n'
}

describe('active Agent Note verification', () => {
  it('accepts a valid active note and empty archive', () => {
    const root = fixtureRoot()
    write(resolve(root, '.agents/notes/implemented/process/2026-08-20-example.md'), implementedNote())
    expect(verifyAgentNotes(root)).toEqual({ errors: [], noteCount: 1 })
  })

  it('rejects unknown lifecycle and class directories', () => {
    const root = fixtureRoot()
    mkdirSync(resolve(root, '.agents/notes/draft'), { recursive: true })
    mkdirSync(resolve(root, '.agents/notes/proposed/feature'), { recursive: true })
    expect(verifyAgentNotes(root).errors.join('\n')).toMatch(/unknown lifecycle[\s\S]*unknown Agent Note class/)
  })

  it('rejects a note at a lifecycle root', () => {
    const root = fixtureRoot()
    write(resolve(root, '.agents/notes/proposed/2026-08-20-example.md'), proposedNote())
    expect(verifyAgentNotes(root).errors.join('\n')).toContain('Agent Notes must live under a class directory')
  })

  it('rejects invalid calendar dates and non-kebab topics', () => {
    const root = fixtureRoot()
    write(resolve(root, '.agents/notes/implemented/process/2026-02-30-example.md'), implementedNote())
    write(resolve(root, '.agents/notes/implemented/process/2026-08-20-Not_Good.md'), implementedNote())
    const errors = verifyAgentNotes(root).errors.join('\n')
    expect(errors).toContain('invalid calendar date')
    expect(errors).toContain('ASCII lowercase kebab-case')
  })

  it('rejects status mismatch and missing required sections', () => {
    const root = fixtureRoot()
    const invalid = '# Agent Note: Example\n\nStatus: proposed\n\n## Problem\n\nProblem.\n'
    write(resolve(root, '.agents/notes/implemented/process/2026-08-20-example.md'), invalid)
    const errors = verifyAgentNotes(root).errors.join('\n')
    expect(errors).toContain('does not match lifecycle implemented')
    expect(errors).toContain('must appear exactly once')
  })

  it('rejects proposal-era implemented headings', () => {
    const root = fixtureRoot()
    write(
      resolve(root, '.agents/notes/implemented/process/2026-08-20-example.md'),
      implementedNote('## Plan\n\nLater.\n\n'),
    )
    expect(verifyAgentNotes(root).errors.join('\n')).toContain('proposal-era section')
  })

  it('rejects the same identity in multiple active lifecycles', () => {
    const root = fixtureRoot()
    write(resolve(root, '.agents/notes/implemented/process/2026-08-20-example.md'), implementedNote())
    write(resolve(root, '.agents/notes/proposed/product/2026-08-20-example.md'), proposedNote())
    expect(verifyAgentNotes(root).errors.join('\n')).toContain('duplicate active identity')
  })

  it.each([
    ['BOM', Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(implementedNote())])],
    ['CRLF', Buffer.from(implementedNote().replaceAll('\n', '\r\n'))],
    ['missing final LF', Buffer.from(implementedNote().slice(0, -1))],
  ])('rejects invalid text encoding: %s', (_label, content) => {
    const root = fixtureRoot()
    write(resolve(root, '.agents/notes/implemented/process/2026-08-20-example.md'), content)
    expect(verifyAgentNotes(root).errors.length).toBeGreaterThan(0)
  })

  it('rejects bilingual companions, pairing metadata, and a centralized index', () => {
    const root = fixtureRoot()
    write(resolve(root, '.agents/notes/INDEX.md'), '# Index\n')
    write(resolve(root, `.agents/notes/implemented/process/2026-08-20-example${BILINGUAL_MARKDOWN_SUFFIX}`), '# Copy\n')
    write(resolve(root, `.agents/notes/implemented/process/2026-08-20-example${PAIRING_METADATA_SUFFIX}`), 'copy\n')
    const errors = verifyAgentNotes(root).errors.join('\n')
    expect(errors).toContain('centralized INDEX.md is forbidden')
    expect(errors).toContain('bilingual companions and pairing metadata are forbidden')
  })
})

describe('Markdown link verification', () => {
  it('reports missing targets and anchors while accepting duplicate heading slugs', () => {
    const root = fixtureRoot()
    const source = resolve(root, 'source.md')
    const target = resolve(root, 'target.md')
    write(target, '# Repeat\n\n# Repeat\n')
    write(source, '[first](target.md#repeat) [second](target.md#repeat-1) [bad](target.md#repeat-2) [missing](missing.md)\n')
    const violations = findLinkViolations(source, root)
    expect(violations.map(item => item.reason)).toEqual(['anchor', 'target'])
    expect(documentAnchors('# Repeat\n\n# Repeat\n')).toEqual(new Set(['repeat', 'repeat-1']))
  })

  it('skips sealed note sources but not archive governance', () => {
    expect(isArchivedArtifactSource('.agents/notes/archived/process/2026-08-20-example.md')).toBe(true)
    expect(isArchivedArtifactSource('.agents/notes/archived/AGENTS.md')).toBe(false)
  })
})
