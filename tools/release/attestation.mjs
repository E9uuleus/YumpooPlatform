import path from 'node:path'
import { fileDigest, run } from './github.mjs'
import { repository, ciWorkflowPath, releaseBranch, attestedFileNames, validateAttestations } from './policy.mjs'

export function attestationArgs(file, sourceCommit) {
  return ['attestation', 'verify', file, '--repo', repository,
    '--signer-workflow', `${repository}/${ciWorkflowPath}`, '--source-ref', `refs/heads/${releaseBranch}`,
    '--source-digest', sourceCommit, '--deny-self-hosted-runners', '--format', 'json']
}

// The Sigstore signature is checked independently of the Actions artifact API that supplied the bytes.
export async function verifyBuildAttestations(directory, ciRun, { env = process.env, execute = run } = {}) {
  for (const name of attestedFileNames) {
    const file = path.join(directory, name)
    const output = execute('gh', attestationArgs(file, ciRun.head_sha), { env, timeout: 300_000 })
    validateAttestations(JSON.parse(output), { file: name, digest: await fileDigest(file), run: ciRun })
  }
}
