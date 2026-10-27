import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { scryptSync } from 'node:crypto'
import { Store } from '../src/store.ts'
import { Hub } from '../src/hub.ts'
import { DeliveryWorker, boundedText, enqueue } from '../src/delivery.ts'
import { createServers } from '../src/server.ts'
import { formatBeijing, maintenanceAnnouncement, maintenanceChange } from '../src/messages.ts'
import { renderMetrics } from '../src/metrics.ts'
import { sign } from '../src/security.ts'
import type { Config, ReleaseEvent } from '../src/model.ts'
const base = 1800000000000
function config(): Config {
  return { environment: 'test', database: ':memory:', siteUrl: 'https://example.com', prometheusUrl: 'http://127.0.0.1:9090', uiRoot: '', admin: { username: 'admin', salt: 'test-salt-long-enough', passwordHash: scryptSync('test-password', 'test-salt-long-enough', 64).toString('hex') }, sources: { windows: { secret: 'a'.repeat(64), types: ['maintenance.planned','maintenance.started','deployment.completed'] }, github: { secret: 'b'.repeat(64), types: ['workflow.failed'] } }, alertToken: 'c'.repeat(64), channels: { operations: 'https://example.com/test' }, services: [{ id: 'server', name: 'Yumpoo', checks: ['health'] }] }
}
function event(type: ReleaseEvent['type'], offset = 0): ReleaseEvent { return { schemaVersion: 1, id: `event-${type}`, source: 'windows', environment: 'test', type, occurredAt: new Date(base + offset).toISOString(), correlationId: 'request-1', payload: { target: 'server', tag: 'server-v2026.10.23.1', commit: 'a'.repeat(40), outcome: 'succeeded' } } }
function setup() { const store = new Store(':memory:'); return { store, hub: new Hub(store, config()) } }
test('sustained fault, public delay, unknown sampling and continuous recovery', () => {
  const { hub, store } = setup()
  const sample = (state: 'down'|'healthy'|'unknown', seconds: number) => hub.observe({ service: 'server', state, at: base + seconds*1000 }, base + seconds*1000)
  sample('down', 0); sample('healthy', 30); sample('down', 60); sample('down', 149)
  assert.equal(store.all('SELECT * FROM deliveries').length, 0)
  for(let second=150;second<=360;second+=30) sample('down',second)
  assert.equal(store.all('SELECT * FROM deliveries').length, 2)
  sample('healthy', 390); sample('unknown', 420); sample('healthy', 450); sample('healthy', 509)
  assert.equal(store.all('SELECT * FROM deliveries').length, 2)
  sample('healthy', 510)
  assert.equal(store.all("SELECT * FROM deliveries WHERE state='superseded'").length, 2)
  assert.equal(store.all("SELECT * FROM deliveries WHERE state='pending'").length, 2)
  assert.equal(hub.status(base + 700000).services[0]?.observed, 'unknown')
  store.close()
})
test('events are durable and idempotent; terminal event supersedes late maintenance', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'monitor-test-')); const file = join(dir, 'hub.sqlite')
  try {
    let store = new Store(file); let hub = new Hub(store, config())
    hub.accept(event('maintenance.planned'), base)
    store.close(); store = new Store(file); hub = new Hub(store, config())
    assert.deepEqual(hub.accept(event('maintenance.planned'), base), { duplicate: true })
    hub.accept(event('deployment.completed'), base + 1000)
    assert.deepEqual(hub.accept(event('maintenance.started'), base + 2000), { obsolete: true })
    assert.equal(store.all('SELECT * FROM maintenance').length, 0)
    assert.equal(store.get<{state:string}>("SELECT state FROM deliveries WHERE dedupe='request-1:planned'")?.state, 'superseded')
    await hub.verifyDeployments(async () => ({ healthy: true, tag: 'server-v2026.10.23.1', commit: 'b'.repeat(40) }), base + 3000)
    assert.equal(store.all("SELECT * FROM deliveries WHERE dedupe LIKE '%verified%'").length, 0)
    await hub.verifyDeployments(async () => ({ healthy: true, tag: 'server-v2026.10.23.1', commit: 'a'.repeat(40) }), base + 4000)
    assert.equal(store.all("SELECT * FROM deliveries WHERE dedupe LIKE '%verified%'").length, 2)
    store.close()
  } finally { rmSync(dir, { recursive: true, force: true }) }
})
test('maintenance suppresses only expected interval and expires without recovery claim', () => {
  const { hub, store } = setup()
  hub.accept(event('maintenance.started'), base)
  hub.observe({ service:'server',state:'down',at:base }, base)
  for(let second=30;second<1800;second+=30) hub.observe({ service:'server',state:'down',at:base+second*1000 }, base+second*1000)
  assert.equal(store.all("SELECT * FROM deliveries WHERE dedupe LIKE '%:ops'").length, 0)
  hub.expireMaintenance(base + 1800000)
  hub.observe({ service:'server',state:'down',at:base+1800000 }, base+1800000)
  assert.equal(hub.status(base+1800000).services[0]?.state, 'down')
  assert.equal(store.all("SELECT * FROM deliveries WHERE dedupe LIKE '%:ops'").length, 1)
  store.close()
})
test('WeCom nonzero errcode retries, expiry, rate budget and byte bounds', async () => {
  const { store } = setup()
  const long = boundedText('中😀'.repeat(2000))
  assert.ok(Buffer.byteLength(long) <= 4096); assert.ok(!long.includes('\uFFFD'))
  enqueue(store, 'one', 'operations', 'test', base)
  let calls = 0
  const worker = new DeliveryWorker(store, config().channels, (async () => { calls++; return new Response(JSON.stringify({errcode: calls === 1 ? 45009 : 0}), {status:200}) }) as typeof fetch)
  await worker.tick(base)
  assert.equal(store.get<{state:string}>('SELECT state FROM deliveries')?.state, 'pending')
  await worker.tick(base+5000)
  assert.equal(store.get<{state:string}>('SELECT state FROM deliveries')?.state, 'sent')
  for (let i=0;i<20;i++) enqueue(store, `rate-${i}`, 'operations', 'test', base)
  for (let i=0;i<20;i++) await worker.tick(base+6000)
  assert.equal(calls, 15)
  enqueue(store, 'expired', 'announcements', 'old', base, null, base+1000)
  await worker.tick(base+6000)
  assert.equal(store.get<{state:string}>("SELECT state FROM deliveries WHERE dedupe='expired'")?.state, 'expired')
  store.close()
})
test('stale Alertmanager resolved cannot claim recovery; fresh recovery stops retries', () => {
  const { hub, store } = setup()
  const alert = { fingerprint: 'a'.repeat(16), status: 'firing' as const, labels: { alertname:'DatabaseUnavailable', severity:'critical', source_job:'application' }, annotations: {summary:'数据库不可用'}, startsAt:new Date(base).toISOString() }
  hub.receiveAlerts([alert], () => true, base)
  hub.receiveAlerts([{...alert,status:'resolved'}], () => false, base+120000)
  assert.equal(store.get<{state:string}>('SELECT state FROM alerts')?.state, 'pending_resolved')
  hub.reconcileAlerts(() => true, base+180000)
  assert.equal(store.get<{state:string}>('SELECT state FROM alerts')?.state, 'resolved')
  assert.equal(store.get<{state:string}>("SELECT state FROM deliveries WHERE incident=?", alert.fingerprint)?.state, 'superseded')
  store.close()
})
test('machine and admin listeners separate privileges; signed ingress rejects replay and CSRF', async () => {
  const { hub, store } = setup(); const { ui, ingress } = await createServers(hub, () => true)
  try {
    assert.deepEqual((await ingress.inject({url:'/healthz'})).json(),{status:'UP',monitoring:'UNKNOWN'})
    assert.equal((await ingress.inject({url:'/api/v1/admin/overview'})).statusCode,404)
    assert.equal((await ui.inject({url:'/api/v1/admin/overview'})).statusCode,401)
    const body = JSON.stringify({...event('maintenance.planned'),occurredAt:new Date().toISOString()})
    const timestamp = String(Date.now()); const nonce = 'd'.repeat(32)
    const headers = {'content-type':'application/json','x-monitor-source':'windows','x-monitor-time':timestamp,'x-monitor-nonce':nonce,'x-monitor-signature':sign('a'.repeat(64),timestamp,nonce,body)}
    assert.equal((await ingress.inject({method:'POST',url:'/internal/v1/events',payload:body,headers})).statusCode,200)
    assert.equal((await ingress.inject({method:'POST',url:'/internal/v1/events',payload:body,headers})).statusCode,400)
    const login = await ui.inject({method:'POST',url:'/api/v1/login',payload:{username:'admin',password:'test-password'}})
    const cookie = String(login.headers['set-cookie']).split(';')[0]!
    const now = Date.now()
    const payload = {title:'测试*维护*',service:'server',starts:now,ends:now+600000}
    assert.equal((await ui.inject({method:'POST',url:'/api/v1/admin/preview',headers:{cookie},payload})).statusCode,403)
    const adminHeaders = {cookie,'x-csrf-token':login.json().csrf}
    const preview = await ui.inject({method:'POST',url:'/api/v1/admin/preview',headers:adminHeaders,payload})
    assert.equal(preview.statusCode,200)
    for (const invalid of [{...payload,ends:now-1},{...payload,starts:'invalid'},{...payload,title:''},{...payload,service:'missing'},{...payload,ends:now+86400001}]) {
      const previewError = await ui.inject({method:'POST',url:'/api/v1/admin/preview',headers:adminHeaders,payload:invalid})
      const createError = await ui.inject({method:'POST',url:'/api/v1/admin/maintenance',headers:adminHeaders,payload:invalid})
      assert.equal(previewError.statusCode,400)
      assert.equal(createError.statusCode,400)
      assert.deepEqual(previewError.json(),createError.json())
    }
    const maintenance = await ui.inject({method:'POST',url:'/api/v1/admin/maintenance',headers:adminHeaders,payload})
    assert.equal(maintenance.statusCode,200)
    const content = store.get<{content:string}>('SELECT content FROM deliveries WHERE dedupe=?',`${maintenance.json().id}:created`)!.content
    assert.equal(content,preview.json().text)
    assert.match(content,/服务：Yumpoo/u)
    assert.match(content,/（北京时间）/u)
    assert.doesNotMatch(content,/T\d{2}:\d{2}:\d{2}/u)
    const url = `/api/v1/admin/maintenance/${maintenance.json().id}`
    assert.equal((await ui.inject({method:'POST',url,headers:adminHeaders,payload:{action:'end'}})).statusCode,409)
    assert.equal((await ui.inject({method:'POST',url,headers:adminHeaders,payload:{action:'postpone',ends:now+900000}})).statusCode,200)
    assert.ok(store.all<{content:string}>("SELECT content FROM deliveries WHERE dedupe LIKE '%:postpone:%'").some(row=>row.content===maintenanceChange('postpone',payload.title,now+900000)))
    assert.equal((await ui.inject({method:'POST',url,headers:adminHeaders,payload:{action:'cancel'}})).statusCode,200)
    assert.ok(store.all<{content:string}>("SELECT content FROM deliveries WHERE dedupe LIKE '%:cancel:%'").some(row=>row.content==='维护安排「测试维护」已取消。'))
    assert.equal((await ui.inject({method:'POST',url,headers:adminHeaders,payload:{action:'postpone',ends:now+900000}})).statusCode,409)
    for(let index=0;index<9;index++) assert.equal((await ui.inject({method:'POST',url:'/api/v1/login',payload:{username:'admin',password:'test-password'}})).statusCode,200)
    assert.equal((await ui.inject({method:'POST',url:'/api/v1/login',payload:{username:'admin',password:'test-password'}})).statusCode,429)
  } finally { await ui.close(); await ingress.close(); store.close() }
})

