import { describe, expect, it } from 'vitest'
import type { OperationsLogEntry } from '@yumpoo/api-client'
import { mergeLogEntries } from './logEntries'

function entry(seq: string, milliseconds = 0): OperationsLogEntry {
  return { id: `boot:${seq}`, seq, record: { time: new Date(milliseconds), level: 'INFO', module: 'test', event: null, msg: 'safe', logger: 'test', thread: 'test', requestId: null, correlationId: null, userId: null, fields: {}, error: null } }
}
describe('实时日志合并', () => {
  it('同一毫秒按 64 位序列排序，重复响应只保留一条', () => {
    const result = mergeLogEntries([entry('9007199254740992'), entry('9')], [entry('9007199254740993'), entry('10'), entry('9')])
    expect(result.map(item => item.seq)).toEqual(['9007199254740993', '9007199254740992', '10', '9'])
  })
  it('只保留最新 500 条，文件行号也按数值排序', () => {
    const result = mergeLogEntries([], Array.from({ length: 600 }, (_, i) => entry(String(i), i)))
    expect(result).toHaveLength(500); expect(result.at(-1)?.seq).toBe('100')
    expect(mergeLogEntries([{ ...entry('9'), seq: null }], [{ ...entry('10'), seq: null }])[0]?.id).toBe('boot:10')
  })
})
