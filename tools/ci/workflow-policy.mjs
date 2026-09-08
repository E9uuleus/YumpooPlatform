import assert from 'node:assert/strict'
import { requiredJobs } from './gate.mjs'

export function assertWorkflowSafety(workflow) {
  assert.deepEqual(workflow.permissions, { contents: 'read' })
  assert.deepEqual(workflow.on.pull_request.branches, ['dev'])
  assert(!workflow.on.pull_request.paths && !workflow.on.pull_request['paths-ignore'], 'PR 工作流不能被路径过滤漏跑')
  assert(!workflow.on.pull_request_target, 'PR 代码不得使用特权触发器')
  const gate = workflow.jobs.windows
  assert.equal(gate.name, 'M0 Windows x64 Gate')
  assert.equal(workflow.jobs.linux.name, 'M0 Portable Gate')
  assert.equal(gate.if, '${{ always() }}')
  assert.deepEqual([...gate.needs].sort(), [...requiredJobs].sort())
  assert.deepEqual(Object.keys(workflow.jobs).filter(id => id !== 'windows').sort(), [...requiredJobs].sort(), '新增任务也必须进入合并门禁')
  assert(gate.steps.some(step => step.run === 'node tools/ci/gate.mjs'
    && step.env?.NEEDS_JSON === '${{ toJSON(needs) }}' && step.env.WORKFLOW_CANCELLED === '${{ cancelled() }}' && !step.if))
  for (const [id, commands] of Object.entries({
    linux: ['pnpm ci:static', 'pnpm ci:backend', 'pnpm ci:portable'],
    windows_delivery: ['pnpm ci:windows'],
  })) {
    const job = workflow.jobs[id]
    assert(!job.if, `${id} 不允许条件性成功或静默跳过`)
    assert.equal(job.outputs.completed, '${{ steps.completed.outputs.completed }}')
    const completionIndex = job.steps.findIndex(step => step.id === 'completed')
    assert(completionIndex >= 0 && !job.steps[completionIndex].if)
    for (const command of commands) {
      const index = job.steps.findIndex(step => step.run === command)
      assert(index >= 0 && index < completionIndex && !job.steps[index].if, `必需命令不能缺失或跳过：${command}`)
    }
  }
  assert.deepEqual(workflow.jobs.windows_delivery.needs, ['linux'])
  for (const job of Object.values(workflow.jobs)) {
    assert(!job['continue-on-error'])
    for (const step of job.steps) {
      assert(!step['continue-on-error'], '必需步骤的失败不能被吞掉')
      if (step.uses) assert(/^[^@]+@[0-9a-f]{40}$/u.test(step.uses), 'Action 必须固定 commit')
      if (step.uses?.startsWith('actions/checkout@')) assert.equal(step.with['persist-credentials'], false)
    }
  }
  const upload = workflow.jobs.linux.steps.find(step => step.id === 'handoff_upload')
  const download = workflow.jobs.windows_delivery.steps.find(step => step.uses?.startsWith('actions/download-artifact@'))
  assert.equal(upload.with.name, download.with.name)
  assert(upload.with.name.includes('github.run_id') && upload.with.name.includes('github.run_attempt'))
  assert.equal(upload.with['if-no-files-found'], 'error')
  assert.equal(workflow.jobs.windows_delivery.steps.find(step => step.run === 'pnpm ci:windows')
    .env.YUMPOO_M018_HANDOFF_ARTIFACT_DIGEST, '${{ needs.linux.outputs.handoff-digest }}')
}