test('maintenance messages use Beijing time and include the title in every change', () => {
  const starts = Date.UTC(2026,9,25,8,0)
  assert.equal(formatBeijing(starts),'2026-10-25 16:00')
  assert.equal(formatBeijing(Date.UTC(2026,9,25,16,0)),'2026-10-26 00:00')
  assert.equal(maintenanceAnnouncement('*升级*','协作平台',starts,starts+3600000),
    '计划维护：升级\n服务：协作平台\n开始：2026-10-25 16:00（北京时间）\n结束：2026-10-25 17:00（北京时间）')
  assert.equal(maintenanceChange('postpone','*升级*',starts+3600000),'维护安排「升级」已延期，预计结束：2026-10-25 17:00（北京时间）。')
  assert.equal(maintenanceChange('cancel','升级'),'维护安排「升级」已取消。')
  assert.equal(maintenanceChange('end','升级'),'维护安排「升级」已结束，服务恢复。')
})

test('metrics share status-page states and expose stable maintenance series at window boundaries', async () => {
  const { hub, store } = setup()
  hub.config.services.push({id:'desktop',name:'桌面端',checks:['download']})
  const now = Date.now()
  hub.observe({service:'server',state:'healthy',at:now},now)
  store.run("INSERT INTO maintenance VALUES(?,?,?,?,?,'active',0)",'manual','server','维护',now,now+600000)
  store.run("INSERT INTO maintenance VALUES(?,?,?,?,?,'active',1)",'future','server','未来维护',now+600000,now+900000)
  const { ui, ingress } = await createServers(hub,()=>true,()=>true)
  try {
    const response = await ingress.inject({url:'/metrics'})
    assert.equal(response.statusCode,200)
    const text = response.body
    assert.match(text,/^monitor_delivery_pending 0\nmonitor_delivery_dead 0\nmonitor_discovery_success 1\nmonitor_process_timestamp_seconds \d+(\.\d+)?\n/u)
    assert.match(text,/monitor_service_status\{service="server"\} 1\n/u)
    assert.match(text,/monitor_service_observed_status\{service="server"\} 0\n/u)
    assert.match(text,/monitor_maintenance_active\{service="server",kind="manual"\} 1\n/u)
    assert.match(text,/monitor_maintenance_active\{service="server",kind="automatic"\} 0\n/u)
    assert.match(text,/monitor_service_status\{service="desktop"\} 2\n/u)
    for (const kind of ['manual','automatic']) assert.ok(text.includes(`monitor_maintenance_active{service="desktop",kind="${kind}"} 0\n`))
    const later = renderMetrics(hub,true,now+600000)
    assert.ok(later.includes('monitor_maintenance_active{service="server",kind="manual"} 0\n'))
    assert.ok(later.includes('monitor_maintenance_active{service="server",kind="automatic"} 1\n'))
    assert.ok(later.includes('monitor_service_observed_status{service="server"} 2\n'))
  } finally { await ui.close(); await ingress.close(); store.close() }
})

