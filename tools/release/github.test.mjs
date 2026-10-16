import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createGithub } from './github.mjs'

test('GitHub downloads use endpoint-specific Accept headers and keep the token out of process arguments', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-github-test-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const calls = []
  const github = createGithub({ GH_TOKEN: 'test-token' }, (command, args, options) => {
    calls.push({ command, args, options })
    fs.writeSync(options.stdio[1], 'binary bytes')
  })
  github.download('/actions/artifacts/99/zip', path.join(directory, 'artifact.zip'))
  github.download('/releases/assets/88', path.join(directory, 'package.zip'))
  assert(calls[0].args.includes('Accept: application/vnd.github+json'))
  assert(calls[1].args.includes('Accept: application/octet-stream'))
  for (const call of calls) {
    assert.equal(call.command, 'gh')
    assert(!call.args.some(value => value.includes('test-token')))
    assert.equal(call.options.env.GH_TOKEN, 'test-token')
  }
  assert.equal(fs.readFileSync(path.join(directory, 'artifact.zip'), 'utf8'), 'binary bytes')
})

test('paginated APIs flatten every page; writes send JSON on stdin and draft assets go to the upload host by ID', () => {
  const calls = []
  const github = createGithub({ GH_TOKEN: 'test-token' }, (_command, args, options) => {
    calls.push({ args, options })
    return calls.length === 1 ? JSON.stringify([{ jobs: [{ id: 1 }] }, { jobs: [{ id: 2 }] }]) : '{"id":7}'
  })
  assert.deepEqual(github.list('/actions/runs/1/attempts/2/jobs?per_page=100', 'jobs'), [{ id: 1 }, { id: 2 }])
  assert.deepEqual(github.send('POST', '/releases', { tag_name: 'server-v2026.10.20.1', draft: true }), { id: 7 })
  github.uploadAsset(7, '/tmp/provenance.json')
  assert(calls[0].args.includes('--paginate') && calls[0].args.includes('--slurp'))
  assert(calls[1].args.includes('POST') && calls[1].args.includes('--input') && JSON.parse(calls[1].options.input).draft === true)
  assert(calls[2].args[1].startsWith('https://uploads.github.com/repos/E9uuleus/YumpooPlatform/releases/7/assets?name=provenance.json'))
  assert(!calls.some(call => call.args.includes('--clobber')))
  assert.throws(() => github.send('PUT', '/releases/7', {}), /Unsupported/u)
  assert.throws(() => github.send('DELETE', '/releases/7'), /Unsupported/u)
  assert.throws(() => createGithub({ GITHUB_TOKEN: 'not-an-accepted-credential' }), /GH_TOKEN/u)
})
