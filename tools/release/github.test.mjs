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

test('paginated APIs flatten every page and uploads never use clobber', () => {
  const calls = []
  const github = createGithub({ GH_TOKEN: 'test-token' }, (_command, args) => {
    calls.push(args)
    return JSON.stringify([{ jobs: [{ id: 1 }] }, { jobs: [{ id: 2 }] }])
  })
  assert.deepEqual(github.list('/actions/runs/1/attempts/2/jobs?per_page=100', 'jobs'), [{ id: 1 }, { id: 2 }])
  github.upload('server-v1', '/tmp/package.zip')
  assert(calls[0].includes('--paginate') && calls[0].includes('--slurp'))
  assert(!calls[1].includes('--clobber'))
  assert.throws(() => createGithub({ GITHUB_TOKEN: 'not-an-accepted-credential' }), /GH_TOKEN/u)
})
