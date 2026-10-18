import path from 'node:path'
import type { DesktopDistribution } from './distribution'

export const SELF_CHECK_ARGUMENT = '--self-check='
export const UPDATE_CHECK_ARGUMENT = '--update-check-now='

export interface SelfCheckReport {
  readonly schemaVersion: 1
  readonly status: 'PASS' | 'FAIL'
  readonly version: string
  readonly electronVersion: string
  readonly packaged: boolean
  readonly distributionOrigin: string | null
  readonly protocolRegistered: boolean
  readonly safeStorageAvailable: boolean
  readonly diagnosticsPage: boolean
  readonly failures: readonly string[]
}

export interface SelfCheckDependencies {
  readonly isPackaged: boolean
  readonly version: string
  readonly electronVersion: string
  loadDistribution(): DesktopDistribution
  isDefaultProtocolClient(protocol: string): boolean
  isEncryptionAvailable(): boolean
  /** Renders the packaged diagnostics page offline and reports whether it reached its ready state. */
  checkDiagnosticsPage(): Promise<boolean>
}

/** The report path is the argument's own value; a relative or missing path disables the check. */
export function selfCheckReportPath(processArguments: readonly string[], prefix = SELF_CHECK_ARGUMENT): string | undefined {
  const argument = processArguments.find(candidate => candidate.startsWith(prefix))
  const reportPath = argument?.slice(prefix.length)
  return reportPath && path.isAbsolute(reportPath) ? path.normalize(reportPath) : undefined
}

/**
 * Offline installation check: the asar integrity fuse already refused a tampered archive before this
 * code ran, so the report covers what the installer and the build must have provided.
 */
export async function runSelfCheck(dependencies: SelfCheckDependencies): Promise<SelfCheckReport> {
  const failures: string[] = []
  if (!dependencies.isPackaged) failures.push('NOT_PACKAGED')
  let distributionOrigin: string | null = null
  try {
    distributionOrigin = dependencies.loadDistribution().origin
  } catch {
    failures.push('DISTRIBUTION_INVALID')
  }
  const protocolRegistered = dependencies.isDefaultProtocolClient('yumpoo')
  if (!protocolRegistered) failures.push('PROTOCOL_NOT_REGISTERED')
  const diagnosticsPage = await dependencies.checkDiagnosticsPage().catch(() => false)
  if (!diagnosticsPage) failures.push('DIAGNOSTICS_PAGE_FAILED')
  return {
    schemaVersion: 1,
    status: failures.length === 0 ? 'PASS' : 'FAIL',
    version: dependencies.version,
    electronVersion: dependencies.electronVersion,
    packaged: dependencies.isPackaged,
    distributionOrigin,
    protocolRegistered,
    safeStorageAvailable: dependencies.isEncryptionAvailable(),
    diagnosticsPage,
    failures,
  }
}
