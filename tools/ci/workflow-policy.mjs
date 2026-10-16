import assert from 'node:assert/strict'
import { requiredJobs } from './gate.mjs'
import { attestedFileNames, artifactFileNames } from '../release/policy.mjs'

export function assertWorkflowSafety(workflow) {
  assert.deepEqual(workflow.permissions, { contents: 'read' })
  // dev 是集成分支，main 是已发布线：两者的 PR 与推送都跑同一套完整门禁。
  assert.deepEqual(workflow.on.pull_request.branches, ['dev', 'main'])
  assert.deepEqual(workflow.on.push.branches, ['dev', 'main'])
  assert(!workflow.on.push.paths && !workflow.on.push['paths-ignore'], '推送工作流不能被路径过滤漏跑')
  assert(!workflow.on.pull_request.paths && !workflow.on.pull_request['paths-ignore'], 'PR 工作流不能被路径过滤漏跑')
  assert(!workflow.on.pull_request_target, 'PR 代码不得使用特权触发器')
  const gate = workflow.jobs.windows
  assert.equal(gate.name, 'M0 Windows x64 Gate')
  assert.equal(workflow.jobs.linux.name, 'M0 Portable Gate')
  assert.equal(gate.if, '${{ always() }}')
  assert.deepEqual([...gate.needs].sort(), [...requiredJobs].sort())
  assert.deepEqual(Object.keys(workflow.jobs).filter(id => id !== 'windows').sort(), [...requiredJobs].sort(), '新增任务也必须进入合并门禁')
  const gateIndex = gate.steps.findIndex(step => step.run === 'node tools/ci/gate.mjs'
    && step.env?.NEEDS_JSON === '${{ toJSON(needs) }}' && !step.if)
  const cancellationIndex = gate.steps.findIndex(step => step.if === '${{ cancelled() }}' && step.run === 'exit 1')
  assert(gateIndex >= 0 && cancellationIndex >= 0 && cancellationIndex < gateIndex, '取消状态必须在正常门禁前明确失败')
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
      assert(!/\b(always|cancelled|success|failure)\s*\(/u.test(JSON.stringify([step.env, step.run, step.with])),
        'Actions 状态函数只能在 if 上下文使用')
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
  assertBuildAttestation(workflow)
}

// 构建证明只由打包任务签署：OIDC 与证明写权限不外溢，签署对象正是随后上传、发布的同一批文件。
function assertBuildAttestation(workflow) {
  const delivery = workflow.jobs.windows_delivery
  assert.deepEqual(delivery.permissions, { contents: 'read', 'id-token': 'write', attestations: 'write' })
  for (const [id, job] of Object.entries(workflow.jobs)) {
    if (id !== 'windows_delivery') assert(!job.permissions, `${id} 不得扩大 token 权限`)
  }
  const steps = delivery.steps
  const attest = steps.findIndex(step => step.uses?.startsWith('actions/attest-build-provenance@'))
  const packaging = steps.findIndex(step => step.run === 'pnpm ci:windows')
  const upload = steps.findIndex(step => step.uses?.startsWith('actions/upload-artifact@') && step.with?.name?.startsWith('m1-15-server-'))
  assert(packaging < attest && attest < upload, '构建证明必须签署打包结果，并在上传前完成')
  assert.equal(steps[attest].if, "${{ github.event_name == 'push' }}", '只有推送 CI 签署构建证明')
  const subjects = steps[attest].with['subject-path'].trim().split('\n').map(line => line.trim())
  assert.deepEqual(subjects, attestedFileNames.map(name => `out/m1-15/${name}`))
  const uploaded = steps[upload].with.path.trim().split('\n').map(line => line.trim())
  assert.deepEqual(uploaded.map(file => file.replace(/^out\/m1-15\//u, '')).sort(), [...artifactFileNames].sort())
}
