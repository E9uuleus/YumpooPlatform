import assert from 'node:assert/strict'
import test from 'node:test'
import { mainHasChangesMissingFromDev, openBackMerge } from './back-merge.mjs'

const mainSha = '0123456789abcdef0123456789abcdef01234567'

function fakeGithub(open = []) {
  return {
    writes: [],
    list: async endpoint => { assert.equal(endpoint, '/pulls?state=open&base=dev&per_page=100'); return open },
    send: async function (method, endpoint, body) {
      this.writes.push({ method, endpoint, body })
      return endpoint === '/pulls' ? { number: 12, html_url: 'https://example.invalid/pull/12' } : {}
    },
  }
}

test('a dev-to-main merge commit alone needs no back-merge', async () => {
  const github = fakeGithub()
  assert.equal(await openBackMerge({ github, root: '.', mainSha, log: () => {}, hasChanges: () => false }), null)
  assert.deepEqual(github.writes, [])
})

test('hotfix content opens one back-merge PR from a dedicated branch at the main commit', async () => {
  const github = fakeGithub()
  const pull = await openBackMerge({ github, root: '.', mainSha, log: () => {}, hasChanges: () => true })
  assert.equal(pull.number, 12)
  assert.deepEqual(github.writes[0], { method: 'POST', endpoint: '/git/refs', body: { ref: 'refs/heads/backmerge/0123456789ab', sha: mainSha } })
  assert.equal(github.writes[1].body.head, 'backmerge/0123456789ab')
  assert.equal(github.writes[1].body.base, 'dev')
  assert.match(github.writes[1].body.body, /Create a merge commit/u)
})

test('an already open back-merge PR is reused and an invalid commit is rejected', async () => {
  const github = fakeGithub([{ number: 3, head: { ref: 'backmerge/aaaa' } }])
  assert.equal((await openBackMerge({ github, root: '.', mainSha, log: () => {}, hasChanges: () => true })).number, 3)
  assert.deepEqual(github.writes, [])
  await assert.rejects(openBackMerge({ github, root: '.', mainSha: 'main', hasChanges: () => true }), /Invalid main commit/u)
})

test('git diff exit codes map to unchanged, changed and failure', () => {
  assert.equal(mainHasChangesMissingFromDev('.', () => ({ status: 0 })), false)
  assert.equal(mainHasChangesMissingFromDev('.', () => ({ status: 1 })), true)
  assert.throws(() => mainHasChangesMissingFromDev('.', () => ({ status: 128, stderr: 'bad revision' })), /bad revision/u)
})
