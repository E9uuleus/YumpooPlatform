import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { attestationArgs, verifyBuildAttestations } from './attestation.mjs'
import { repository, ciWorkflowPath, attestedFileNames } from './policy.mjs'
import { attestationResult, digest } from './test-fixtures.mjs'

const run = { id: 3001, run_attempt: 2, head_sha: 'a'.repeat(40) }

test('gh verifies the signer workflow, main ref, source commit and hosted runner', () => {
  const args = attestationArgs('pkg.zip', run.head_sha)
  const flag = name => args[args.indexOf(name) + 1]
  assert.deepEqual(args.slice(0, 3), ['attestation', 'verify', 'pkg.zip'])
  assert.equal(flag('--repo'), repository)
  assert.equal(flag('--signer-workflow'), `${repository}/${ciWorkflowPath}`)
  assert.equal(flag('--source-ref'), 'refs/heads/main')
  assert.equal(flag('--source-digest'), run.head_sha)
  assert.equal(flag('--format'), 'json')
  assert(args.includes('--deny-self-hosted-runners'))
})

test('every attested file must verify against the exact CI attempt; a gh failure fails closed', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-attest-test-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  for (const name of attestedFileNames) fs.writeFileSync(path.join(directory, name), `bytes of ${name}`)
  const calls = []
  const execute = (command, args, options) => {
    calls.push({ command, file: path.basename(args[2]), token: options.env.GH_TOKEN })
    const name = path.basename(args[2])
    return JSON.stringify([attestationResult({ name, sha256: digest(`bytes of ${name}`), run })])
  }
  await verifyBuildAttestations(directory, run, { env: { GH_TOKEN: 'token' }, execute })
  assert.deepEqual(calls.map(call => call.file), attestedFileNames)
  assert(calls.every(call => call.command === 'gh' && call.token === 'token'))

  fs.writeFileSync(path.join(directory, attestedFileNames[1]), 'bytes replaced after signing')
  await assert.rejects(verifyBuildAttestations(directory, run, { env: {}, execute }), /has no build attestation/u)
  const failing = () => { throw new Error('gh failed (1): no attestations found') }
  await assert.rejects(verifyBuildAttestations(directory, run, { env: {}, execute: failing }), /no attestations found/u)
})
