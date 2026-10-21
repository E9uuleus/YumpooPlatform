const node = (id, file, ...args) => ({ id, tool: 'node', args: [file, ...args] })
const pnpm = (id, ...args) => ({ id, tool: 'pnpm', args })
const mvn = (id, ...args) => ({ id, tool: 'maven', args })

export function contractSteps(baseRef) {
  return [
    node('openapi-bundle', 'tools/openapi/openapi-bundle.mjs', '--check'),
    node('openapi-baseline', 'tools/openapi/extract-openapi-baseline.mjs', baseRef,
      'out/contracts/openapi-baseline.yaml', 'out/contracts/openapi-baseline.metadata.json'),
    node('openapi-compatibility', 'tools/openapi/check-openapi-compat.mjs', 'out/contracts/openapi-baseline.yaml'),
    pnpm('openapi-compatibility-tests', 'run', 'test:openapi-compat'),
    pnpm('openapi-lint', 'run', 'lint:openapi'),
    pnpm('openapi-examples', 'run', 'validate:openapi-examples'),
    pnpm('generated-client', 'run', 'check:api-client'),
    pnpm('event-schemas', 'run', 'validate:event-contracts'),
    node('event-baseline', 'tools/events/extract-event-contract-baseline.mjs', baseRef, 'out/ci/event-baseline.json'),
    node('event-compatibility', 'tools/events/check-event-contract-compat.mjs', 'out/ci/event-baseline.json'),
    pnpm('event-inventory', 'run', 'audit:workitem-event-inventory'),
    node('migration-rollback-safety', 'tools/ci/migration-policy.mjs', baseRef),
  ]
}

export const nodeSteps = [
  pnpm('lint', 'run', 'lint'),
  pnpm('workspace-boundaries', 'run', 'test:architecture'),
  pnpm('preload-contract', '--filter', '@yumpoo/preload-contract', 'build'),
  pnpm('client-types', '--filter', '@yumpoo/api-client', 'typecheck'),
  pnpm('client-build', '--filter', '@yumpoo/api-client', 'build'),
  pnpm('web-build-and-types', '--filter', '@yumpoo/web-app', 'build'),
  pnpm('desktop-build-and-types', '--filter', '@yumpoo/desktop-shell', 'build'),
  pnpm('client-tests', '--filter', '@yumpoo/api-client', 'test'),
  pnpm('web-tests', '--filter', '@yumpoo/web-app', 'test'),
  pnpm('desktop-tests', '--filter', '@yumpoo/desktop-shell', 'test'),
]

export const assetSteps = [
  node('brand-assets', 'tools/branding/generate-assets.mjs', '--check'),
  node('deployment-config', 'tools/verification/verify-deployment-config.mjs'),
]

export function plan(stage, baseRef = 'origin/dev') {
  switch (stage) {
    case 'contracts': return contractSteps(baseRef)
    case 'static': return [
      pnpm('documentation', 'run', 'doc-sync'),
      pnpm('agent-note-tests', 'run', 'test:agent-notes'),
      pnpm('ci-policy-tests', 'run', 'test:ci'),
      pnpm('event-policy-tests', 'run', 'test:event-contract-compat'),
      pnpm('delivery-policy-tests', 'run', 'test:delivery'),
      node('release-policy-tests', '--test', 'tools/release/github.test.mjs', 'tools/release/policy.test.mjs', 'tools/release/promote-release.test.mjs', 'tools/release/prepare-release.test.mjs', 'tools/release/release-notes.test.mjs', 'tools/release/promotion-policy.test.mjs', 'tools/release/back-merge.test.mjs', 'tools/release/ssh-deploy.test.mjs', 'tools/release/version-placeholders.test.mjs', 'tools/release/attestation.test.mjs', 'tools/release/rehearse-release.test.mjs', 'tools/release/desktop-update-signature.test.mjs', 'tools/release/desktop-release.test.mjs'),
      node('version-placeholders', 'tools/release/version-placeholders.mjs'),
      ...contractSteps(baseRef), ...nodeSteps, ...assetSteps,
    ]
    case 'backend': return [
      mvn('backend-regression', 'clean', 'verify'),
      mvn('bounded-heap', '-q', '-Dtest=M014BoundedHeapVerification', '-DargLine=-Xmx96m', 'test'),
    ]
    case 'portable': return [
      node('backup-restore-output', 'tools/verification/verify-backup-restore-output.mjs', '$BACKEND_STARTED'),
      node('packaged-http', 'tools/verification/smoke-packaged-http.mjs'),
      node('packaged-http-report', 'tools/verification/verify-packaged-http-report.mjs'),
      node('create-handoff', 'tools/verification/create-portable-handoff.mjs'),
      node('verify-handoff', 'tools/verification/verify-portable-handoff.mjs'),
    ]
    case 'windows': return [
      node('windows-toolchain', 'tools/verification/verify-windows-toolchain.mjs'),
      node('desktop-profile-tests', '--test', 'tools/verification/desktop-profile.test.mjs'),
      node('windows-release-restore', 'tools/release/verify-windows.mjs'),
      node('windows-handoff', 'tools/verification/verify-portable-handoff.mjs'),
      node('desktop-smoke', 'tools/verification/smoke-desktop.mjs', '--handoff', 'out/handoff'),
      node('desktop-package', 'tools/verification/package-desktop-win.mjs'),
      node('desktop-package-integrity', 'tools/verification/verify-desktop-package.mjs'),
      node('desktop-installer', 'tools/verification/verify-desktop-installer.mjs'),
      node('desktop-update', 'tools/verification/verify-desktop-update.mjs'),
      node('desktop-release-artifact', 'tools/verification/collect-desktop-release.mjs'),
      node('powershell-behavior', 'tools/verification/verify-bootstrap-powershell.mjs'),
      node('server-package', 'tools/verification/package-server-win.mjs'),
      node('server-package-integrity', 'tools/verification/verify-server-package.mjs'),
    ]
    default: throw new Error(`未知 CI 阶段：${stage}`)
  }
}
