import { createHash } from 'node:crypto'
import { packageName, repository, ciWorkflowPath, sboms } from './policy.mjs'

export const digest = value => createHash('sha256').update(value).digest('hex')

export function sbomBytes() {
  return new Map(sboms.map(({ name, component }) => [name, Buffer.from(JSON.stringify({
    bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: `urn:uuid:${name}`,
    metadata: { component: { type: 'application', ...component, version: '0.0.0' } },
    components: [{ type: 'library', name: 'example', version: '1.0.0', purl: 'pkg:npm/example@1.0.0' }],
  }))]))
}

export function packageManifest(sourceCommit, packageBytes, sbomFiles) {
  return {
    schemaVersion: 2, milestone: 'M1-15', sourceCommit,
    server: { platform: 'windows', architecture: 'x64', runtimeMode: 'WINDOWS_SERVICE_WINSW' },
    artifacts: [{ name: packageName, bytes: packageBytes.length, sha256: digest(packageBytes) }],
    sboms: [...sbomFiles].map(([name, bytes]) => ({ name, format: 'CycloneDX', bytes: bytes.length, sha256: digest(bytes) })),
  }
}

// What `gh attestation verify --format json` returns for a file attested by the given CI attempt.
export function attestationResult({ name, sha256, run, ref = 'refs/heads/main', event = 'push' }) {
  return {
    attestation: {},
    verificationResult: {
      statement: {
        predicateType: 'https://slsa.dev/provenance/v1',
        subject: [{ name, digest: { sha256 } }],
        predicate: {
          buildDefinition: {
            externalParameters: { workflow: { path: ciWorkflowPath, ref, repository: `https://github.com/${repository}` } },
            internalParameters: { github: { event_name: event } },
            resolvedDependencies: [{ uri: `git+https://github.com/${repository}@${ref}`, digest: { gitCommit: run.head_sha } }],
          },
          runDetails: { metadata: { invocationId: `https://github.com/${repository}/actions/runs/${run.id}/attempts/${run.run_attempt}` } },
        },
      },
    },
  }
}
