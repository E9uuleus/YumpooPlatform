import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOperationsQuery } from './useOperationsQuery'
import { ErrorCode, ResponseError } from '@yumpoo/api-client'
import { ensureAuthentication } from './useSession'
vi.mock('./useSession', () => ({ ensureAuthentication: vi.fn().mockResolvedValue(undefined) }))

beforeEach(() => { vi.useFakeTimers(); vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible') })
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })
describe('运维轮询', () => {
  it('后台与撤销权限时中止请求，旧响应不能恢复数据', async () => {
    const enabled=ref(true), pending: ((value:number)=>void)[]=[], signals:AbortSignal[]=[]
    const load=vi.fn((signal:AbortSignal)=>{signals.push(signal);return new Promise<number>(resolve=>pending.push(resolve))})
    let query!:ReturnType<typeof useOperationsQuery<number>>
    const wrapper=mount(defineComponent({setup(){query=useOperationsQuery(load,{interval:()=>3000,enabled:()=>enabled.value});return()=>null}}))
    pending[0]!(1);await flushPromises();expect(query.data.value).toBe(1)
    await vi.advanceTimersByTimeAsync(3000);expect(load).toHaveBeenCalledTimes(2)
    enabled.value=false;await flushPromises();expect(signals[1]!.aborted).toBe(true)
    pending[1]!(9);await flushPromises();expect(query.data.value).toBeUndefined()
    enabled.value=true;await flushPromises();expect(load).toHaveBeenCalledTimes(3)
    vi.spyOn(document,'visibilityState','get').mockReturnValue('hidden');document.dispatchEvent(new Event('visibilitychange'))
    expect(signals[2]!.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(60000);expect(load).toHaveBeenCalledTimes(3)
    wrapper.unmount();pending[2]!(99);await flushPromises();expect(query.data.value).toBeUndefined()
  })
  it('不重叠轮询，输入焦点期间保留编辑，卸载后停止定时器', async () => {
    const load=vi.fn().mockResolvedValue('ok')
    const wrapper=mount(defineComponent({setup(){useOperationsQuery(load,{interval:()=>3000});return()=>null}}))
    await flushPromises();const input=document.createElement('input');document.body.append(input);input.focus()
    await vi.advanceTimersByTimeAsync(9000);expect(load).toHaveBeenCalledOnce()
    input.blur();input.remove();await vi.advanceTimersByTimeAsync(3000);expect(load).toHaveBeenCalledTimes(2)
    wrapper.unmount();await vi.advanceTimersByTimeAsync(30000);expect(load).toHaveBeenCalledTimes(2)
  })
  it('403 清除旧运维数据、停止重试并重新验证角色', async () => {
    const load=vi.fn().mockResolvedValueOnce('sensitive').mockRejectedValue(new ResponseError(new Response(JSON.stringify({code:ErrorCode.AccessDenied,message:'无权限',requestId:'denied',retryable:false,fieldErrors:[],details:{}}),{status:403})))
    let query!:ReturnType<typeof useOperationsQuery<string>>
    const wrapper=mount(defineComponent({setup(){query=useOperationsQuery(load,{interval:()=>3000});return()=>null}}))
    await flushPromises();expect(query.data.value).toBe('sensitive')
    await vi.advanceTimersByTimeAsync(3000);await flushPromises()
    expect(query.data.value).toBeUndefined();expect(ensureAuthentication).toHaveBeenCalledWith(true)
    await vi.advanceTimersByTimeAsync(60000);expect(load).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('切换窗口不重新加载规则并覆盖未保存的编辑，也不启动手动查询', async () => {
    const load=vi.fn().mockResolvedValue({threshold:85}), manual=vi.fn()
    let query!:ReturnType<typeof useOperationsQuery<{threshold:number}>>
    const wrapper=mount(defineComponent({setup(){query=useOperationsQuery(load);useOperationsQuery(manual,{immediate:false});return()=>null}}))
    await flushPromises();query.data.value!.threshold=86
    vi.spyOn(document,'visibilityState','get').mockReturnValue('hidden');document.dispatchEvent(new Event('visibilitychange'))
    vi.spyOn(document,'visibilityState','get').mockReturnValue('visible');document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises();expect(load).toHaveBeenCalledOnce();expect(manual).not.toHaveBeenCalled();expect(query.data.value!.threshold).toBe(86)
    wrapper.unmount()
  })
})

