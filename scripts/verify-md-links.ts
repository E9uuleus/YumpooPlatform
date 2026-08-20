import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, realpathSync } from 'node:fs'
import { dirname, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ARCHIVE_DIRECTORY, isAgentNoteClass } from './agent-note-policy.ts'
import { markdownHeadings, parseMarkdown, visitMarkdown, type MarkdownNode } from './markdown.ts'

export interface LinkViolation {
  file: string
  line: number
  url: string
  reason: 'target' | 'anchor'
}

function toPosix(path: string): string {
  return path.split(sep).join('/')
}

function isExternal(url: string): boolean {
  if (url.startsWith('//') || url.startsWith('/')) return true
  return /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(url)
}

function pathPart(url: string): string {
  const raw = url.replace(/[#?].*$/, '')
  try {
    return decodeURIComponent(raw)
  } catch {
    return raw
  }
}

function fragmentPart(url: string): string | null {
  const index = url.indexOf('#')
  if (index < 0) return null
  const raw = url.slice(index + 1).replace(/\?.*$/, '')
  try {
    return decodeURIComponent(raw)
  } catch {
    return raw
  }
}

export function githubSlug(heading: string): string {
  return heading.toLowerCase().replace(/[^\p{L}\p{N}_ -]/gu, '').replaceAll(' ', '-')
}

export function documentAnchors(source: string): Set<string> {
  const anchors = new Set<string>()
  const occurrences = new Map<string, number>()
  for (const heading of markdownHeadings(source)) {
    const base = githubSlug(heading.text)
    let candidate = base
    let suffix = occurrences.get(base) ?? 0
    while (anchors.has(candidate)) {
      suffix += 1
      candidate = `${base}-${suffix}`
    }
    occurrences.set(base, suffix)
    anchors.add(candidate)
  }
  visitMarkdown(parseMarkdown(source), (node) => {
    if (node.type !== 'html') return
    const html = (node.value ?? '').replace(/<!--[\s\S]*?-->/g, '')
    for (const match of html.matchAll(/<a id="([^"]+)"/g)) anchors.add(match[1] ?? '')
  })
  return anchors
}

export function isArchivedArtifactSource(repoPath: string): boolean {
  const segments = toPosix(repoPath).split('/')
  return segments.length === 5
    && segments[0] === '.agents'
    && segments[1] === 'notes'
    && segments[2] === ARCHIVE_DIRECTORY
    && isAgentNoteClass(segments[3] ?? '')
    && (segments[4] ?? '').endsWith('.md')
}

function anchorLookup(): (path: string) => Set<string> {
  const cache = new Map<string, Set<string>>()
  return (path) => {
    const existing = cache.get(path)
    if (existing !== undefined) return existing
    const anchors = documentAnchors(readFileSync(path, 'utf8'))
    cache.set(path, anchors)
    return anchors
  }
}

export function findLinkViolations(
  absPath: string,
  repoRoot: string,
  anchorsOf: (path: string) => Set<string> = anchorLookup(),
): LinkViolation[] {
  const file = toPosix(relative(repoRoot, absPath))
  const directory = dirname(absPath)
  const source = readFileSync(absPath, 'utf8')
  const violations: LinkViolation[] = []

  const check = (url: string, node: MarkdownNode): void => {
    if (isExternal(url)) return
    const target = pathPart(url)
    const resolved = target === '' ? absPath : resolve(directory, target)
    if (!existsSync(resolved)) {
      violations.push({ file, line: node.position?.start.line ?? 0, url, reason: 'target' })
      return
    }
    const fragment = fragmentPart(url)
    if (fragment === null || !resolved.toLowerCase().endsWith('.md')) return
    if (!anchorsOf(resolved).has(fragment)) {
      violations.push({ file, line: node.position?.start.line ?? 0, url, reason: 'anchor' })
    }
  }

  visitMarkdown(parseMarkdown(source), (node) => {
    if ((node.type === 'link' || node.type === 'image' || node.type === 'definition') && node.url !== undefined) {
      check(node.url, node)
    }
  })
  return violations
}

function discoverMarkdownFiles(repoRoot: string): string[] {
  const result = spawnSync('git', ['ls-files', '-c', '-o', '--exclude-standard', '-z'], {
    cwd: repoRoot,
    encoding: 'utf8',
  })
  if (result.error !== undefined) throw result.error
  if (result.status !== 0) throw new Error(result.stderr.trim() || `git ls-files exited with ${result.status}`)
  const excludedSegments = new Set(['.git', '.idea', '.pnpm-store', 'node_modules', 'out', 'dist', 'coverage', 'target'])
  const seen = new Set<string>()
  const files: string[] = []
  for (const repoPath of result.stdout.split('\0').filter(Boolean).sort()) {
    const normalized = toPosix(repoPath)
    if (!normalized.endsWith('.md')) continue
    if (normalized.split('/').some(segment => excludedSegments.has(segment))) continue
    if (isArchivedArtifactSource(normalized)) continue
    const abs = resolve(repoRoot, repoPath)
    if (!existsSync(abs)) continue
    const real = realpathSync(abs)
    if (seen.has(real)) continue
    seen.add(real)
    files.push(abs)
  }
  return files
}

export function verifyMarkdownLinks(repoRoot: string = resolve(import.meta.dirname, '..')): LinkViolation[] {
  const anchorsOf = anchorLookup()
  return discoverMarkdownFiles(repoRoot).flatMap(path => findLinkViolations(path, repoRoot, anchorsOf))
}

function runCli(): void {
  const violations = verifyMarkdownLinks()
  if (violations.length === 0) {
    console.log('verify-md-links: all relative Markdown links and fragments resolve.')
    return
  }
  console.error('verify-md-links: broken relative links found:')
  for (const violation of violations) {
    const reason = violation.reason === 'target' ? 'target does not exist' : 'anchor does not exist'
    console.error(`  ${violation.file}:${violation.line}  ${violation.url}  (${reason})`)
  }
  process.exitCode = 1
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) runCli()