test('expired dead deliveries no longer count as a notification backlog', async () => {
  const { hub, store } = setup()
  try {
    enqueue(store,'expired-failure','announcements','过期公告',base,null,base+1000)
    store.run("UPDATE deliveries SET state='dead'")
    assert.ok(renderMetrics(hub,false,base).includes('monitor_delivery_dead 1\n'))
    await new DeliveryWorker(store,{},(async()=>{throw new Error('must not send')}) as typeof fetch).tick(base+1000)
    assert.equal(store.get<{state:string}>('SELECT state FROM deliveries')?.state,'expired')
    assert.ok(renderMetrics(hub,false,base+1000).includes('monitor_delivery_dead 0\n'))
  } finally { store.close() }
})

test('admin can discard only dead deliveries, with CSRF protection and an audit record', async () => {
  const { hub, store } = setup(); const { ui, ingress } = await createServers(hub,()=>true)
  try {
    const now=Date.now()
    enqueue(store,'dead','operations','失败消息',now,null,now+60000)
    store.run("UPDATE deliveries SET state='dead' WHERE dedupe='dead'")
    enqueue(store,'pending','operations','待发送消息',now)
    const deadId=store.get<{id:number}>("SELECT id FROM deliveries WHERE dedupe='dead'")!.id
    const pendingId=store.get<{id:number}>("SELECT id FROM deliveries WHERE dedupe='pending'")!.id
    const login=await ui.inject({method:'POST',url:'/api/v1/login',payload:{username:'admin',password:'test-password'}})
    const cookie=String(login.headers['set-cookie']).split(';')[0]!
    const headers={cookie,'x-csrf-token':login.json().csrf}
    const url=`/api/v1/admin/deliveries/${deadId}/discard`
    assert.equal((await ui.inject({method:'POST',url,headers:{cookie}})).statusCode,403)
    assert.equal((await ui.inject({method:'POST',url,headers})).statusCode,200)
    assert.equal(store.get<{state:string}>('SELECT state FROM deliveries WHERE id=?',deadId)?.state,'abandoned')
    assert.equal((await ui.inject({method:'POST',url,headers})).statusCode,409)
    assert.equal((await ui.inject({method:'POST',url:`/api/v1/admin/deliveries/${pendingId}/discard`,headers})).statusCode,409)
    assert.equal(store.all("SELECT * FROM audit WHERE action='delivery.discard'").length,1)
    const overview=(await ui.inject({url:'/api/v1/admin/overview',headers})).json()
    assert.equal(overview.deliveries.find((delivery:{id:number})=>delivery.id===deadId).expires,now+60000)
  } finally { await ui.close(); await ingress.close(); store.close() }
})

