import { describe, expect, it } from 'vitest'
import { ProjectLifecycle } from '@yumpoo/api-client'
import { connectColumnAutoName, connectColumnKey, connectColumnNameError, isConnectColumnKey, reverseColumnKey, reverseColumnLabels } from './connectColumnKeys'

describe('连接列键与名称', () => {
  it('正向、反向与草稿列使用独立命名空间，不接受内置键', () => {
    expect(connectColumnKey('column-1')).toBe('connect:column-1')
    expect(reverseColumnKey('column-1')).toBe('connect-reverse:column-1')
    expect(['connect:column-1', 'connect-reverse:column-1', 'connect-draft', 'connect-incoming', 'status', 'connect:', 'connect:a:b']
      .map(isConnectColumnKey)).toEqual([true, true, true, false, false, false, false])
  })
  it('列名取连接的项目名，过长时收敛为首个项目加数量，并避开已有和内置名称', () => {
    expect(connectColumnAutoName(['Yumpoo 门户'], [])).toBe('Yumpoo 门户')
    expect(connectColumnAutoName(['华东现场', '华南现场'], [])).toBe('华东现场、华南现场')
    const long = connectColumnAutoName(['一'.repeat(40), '二'.repeat(30), '三'], [])
    expect(long).toBe(`${'一'.repeat(32)} 等 3 个项目`)
    expect(Array.from(long)).toHaveLength(40)
    expect(connectColumnAutoName(['Yumpoo 门户'], ['yumpoo 门户', 'Yumpoo 门户 2'])).toBe('Yumpoo 门户 3')
    expect(connectColumnAutoName(['状态'], [])).toBe('状态 2')
    expect(connectColumnAutoName([], ['连接项目'])).toBe('连接项目 2')
  })
  it('重命名校验必填、40 字上限和大小写不敏感的唯一性', () => {
    expect(connectColumnNameError('  ', [])).toBe('请输入列名称')
    expect(connectColumnNameError('一'.repeat(41), [])).toBe('列名称最多 40 个字符')
    expect(connectColumnNameError('Bug', ['bug'])).toBe('列名称已存在，请换一个名称')
    expect(connectColumnNameError('优先级', [])).toBe('列名称已存在，请换一个名称')
    expect(connectColumnNameError('现场问题', ['产品缺陷'])).toBe('')
  })
  it('反向列以来源项目命名，同一来源项目有多列时附带列名', () => {
    const column = { columnId: 'a', columnName: '实施问题', projectId: 'p1', projectCode: 'P1', projectName: '华东现场',
      projectLifecycle: ProjectLifecycle.Active, actorCanLinkExisting: true }
    expect([...reverseColumnLabels([column, { ...column, columnId: 'b', projectId: 'p2', projectName: '华南现场' }]).values()])
      .toEqual(['华东现场', '华南现场'])
    expect([...reverseColumnLabels([column, { ...column, columnId: 'b', columnName: '交付风险' }]).values()])
      .toEqual(['华东现场 · 实施问题', '华东现场 · 交付风险'])
  })
})
