import assert from 'node:assert/strict'
import test from 'node:test'
import { currentMainCommit, mainHasChangesMissingFromDev, openBackMerge } from './back-merge.mjs'

const mainSha = '0123456789abcdef0123456789abcdef01234567'
const olderHotfix = 'a'.repeat(40)
const updatedBranch = 'b'.repeat(40)

// order maps "base...head" to the GitHub compare status.
function fakeGithub(open = [], order = {}) {
  return {
    writes: [],
    list: async endpoint => { assert.equal(endpoint, '/pulls?state=open&base=dev&per_page=100'); return open },
    get: async endpoint => {
      const match = endpoint.match(/^\/compare\/([0-9a-f]{40})\.\.\.([0-9a-f]{40})\?per_page=1$/u)
      assert(match, `Unexpected GitHub request: ${endpoint}`)
      return { status: order[`${match[1]}...${match[2]}`] ?? 'diverged' }
    },
    send: async function (method, endpoint, body) {
      this.writes.push({ method, endpoint, body })
      return endpoint === '/pulls' ? { number: 12, html_url: 'https://example.invalid/pull/12' } : {}
    },
  }
}

const run = options => openBackMerge({ root: '.', mainSha, log: () => {}, hasChanges: () => true, ...options })

test('a dev-to-main merge commit alone needs no back-merge', async () => {
  const github = fakeGithub()
  assert.equal(await run({ github, hasChanges: () => false }), null)
  assert.deepEqual(github.writes, [])
})

test('hotfix content opens one back-merge PR from a dedicated branch at the main commit', async () => {
  const github = fakeGithub()
  const pull = await run({ github })
  assert.equal(pull.number, 12)
  assert.deepEqual(github.writes[0], { method: 'POST', endpoint: '/git/refs', body: { ref: 'refs/heads/backmerge/0123456789ab', sha: mainSha } })
  assert.equal(github.writes[1].body.head, 'backmerge/0123456789ab')
  assert.equal(github.writes[1].body.base, 'dev')
  assert.match(github.writes[1].body.body, /Create a merge commit/u)
})

test('an open back-merge PR is reused only when it already contains the current main', async () => {
  const pull = { number: 3, head: { ref: 'backmerge/aaaa', sha: olderHotfix } }
  const github = fakeGithub([pull], { [`${mainSha}...${olderHotfix}`]: 'identical' })
  assert.equal((await run({ github })).number, 3)
  assert.deepEqual(github.writes, [])
})

// REL-P3 F12: a second hotfix while the first back-merge PR is still open must not be swallowed.
test('a later hotfix fast-forwards the open back-merge branch instead of being skipped', async () => {
  const pull = { number: 3, head: { ref: 'backmerge/aaaa', sha: olderHotfix } }
  const github = fakeGithub([pull], { [`${mainSha}...${olderHotfix}`]: 'behind', [`${olderHotfix}...${mainSha}`]: 'ahead' })
  assert.equal((await run({ github })).number, 3)
  assert.deepEqual(github.writes, [{ method: 'PATCH', endpoint: '/git/refs/heads/backmerge/aaaa', body: { sha: mainSha, force: false } }])
})

test('a back-merge branch already updated with dev is left alone and a new PR covers the newer hotfix', async () => {
  const pull = { number: 3, head: { ref: 'backmerge/aaaa', sha: updatedBranch } }
  const github = fakeGithub([pull], { [`${mainSha}...${updatedBranch}`]: 'diverged', [`${updatedBranch}...${mainSha}`]: 'diverged' })
  assert.equal((await run({ github })).number, 12)
  assert(!github.writes.some(write => write.method === 'PATCH'))
  assert.deepEqual(github.writes.map(write => write.endpoint), ['/git/refs', '/pulls'])
})

test('invalid commits are rejected and git results map to clear decisions', async () => {
  await assert.rejects(run({ github: fakeGithub(), mainSha: 'main' }), /Invalid main commit/u)
  assert.equal(mainHasChangesMissingFromDev('.', () => ({ status: 0 })), false)
  assert.equal(mainHasChangesMissingFromDev('.', () => ({ status: 1 })), true)
  assert.throws(() => mainHasChangesMissingFromDev('.', () => ({ status: 128, stderr: 'bad revision' })), /bad revision/u)
  assert.equal(currentMainCommit('.', () => ({ status: 0, stdout: `${mainSha}\n` })), mainSha)
  assert.throws(() => currentMainCommit('.', () => ({ status: 128, stdout: '', stderr: 'unknown revision' })), /unknown revision/u)
})