test('restart gap cannot complete a continuous recovery and recovery_required ends suppression', async () => {
  const {hub,store} = setup()
  for(let second=0;second<=300;second+=30) hub.observe({service:'server',state:'down',at:base+second*1000},base+second*1000)
  hub.observe({service:'server',state:'healthy',at:base+330000},base+330000)
  hub.observe({service:'server',state:'healthy',at:base+500000},base+500000)
  assert.equal(store.all("SELECT * FROM deliveries WHERE dedupe LIKE '%recovered%'").length,0)
  hub.accept(event('maintenance.started',500000),base+500000)
  hub.accept({...event('deployment.completed',501000),payload:{...event('deployment.completed').payload,outcome:'recovery_required'}},base+501000)
  assert.equal(hub.maintained('server',base+501000),false)
  await hub.verifyDeployments(async()=>({healthy:true}),base+502000)
  assert.equal(store.all("SELECT * FROM deliveries WHERE dedupe LIKE '%verified%'").length,0)
  store.close()
})

test('shared temporary bot has one rate budget; transport failures become manually retryable', async () => {
  const {store} = setup()
  for(let index=0;index<20;index++) enqueue(store,`shared-${index}`,index%2?'operations':'announcements','test',base)
  let calls=0
  const worker = new DeliveryWorker(store,{operations:'https://example.com/bot',announcements:'https://example.com/bot'},(async()=>{calls++;return Response.json({errcode:0})}) as typeof fetch)
  for(let index=0;index<20;index++) await worker.tick(base)
  assert.equal(calls,15)
  store.run("UPDATE deliveries SET state='superseded' WHERE state='pending'")
  enqueue(store,'failure','operations','test',base+60000)
  worker.send=(async()=>{throw new Error('timeout')}) as typeof fetch
  for(let index=0;index<12;index++) await worker.tick(base+60000+index*3600001)
  assert.equal(store.get<{state:string}>("SELECT state FROM deliveries WHERE dedupe='failure'")?.state,'dead')
  store.close()
})
