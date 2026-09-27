import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OperationsLogPage } from '@yumpoo/api-client'
import OperationsLogsView from './OperationsLogsView.vue'

const api=vi.hoisted(()=>({queryOperationsLogs:vi.fn(),getOperationsLogHistogram:vi.fn(),tailOperationsLogs:vi.fn()}))
vi.mock('../../api/client',()=>({operationsApi:api}))
vi.mock('../../composables/useSession',()=>({ensureAuthentication:vi.fn()}))
vi.mock('../../components/operations/OperationsChart.vue',()=>({default:{template:'<div />'}}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{from:'2026-09-26T11:00:00Z',to:'2026-09-26T12:00:00Z'}}),useRouter:()=>({replace:vi.fn().mockResolvedValue(undefined)})}))
function page(message:string,cursor:string|null):OperationsLogPage {
  return { items:[{id:message+':1',seq:null,record:{time:new Date('2026-09-26T11:59:00Z'),level:'INFO',module:'test',event:null,msg:message,logger:'test',thread:'test',requestId:null,correlationId:null,userId:null,fields:{},error:null}}],source:'FILE',nextCursor:cursor,partial:false,partialReason:null,scannedBytes:0,skippedLines:0,droppedCount:0 }
}
afterEach(()=>{vi.restoreAllMocks();Object.values(api).forEach(mock=>mock.mockReset())})
describe('历史日志筛选',()=>{
  it('级别计数覆盖当前区间的所有级别，精确筛选收在折叠区',async()=>{
    api.queryOperationsLogs.mockResolvedValue(page('history',null))
    api.getOperationsLogHistogram.mockResolvedValue({buckets:[{time:new Date(),counts:{INFO:4,ERROR:2,DEBUG:9}}],bucketSeconds:60,partial:false,partialReason:null})
    const wrapper=mount(OperationsLogsView)
    await flushPromises()
    expect(wrapper.get('[aria-label="日志级别"]').text()).toContain('DEBUG 9')
    expect(api.getOperationsLogHistogram.mock.lastCall?.[0].levels).toBe('TRACE,DEBUG,INFO,WARN,ERROR')
    expect(wrapper.get('.log-more-filters').attributes('open')).toBeUndefined()
    expect(wrapper.get('.log-more-filters input[aria-label="事件代码"]').exists()).toBe(true)
    await wrapper.findAll('[aria-label="日志级别"] button').find(button=>button.text().startsWith('DEBUG'))!.trigger('click')
    await flushPromises()
    expect(api.queryOperationsLogs.mock.lastCall?.[0].levels).toContain('DEBUG')
    wrapper.unmount()
  })
  it('编辑中的筛选不会污染分页，重新查询后丢弃被中止的旧页',async()=>{
    vi.spyOn(document,'visibilityState','get').mockReturnValue('visible')
    api.getOperationsLogHistogram.mockResolvedValue({buckets:[],bucketSeconds:60,partial:false,partialReason:null})
    let oldPage!:(value:OperationsLogPage)=>void
    api.queryOperationsLogs.mockResolvedValueOnce(page('first','cursor-1')).mockImplementationOnce(()=>new Promise(resolve=>{oldPage=resolve})).mockResolvedValueOnce(page('new-filter',null))
    const wrapper=mount(OperationsLogsView)
    await flushPromises()
    await wrapper.get('input[aria-label="搜索日志"]').setValue('new')
    await wrapper.findAll('button').find(b=>b.text()==='加载更早')!.trigger('click');await flushPromises()
    expect(api.queryOperationsLogs.mock.calls[1]?.[0]).toMatchObject({q:'',cursor:'cursor-1'})
    const oldSignal=api.queryOperationsLogs.mock.calls[1]?.[1].signal as AbortSignal
    await wrapper.findAll('button').find(b=>b.text()==='查询')!.trigger('click');await flushPromises()
    expect(oldSignal.aborted).toBe(true)
    oldPage(page('stale-page',null));await flushPromises()
    expect(wrapper.text()).toContain('new-filter');expect(wrapper.text()).not.toContain('stale-page')
    expect(api.queryOperationsLogs.mock.calls[2]?.[0]).toMatchObject({q:'new'})
    wrapper.unmount()
  })
})
