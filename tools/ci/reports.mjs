export function assertStageReport(report, { stage, sourceHash, sourceCommit, baseRef, steps }) {
  if (report?.status !== 'PASS' || report.stage !== stage || report.sourceHash !== sourceHash
    || report.sourceCommit !== sourceCommit || report.baseRef !== baseRef
    || !Number.isFinite(Date.parse(report.startedAt)) || !Number.isFinite(Date.parse(report.completedAt))
    || Date.parse(report.completedAt) < Date.parse(report.startedAt)
    || !Array.isArray(report.steps)
    || JSON.stringify(report.steps.map(step => [step.id, step.status])) !== JSON.stringify(steps.map(step => [step.id, 'PASS']))) {
    throw new Error(`必须先对当前源码成功执行 pnpm ci:${stage}；拒绝旧报告或不完整执行`)
  }
}
