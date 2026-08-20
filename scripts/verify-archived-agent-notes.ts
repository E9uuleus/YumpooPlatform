import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { basename, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  ACTIVE_LIFECYCLES,
  ARCHIVE_DIRECTORY,
  ARCHIVE_ROOT_FILES,
  isAgentNoteClass,
  validateTextBuffer,
} from './agent-note-policy.ts'
import {
  extendArchiveManifest,
  parseArchiveManifest,
  renderArchiveManifest,
  validateArchiveArtifacts,
  validateArchiveManifestExtension,
  type ArchiveManifest,
} from './archived-agent-notes.ts'

export interface ArchiveVerificationOptions {
  repoRoot?: string
  baselineRef?: string
  write?: boolean
}

export interface ArchiveVerification {
  errors: string[]
  added: string[]
  artifactCount: number
  manifestChanged: boolean
}

function runGit(repoRoot: string, args: string[]): string {
  const result = spawnSync('git', args, { cwd: repoRoot, encoding: 'utf8' })
  if (result.error !== undefined) throw result.error
  if (result.status !== 0) throw new Error(result.stderr.trim() || `git exited with ${result.status}`)
  return result.stdout
}

function readBaselineManifest(repoRoot: string, ref: string): ArchiveManifest {
  runGit(repoRoot, ['cat-file', '-e', `${ref}^{commit}`])
  const repoPath = '.agents/notes/archived/manifest.json'
  const entry = runGit(repoRoot, ['ls-tree', '--name-only', ref, '--', repoPath]).trim()
  if (entry === '') return { version: 1, files: {} }
  return parseArchiveManifest(runGit(repoRoot, ['show', `${ref}:${repoPath}`]))
}

function activeIdentities(repoRoot: string): Set<string> {
  const identities = new Set<string>()
  const notesRoot = resolve(repoRoot, '.agents/notes')
  for (const lifecycle of ACTIVE_LIFECYCLES) {
    const lifecycleRoot = resolve(notesRoot, lifecycle)
    if (!existsSync(lifecycleRoot)) continue
    for (const classEntry of readdirSync(lifecycleRoot, { withFileTypes: true })) {
      if (!classEntry.isDirectory() || !isAgentNoteClass(classEntry.name)) continue
      for (const entry of readdirSync(resolve(lifecycleRoot, classEntry.name), { withFileTypes: true })) {
        if (entry.isFile() && entry.name.endsWith('.md')) identities.add(entry.name)
      }
    }
  }
  return identities
}

