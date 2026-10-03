import { beforeEach, describe, expect, it } from 'vitest'
import { connectColumnPrefsKey, readConnectColumnPrefs, saveConnectColumnPrefs } from './connectColumnPrefs'

const key = connectColumnPrefsKey('company', 'user', 'project')
beforeEach(() => localStorage.clear())
describe('每项目连接列偏好', () => {
  it('按公司、用户、项目隔离，不改变跨项目内置偏好', () => {
    localStorage.setItem('yumpoo:project-work-items:table:v1', 'built-in')
    saveConnectColumnPrefs(key, { hidden: ['connect:a'], widths: { 'connect:a': 310 } })
    expect(readConnectColumnPrefs(key, ['connect:a']).hidden).toEqual(['connect:a'])
    expect(readConnectColumnPrefs(connectColumnPrefsKey('company', 'other', 'project'), ['connect:a']).hidden).toEqual([])
    expect(readConnectColumnPrefs(connectColumnPrefsKey('company', 'user', 'other'), ['connect:a']).hidden).toEqual([])
    expect(localStorage.getItem('yumpoo:project-work-items:table:v1')).toBe('built-in')
  })
  it('丢弃失效列键并应用默认宽度和最小宽度', () => {
    localStorage.setItem(key, JSON.stringify({ hidden: ['status', 'connect:deleted', 'connect:a'], widths: { 'connect:a': 42, 'connect-incoming': 100, 'connect:deleted': 999 } }))
    expect(readConnectColumnPrefs(key, ['connect:a', 'connect:b', 'connect-incoming'])).toEqual({ hidden: ['connect:a'], widths: { 'connect:a': 140, 'connect:b': 200, 'connect-incoming': 160 } })
  })
  it.each(['broken', 'null', '[]', '{"hidden":"bad","widths":{"connect:a":"bad"}}'])('忽略损坏数据 %s', value => {
    localStorage.setItem(key, value)
    expect(readConnectColumnPrefs(key, ['connect:a', 'connect-incoming'])).toEqual({ hidden: [], widths: { 'connect:a': 200, 'connect-incoming': 220 } })
  })
})
