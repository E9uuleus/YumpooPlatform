import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { extractOpenApiBaseline } from '../openapi/extract-openapi-baseline.mjs'
import {
  assertDirectoryTargetWithin,
  assertExactPayload,
  collectRegularFiles,
  fileRecords,
  isSafeRelativePath,
  readJson,
  sha256Buffer,
  sha256GitTextFile,
  validateGitRef,
} from './delivery-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

test('Git baseline ref rejects injection, traversal and all-zero commits', () => {
  assert.equal(validateGitRef('origin/dev'), 'origin/dev')
  assert.equal(validateGitRef('a'.repeat(40)), 'a'.repeat(40))
  for (const reference of ['', '-dev', '../dev', 'refs//heads/dev', 'refs/heads/dev@{1}', '0'.repeat(40)]) {
    assert.throws(() => validateGitRef(reference), /DELIVERY/u)
  }
})

test('OpenAPI baseline extraction is commit-bound and fails on empty or missing contracts', (context) => {
  const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-git-'))
  context.after(() => fs.rmSync(fixture, { recursive: true, force: true }))
  git(fixture, ['init', '--quiet'])
  git(fixture, ['config', 'user.email', 'm018@example.invalid'])
  git(fixture, ['config', 'user.name', 'DELIVERY Test'])
  const contractPath = path.join(fixture, 'contracts', 'openapi', 'yumpoo-v1.yaml')
  fs.mkdirSync(path.dirname(contractPath), { recursive: true })
  const expected = 'openapi: 3.0.3\ninfo:\n  title: Fixture\n  version: 1.0.0\npaths: {}\n'
  fs.writeFileSync(contractPath, expected, 'utf8')
  git(fixture, ['add', 'contracts/openapi/yumpoo-v1.yaml'])
  git(fixture, ['commit', '--quiet', '-m', 'fixture'])
  const goodCommit = git(fixture, ['rev-parse', 'HEAD']).trim()
  const outputPath = path.join(fixture, 'result', 'baseline.yaml')
  const metadataPath = path.join(fixture, 'result', 'metadata.json')
  const metadata = extractOpenApiBaseline({ repositoryRoot: fixture, reference: goodCommit, outputPath, metadataPath })
  assert.equal(fs.readFileSync(outputPath, 'utf8'), expected)
  assert.equal(metadata.baseCommit, goodCommit)
  assert.equal(readJson(metadataPath).sha256, metadata.sha256)
  fs.writeFileSync(contractPath, expected.replaceAll('\n', '\r\n'), 'utf8')
  assert.equal(
    sha256GitTextFile(fixture, goodCommit, 'contracts/openapi/yumpoo-v1.yaml', contractPath),
    sha256Buffer(expected),
  )
  fs.writeFileSync(contractPath, `${expected}# semantic drift\n`, 'utf8')
  assert.throws(
    () => sha256GitTextFile(fixture, goodCommit, 'contracts/openapi/yumpoo-v1.yaml', contractPath),
    /DELIVERY/u,
  )
  assert.throws(
    () => extractOpenApiBaseline({ repositoryRoot: fixture, reference: 'refs/heads/missing', outputPath, metadataPath }),
    /DELIVERY/u,
  )

  fs.writeFileSync(contractPath, '', 'utf8')
  git(fixture, ['add', 'contracts/openapi/yumpoo-v1.yaml'])
  git(fixture, ['commit', '--quiet', '-m', 'empty'])
  assert.throws(
    () => extractOpenApiBaseline({ repositoryRoot: fixture, reference: 'HEAD', outputPath, metadataPath }),
    /DELIVERY/u,
  )

  fs.rmSync(contractPath)
  git(fixture, ['add', '-A'])
  git(fixture, ['commit', '--quiet', '-m', 'missing'])
  assert.throws(
    () => extractOpenApiBaseline({ repositoryRoot: fixture, reference: 'HEAD', outputPath, metadataPath }),
    /DELIVERY/u,
  )
})

test('OpenAPI compatibility check fails when no baseline is supplied', () => {
  const result = spawnSync(process.execPath, [path.join(repositoryRoot, 'tools', 'openapi', 'check-openapi-compat.mjs')], {
    cwd: repositoryRoot,
    encoding: 'utf8',
  })
  assert.notEqual(result.status, 0)
  assert.match(`${result.stdout}\n${result.stderr}`, /必须显式传入历史基线/u)
})