export function verifyArchivedAgentNotes(options: ArchiveVerificationOptions = {}): ArchiveVerification {
  const repoRoot = options.repoRoot ?? resolve(import.meta.dirname, '..')
  const write = options.write ?? false
  const archiveRoot = resolve(repoRoot, '.agents/notes', ARCHIVE_DIRECTORY)
  const manifestPath = resolve(archiveRoot, 'manifest.json')
  const errors: string[] = []
  const artifacts = new Map<string, Buffer>()

  if (!existsSync(archiveRoot)) {
    return { errors: [`${ARCHIVE_DIRECTORY}/: archive directory is missing`], added: [], artifactCount: 0, manifestChanged: false }
  }

  for (const entry of readdirSync(archiveRoot, { withFileTypes: true })) {
    if (entry.isFile()) {
      if (!ARCHIVE_ROOT_FILES.has(entry.name)) errors.push(`${ARCHIVE_DIRECTORY}/${entry.name}: unexpected archive root file`)
      continue
    }
    if (!entry.isDirectory()) {
      errors.push(`${ARCHIVE_DIRECTORY}/${entry.name}: only regular files and class directories are allowed`)
      continue
    }
    if (!isAgentNoteClass(entry.name)) {
      errors.push(`${ARCHIVE_DIRECTORY}/${entry.name}/: unknown Agent Note class`)
      continue
    }
    const classRoot = resolve(archiveRoot, entry.name)
    for (const child of readdirSync(classRoot, { withFileTypes: true })) {
      const rel = `${entry.name}/${child.name}`
      if (!child.isFile()) {
        errors.push(`${rel}: archived class directories contain regular files only`)
        continue
      }
      if (!child.name.endsWith('.md')) {
        errors.push(`${rel}: archived class directories contain Markdown notes only`)
        continue
      }
      artifacts.set(rel, readFileSync(resolve(classRoot, child.name)))
    }
  }

  if (!existsSync(resolve(archiveRoot, 'AGENTS.md'))) errors.push(`${ARCHIVE_DIRECTORY}/AGENTS.md: required file is missing`)
  else errors.push(...validateTextBuffer(`${ARCHIVE_DIRECTORY}/AGENTS.md`, readFileSync(resolve(archiveRoot, 'AGENTS.md'))).errors)
  errors.push(...validateArchiveArtifacts(artifacts))

  const active = activeIdentities(repoRoot)
  for (const path of artifacts.keys()) {
    if (active.has(basename(path))) errors.push(`${path}: archived identity also exists in the active tree`)
  }

  let manifest: ArchiveManifest = { version: 1, files: {} }
  if (existsSync(manifestPath)) {
    const manifestBuffer = readFileSync(manifestPath)
    errors.push(...validateTextBuffer(`${ARCHIVE_DIRECTORY}/manifest.json`, manifestBuffer).errors)
    try {
      manifest = parseArchiveManifest(manifestBuffer.toString('utf8'))
    } catch (error: unknown) {
      errors.push(`${ARCHIVE_DIRECTORY}/manifest.json: ${error instanceof Error ? error.message : String(error)}`)
    }
  } else if (!write) {
    errors.push(`${ARCHIVE_DIRECTORY}/manifest.json: required file is missing`)
  }

  const baselineRef = options.baselineRef ?? process.env.AGENT_NOTE_ARCHIVE_BASE_REF ?? 'HEAD'
  try {
    const baseline = readBaselineManifest(repoRoot, baselineRef)
    errors.push(...validateArchiveManifestExtension(baseline, manifest))
  } catch (error: unknown) {
    errors.push(`cannot read archive baseline ${JSON.stringify(baselineRef)}: ${error instanceof Error ? error.message : String(error)}`)
  }

  const extended = extendArchiveManifest(manifest, artifacts)
  errors.push(...extended.errors)
  if (!write) {
    for (const path of extended.added) errors.push(`${path}: archived file is not sealed in manifest.json`)
  }

  if (errors.length > 0) {
    return { errors: errors.sort(), added: extended.added, artifactCount: artifacts.size, manifestChanged: false }
  }

  let manifestChanged = false
  if (write) {
    const rendered = renderArchiveManifest(extended.files)
    const current = existsSync(manifestPath) ? readFileSync(manifestPath, 'utf8') : undefined
    if (current !== rendered) {
      writeFileSync(manifestPath, rendered)
      manifestChanged = true
    }
  }
  return {
    errors: [],
    added: extended.added,
    artifactCount: artifacts.size,
    manifestChanged,
  }
}

function runCli(): void {
  const args = process.argv.slice(2)
  const write = args.length === 1 && args[0] === '--write'
  if (args.length > 0 && !write) {
    console.error('verify-archived-agent-notes: usage: tsx scripts/verify-archived-agent-notes.ts [--write]')
    process.exitCode = 1
    return
  }
  const result = verifyArchivedAgentNotes({ write })
  if (result.errors.length > 0) {
    console.error('verify-archived-agent-notes: archive rules violated:')
    for (const error of result.errors) console.error(`  ${error}`)
    process.exitCode = 1
    return
  }
  if (write) {
    console.log(`verify-archived-agent-notes: sealed ${result.added.length} new file(s); existing seals unchanged.`)
  } else {
    console.log(`verify-archived-agent-notes: ${result.artifactCount} frozen file(s) checked.`)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) runCli()
