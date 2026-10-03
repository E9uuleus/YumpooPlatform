import { describe, expect, it } from 'vitest'
import { connectColumnKey, isConnectColumnKey, suggestedConnectColumnName } from './connectColumnKeys'

describe('连接列键与名称', () => {
  it('独立命名空间与被连接系统列不会接受内置键', () => {
    expect(connectColumnKey('column-1')).toBe('connect:column-1')
    expect(['connect:column-1', 'connect-incoming', 'status', 'connect:', 'connect:a:b'].map(isConnectColumnKey)).toEqual([true, true, false, false, false])
  })
  it('从第一个空闲的递增名称开始', () => {
    expect(suggestedConnectColumnName([])).toBe('连接项目')
    expect(suggestedConnectColumnName(['连接项目', '连接项目 2', '连接项目 4'])).toBe('连接项目 3')
  })
})