test('OpenAPI compatibility keeps incompatible changes fatal without global rule exceptions', () => {
  const source = fs.readFileSync(path.join(repositoryRoot, 'tools', 'openapi', 'openapi-diff-pom.xml'), 'utf8')
  assert.match(source, /<failOnIncompatible>true<\/failOnIncompatible>/u)
  assert.deepEqual(
    source.match(/<incompatible\.[^>]+>false<\/incompatible\.[^>]+>/gu) ?? [],
    [],
  )
})

test('portable handoff rejects tampering, missing and extra files', (context) => {
  const fixture = handoffFixture(context)
  assert.doesNotThrow(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit))

  fs.appendFileSync(path.join(fixture.root, 'web', 'index.html'), 'tampered', 'utf8')
  assert.throws(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit), /DELIVERY/u)
  fs.writeFileSync(path.join(fixture.root, 'web', 'index.html'), '<main>DELIVERY</main>', 'utf8')
  fs.rmSync(path.join(fixture.root, 'server', 'yumpoo-server.jar'))
  assert.throws(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit), /DELIVERY/u)
  fs.writeFileSync(path.join(fixture.root, 'server', 'yumpoo-server.jar'), 'jar-bytes', 'utf8')
  fs.writeFileSync(path.join(fixture.root, 'web', 'extra.js'), 'extra', 'utf8')
  assert.throws(() => assertExactPayload(fixture.root, fixture.manifest, 'portable-handoff.json', fixture.commit), /DELIVERY/u)
})

test('portable handoff rejects Windows case collisions', { skip: process.platform === 'win32' ? '由必需 Linux job 构造大小写碰撞文件；Windows 文件系统不支持此夹具' : false }, (context) => {
  const fixture = handoffFixture(context)
  fs.writeFileSync(path.join(fixture.root, 'web', 'INDEX.HTML'), 'collision', 'utf8')
  assert.throws(
    () => fileRecords(fixture.root, collectRegularFiles(fixture.root), () => 'WEB_ASSET'),
    /DELIVERY/u,
  )
})

test('portable handoff rejects symbolic links', { skip: process.platform === 'win32' ? '由必需 Linux job 构造符号链接；Windows 需要额外 OS 权限' : false }, (context) => {
  const fixture = handoffFixture(context)
  fs.symlinkSync(path.join(fixture.root, 'server', 'yumpoo-server.jar'), path.join(fixture.root, 'web', 'jar-link'))
  assert.throws(() => collectRegularFiles(fixture.root), /DELIVERY/u)
})

test('path allowlist rejects traversal, absolute paths and backslashes', () => {
  assert.equal(isSafeRelativePath('backup-restore/verification-report.json'), true)
  for (const candidate of ['../report.json', '/tmp/report.json', 'C:/report.json', 'backup-restore\\report.json', 'backup-restore//report.json']) {
    assert.equal(isSafeRelativePath(candidate), false)
  }
})

test('recursive output targets must stay inside the owned output directory', (context) => {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-owned-'))
  context.after(() => fs.rmSync(parent, { recursive: true, force: true }))
  assert.equal(assertDirectoryTargetWithin(parent, path.join(parent, 'evidence-pack'), 'fixture'), path.join(parent, 'evidence-pack'))
  assert.throws(() => assertDirectoryTargetWithin(parent, parent, 'fixture'), /DELIVERY/u)
  assert.throws(() => assertDirectoryTargetWithin(parent, path.dirname(parent), 'fixture'), /DELIVERY/u)
})


function handoffFixture(context) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m018-handoff-'))
  context.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.mkdirSync(path.join(root, 'server'), { recursive: true })
  fs.mkdirSync(path.join(root, 'web'), { recursive: true })
  fs.writeFileSync(path.join(root, 'server', 'yumpoo-server.jar'), 'jar-bytes', 'utf8')
  fs.writeFileSync(path.join(root, 'web', 'index.html'), '<main>DELIVERY</main>', 'utf8')
  const files = fileRecords(root, collectRegularFiles(root), (relativePath) =>
    relativePath === 'server/yumpoo-server.jar' ? 'SERVER_JAR' : 'WEB_ASSET',
  )
  const commit = 'a'.repeat(40)
  fs.writeFileSync(path.join(root, 'portable-handoff.json'), '{}\n', 'utf8')
  return { root, commit, manifest: { sourceCommit: commit, files } }
}

function git(cwd, args) {
  const result = spawnSync('git', args, { cwd, encoding: 'utf8' })
  assert.equal(result.status, 0, `${result.stderr || result.stdout}`)
  return result.stdout
}
