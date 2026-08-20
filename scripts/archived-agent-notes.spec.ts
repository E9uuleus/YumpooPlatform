import { execFileSync } from 'node:child_process'
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import {
  archiveContentHash,
  parseArchiveManifest,
  renderArchiveManifest,
} from './archived-agent-notes.ts'
import { verifyArchivedAgentNotes } from './verify-archived-agent-notes.ts'

const tempRoots: string[] = []

afterEach(() => {
  for (const root of tempRoots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function write(path: string, content: string | Buffer): void {
  mkdirSync(resolve(path, '..'), { recursive: true })
  writeFileSync(path, content)
}

function runGit(root: string, args: string[]): string {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim()
}

function commit(root: string, message: string): void {
  runGit(root, ['add', '--', '.agents'])
  runGit(root, ['commit', '-m', message])
}

function fixtureRoot(): string {
  const root = mkdtempSync(resolve(tmpdir(), 'yumpoo-archive-'))
  tempRoots.push(root)
  for (const lifecycle of ['proposed', 'implemented', 'rejected']) {
    write(resolve(root, `.agents/notes/${lifecycle}/AGENTS.md`), `# ${lifecycle}\n`)
  }
  write(resolve(root, '.agents/notes/AGENTS.md'), '# Agent Notes\n')
  write(resolve(root, '.agents/notes/README.md'), '# Rules\n')
  write(resolve(root, '.agents/notes/archived/AGENTS.md'), '# Archived\n')
  write(resolve(root, '.agents/notes/archived/manifest.json'), '{\n  "version": 1,\n  "files": {}\n}\n')
  runGit(root, ['init'])
  runGit(root, ['config', 'core.autocrlf', 'false'])
  runGit(root, ['config', 'user.email', 'agent-notes@example.invalid'])
  runGit(root, ['config', 'user.name', 'Agent Notes Test'])
  commit(root, 'baseline')
  return root
}

function archivedNote(archiveDate = '2026-08-20'): string {
  return `# Agent Note: Example\n\nStatus: implemented\nArchived: ${archiveDate}\n\n## Problem\n\nHistory.\n`
}

function addArchive(root: string, fileName = '2026-08-20-example.md', content = archivedNote()): string {
  const path = resolve(root, `.agents/notes/archived/process/${fileName}`)
  write(path, content)
  return path
}

function sealAndCommit(root: string): string {
  const path = addArchive(root)
  const writeResult = verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD', write: true })
  expect(writeResult.errors).toEqual([])
  commit(root, 'seal archive')
  return path
}

describe('frozen Agent Note archive', () => {
  it('accepts the initial empty manifest', () => {
    const root = fixtureRoot()
    expect(verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD' })).toMatchObject({
      errors: [],
      artifactCount: 0,
    })
  })

  it('write mode appends a new SHA-256 seal and normal mode verifies it', () => {
    const root = fixtureRoot()
    const path = addArchive(root)
    const result = verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD', write: true })
    expect(result.errors).toEqual([])
    expect(result.added).toEqual(['process/2026-08-20-example.md'])
    expect(result.manifestChanged).toBe(true)
    const manifest = parseArchiveManifest(readFileSync(resolve(root, '.agents/notes/archived/manifest.json'), 'utf8'))
    expect(manifest.files['process/2026-08-20-example.md']).toBe(archiveContentHash(readFileSync(path)))
    expect(verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD' }).errors).toEqual([])
  })

  it('rejects modified, deleted, and moved sealed files', () => {
    const modifiedRoot = fixtureRoot()
    const modified = sealAndCommit(modifiedRoot)
    write(modified, archivedNote().replace('History.', 'Changed.'))
    expect(verifyArchivedAgentNotes({ repoRoot: modifiedRoot, baselineRef: 'HEAD' }).errors.join('\n')).toContain('sealed archived content changed')

    const deletedRoot = fixtureRoot()
    const deleted = sealAndCommit(deletedRoot)
    rmSync(deleted)
    expect(verifyArchivedAgentNotes({ repoRoot: deletedRoot, baselineRef: 'HEAD' }).errors.join('\n')).toContain('sealed archived file is missing')

    const movedRoot = fixtureRoot()
    const moved = sealAndCommit(movedRoot)
    const destination = resolve(movedRoot, '.agents/notes/archived/process/2026-08-20-moved.md')
    renameSync(moved, destination)
    const movedErrors = verifyArchivedAgentNotes({ repoRoot: movedRoot, baselineRef: 'HEAD' }).errors.join('\n')
    expect(movedErrors).toContain('sealed archived file is missing')
    expect(movedErrors).toContain('not sealed in manifest.json')
  })

  it('rejects deletion or replacement of baseline manifest entries', () => {
    const root = fixtureRoot()
    const path = sealAndCommit(root)
    const manifestPath = resolve(root, '.agents/notes/archived/manifest.json')
    const baseline = parseArchiveManifest(readFileSync(manifestPath, 'utf8'))

    write(manifestPath, renderArchiveManifest({}))
    expect(verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD' }).errors.join('\n')).toContain('sealed manifest entry is missing')

    const changed = archivedNote().replace('History.', 'Changed with manifest.')
    write(path, changed)
    write(manifestPath, renderArchiveManifest({
      'process/2026-08-20-example.md': archiveContentHash(Buffer.from(changed)),
    }))
    const errors = verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD', write: true }).errors.join('\n')
    expect(errors).toContain('sealed manifest hash changed')
    expect(baseline.files['process/2026-08-20-example.md']).toBeDefined()
  })

  it('rejects a new archived file that has not been sealed', () => {
    const root = fixtureRoot()
    addArchive(root)
    expect(verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD' }).errors.join('\n')).toContain('not sealed in manifest.json')
  })

  it('rejects invalid archive metadata and unknown classes', () => {
    const root = fixtureRoot()
    addArchive(root, '2026-08-20-example.md', archivedNote('2026-08-19'))
    write(resolve(root, '.agents/notes/archived/feature/2026-08-20-other.md'), archivedNote())
    const errors = verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD' }).errors.join('\n')
    expect(errors).toContain('archive date predates the note filename')
    expect(errors).toContain('unknown Agent Note class')
  })

  it('rejects an archived identity that remains active', () => {
    const root = fixtureRoot()
    addArchive(root)
    expect(verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD', write: true }).errors).toEqual([])
    write(
      resolve(root, '.agents/notes/implemented/process/2026-08-20-example.md'),
      '# Agent Note: Example\n\nStatus: implemented\n\n## Problem\n\nCurrent.\n',
    )
    expect(verifyArchivedAgentNotes({ repoRoot: root, baselineRef: 'HEAD' }).errors.join('\n')).toContain('also exists in the active tree')
  })
})
