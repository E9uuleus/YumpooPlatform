import { describe, expect, it } from 'vitest'
import { OperationsMetricSeriesFromJSON, OperationsLogTailFromJSON } from '../src/generated/index'

describe('operations wire contract',()=>{
  it('converts all chart timestamps to Date and preserves null gaps',()=>{
    const result=OperationsMetricSeriesFromJSON({range:'1h',resolutionSeconds:15,timestamps:['2026-09-26T12:00:00Z'],restarts:['2026-09-26T12:00:00Z'],series:[{key:'cpu.system',unit:'RATIO',values:[null]}]})
    expect(result.timestamps[0]).toBeInstanceOf(Date);expect(result.restarts[0]).toBeInstanceOf(Date);expect(result.series[0]?.values).toEqual([null])
  })
  it('retains sequence watermarks above Number safe range as strings',()=>{
    const seq='9007199254740999'
    const result=OperationsLogTailFromJSON({bootId:'boot',items:[],nextAfterSeq:seq,latestSeq:seq,hasMore:false,gap:false,gapReason:null,droppedCount:0})
    expect(result.nextAfterSeq).toBe(seq);expect(result.latestSeq).toBe(seq)
  })
})

