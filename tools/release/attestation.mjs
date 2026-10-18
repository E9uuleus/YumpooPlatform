import path from 'node:path'
import { fileDigest, run } from './github.mjs'
import { repository, ciWorkflowPath, releaseBranch, attestedFileNames, validateAttestations } from './policy.mjs'

export function attestationArgs(file, sourceCommit) {
  return ['attestation', 'verify', file, '--repo', repository,
    '--signer-workflow', `${repository}/${ciWorkflowPath}`, '--source-ref', `refs/heads/${releaseBranch}`,
    '--source-digest', sourceCommit, '--deny-self-hosted-runners', '--format', 'json']
}

// The Sigstore signature is checked independently of the Actions artifact API that supplied the bytes.
// Each verified file is logged, so the run log alone shows which bytes were checked against which CI attempt.
export async function verifyBuildAttestations(directory, ciRun, { env = process.env, execute = run, log = console.log, label = 'release assets', files = attestedFileNames } = {}) {
  for (const name of files) {
    const file = path.join(directory, name)
    const output = execute('gh', attestationArgs(file, ciRun.head_sha), { env, timeout: 300_000 })
    const digest = await fileDigest(file)
    validateAttestations(JSON.parse(output), { file: name, digest, run: ciRun })
    log(`Build attestation verified (${label}): ${name} sha256:${digest} from ${releaseBranch} push CI run ${ciRun.id} attempt ${ciRun.run_attempt}, source ${ciRun.head_sha}`)
  }
}
