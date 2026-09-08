const node = (id, file, ...args) => ({ id, tool: 'node', args: [file, ...args] })
const pnpm = (id, ...args) => ({ id, tool: 'pnpm', args })
const mvn = (id, ...args) => ({ id, tool: 'maven', args })

export function contractSteps(baseRef) {
  return [
    node('openapi-baseline', 'tools/openapi/extract-openapi-baseline.mjs', baseRef,
      'out/m0-18/openapi-baseline.yaml', 'out/m0-18/openapi-baseline.metadata.json'),
    node('openapi-compatibility', 'tools/openapi/check-openapi-compat.mjs', 'out/m0-18/openapi-baseline.yaml'),
    pnpm('openapi-compatibility-tests', 'run', 'test:openapi-compat'),
    pnpm('openapi-lint', 'run', 'lint:openapi'),
    pnpm('openapi-examples', 'run', 'validate:openapi-examples'),
    pnpm('generated-client', 'run', 'check:api-client'),
    pnpm('event-schemas', 'run', 'validate:event-contracts'),
    node('event-baseline', 'tools/events/extract-event-contract-baseline.mjs', baseRef, 'out/ci/event-baseline.json'),
    node('event-compatibility', 'tools/events/check-event-contract-compat.mjs', 'out/ci/event-baseline.json'),
    pnpm('event-inventory', 'run', 'audit:workitem-event-inventory'),
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
  ...['m0-12', 'm0-13', 'm0-14'].map(milestone =>
    node(`${milestone}-live-evidence`, `tools/verification/verify-${milestone}-live.mjs`, '--validate-evidence')),
  node('m0-17-evidence', 'tools/verification/verify-m0-17.mjs', '--validate-contracts'),
  node('m0-18-evidence', 'tools/verification/verify-m0-18-evidence.mjs'),
  node('m1-13-evidence', 'tools/verification/verify-m1-13-evidence.mjs'),
  node('m1-15-assets', 'tools/verification/verify-m1-15-assets.mjs'),
  node('m1-15-deployment', 'tools/verification/verify-m1-15-deployment-assets.mjs'),
  ...['01', '02', '03', '04', '06', '08', '21a', '21', '22', '23', '24'].map(step =>
    node(`m2-${step}-assets`, `tools/verification/verify-m2-${step}-assets.mjs`)),
  node('content-contract', 'tools/verification/verify-content-category-refactor-assets.mjs'),
  node('historical-milestones', 'tools/ci/historical-assets.mjs'),
]

export function plan(stage, baseRef = 'origin/dev') {
  switch (stage) {
    case 'contracts': return contractSteps(baseRef)
    case 'static': return [
      pnpm('documentation', 'run', 'doc-sync'),
      pnpm('agent-note-tests', 'run', 'test:agent-notes'),
      pnpm('ci-policy-tests', 'run', 'test:ci'),
      pnpm('event-policy-tests', 'run', 'test:event-contract-compat'),
      pnpm('delivery-policy-tests', 'run', 'test:m0-18'),
      ...contractSteps(baseRef), ...nodeSteps, ...assetSteps,
    ]
    case 'backend': return [
      mvn('backend-regression', 'clean', 'verify'),
      mvn('bounded-heap', '-q', '-Dtest=M014BoundedHeapVerification', '-DargLine=-Xmx96m', 'test'),
    ]
    case 'portable': return [
      node('backup-restore-evidence', 'tools/verification/verify-m0-17.mjs', '--validate-generated-after', '$BACKEND_STARTED'),
      node('packaged-http', 'tools/verification/verify-m1-13-http.mjs'),
      node('packaged-http-evidence', 'tools/verification/verify-m1-13-evidence.mjs', '--require-report'),
      node('create-handoff', 'tools/verification/create-m0-18-handoff.mjs'),
      node('verify-handoff', 'tools/verification/verify-m0-18-handoff.mjs'),
    ]
    case 'windows': return [
      node('windows-evidence', 'tools/verification/verify-m0-18-windows.mjs'),
      node('powershell-behavior', 'tools/verification/verify-m1-15-powershell.mjs'),
      node('server-package', 'tools/verification/package-m1-15-win.mjs'),
      node('server-package-integrity', 'tools/verification/verify-m1-15-package.mjs'),
    ]
    default: throw new Error(`未知 CI 阶段：${stage}`)
  }
}
