import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { Store } from '../../packages/monitor-hub/src/store.ts'
import { Hub } from '../../packages/monitor-hub/src/hub.ts'
import { createServers } from '../../packages/monitor-hub/src/server.ts'
import type { Config } from '../../packages/monitor-hub/src/model.ts'

// This drill owns only its new container and child JVM. It never contacts a deployed host.
const output = path.resolve('out/monitoring/drill')
fs.mkdirSync(output, {recursive:true})
const name = `yumpoo-monitor-drill-${randomUUID().slice(0,8)}`
const jar = path.resolve('backend/target/yumpoo-server.jar')
assert.ok(fs.existsSync(jar),'Build the backend package before running the drill')
const store = new Store(':memory:')
const config = {services:[{id:'server',name:'隔离演练服务',checks:['health']}],channels:{},uiRoot:path.resolve('frontend/monitor-dashboard/dist')} as Config
const hub = new Hub(store,config)
const {ui,ingress} = await createServers(hub,()=>false)
const address = await ui.listen({host:'127.0.0.1',port:0})
const docker = (args:string[]) => execFileSync('docker',args,{encoding:'utf8',windowsHide:true}).trim()
const sleep = (ms:number) => new Promise(resolve=>setTimeout(resolve,ms))
let app:ReturnType<typeof spawn>|undefined; let container=false
const report:{started:string;checks:string[];completed?:string} = {started:new Date().toISOString(),checks:[]}
async function waitFor(check:()=>Promise<boolean>, seconds=100) {
  const deadline=Date.now()+seconds*1000
  while(Date.now()<deadline){if(await check())return;await sleep(1000)}
  throw new Error('Isolated service did not reach the expected state')
}
async function healthy() {
  try {const response=await fetch('http://127.0.0.1:48100/actuator/health',{signal:AbortSignal.timeout(3000)});return response.ok && (await response.json()).status==='UP'} catch{return false}
}
try {
  const net = await import('node:net')
  const listener=net.createServer();await new Promise<void>((resolve,reject)=>{listener.once('error',reject);listener.listen(48100,'127.0.0.1',resolve)});await new Promise<void>(resolve=>listener.close(()=>resolve()))
  docker(['run','-d','--rm','--name',name,'-e','POSTGRES_DB=yumpoo','-e','POSTGRES_USER=drill','-e','POSTGRES_PASSWORD=isolated-drill-only','-p','127.0.0.1::5432','postgres:17.10-alpine']);container=true
  const port=JSON.parse(docker(['inspect',name]))[0].NetworkSettings.Ports['5432/tcp'][0].HostPort
  await waitFor(async()=>{try{docker(['exec',name,'pg_isready','-U','drill','-d','yumpoo']);return true}catch{return false}},60)
  const log=fs.openSync(path.join(output,'application.log'),'w')
  app=spawn('java',['-Xmx384m','-jar',jar],{cwd:output,windowsHide:true,stdio:['ignore',log,log],env:{...process.env,
    SPRING_PROFILES_ACTIVE:'local,m1-13-e2e',SPRING_DATASOURCE_URL:`jdbc:postgresql://127.0.0.1:${port}/yumpoo`,SPRING_DATASOURCE_USERNAME:'drill',SPRING_DATASOURCE_PASSWORD:'isolated-drill-only',
    YUMPOO_SERVER_PORT:'48100',YUMPOO_CONTROLLED_AUTH_ENABLED:'true',YUMPOO_CONTROLLED_AUTH_CORP_ID:'corp-monitor-drill',YUMPOO_CONTROLLED_AUTH_MEMBER_ID:'member-monitor-drill',
    YUMPOO_SESSION_CURRENT_KEY_VERSION:'drill',YUMPOO_SESSION_CURRENT_KEY:Buffer.alloc(32,1).toString('base64'),YUMPOO_OUTBOX_ENABLED:'false'}})
  fs.closeSync(log)
  await waitFor(healthy)
  report.checks.push('Real isolated application and PostgreSQL started healthy')
  docker(['stop',name]);container=false
  await waitFor(async()=>!await healthy(),20)
  for(let sample=0;sample<4;sample++) {
    assert.equal(await healthy(),false)
    hub.observe({service:'server',state:'down',at:Date.now()})
    assert.equal((await fetch(`${address}/api/v1/status`)).status,200)
    if(sample<3)await sleep(30050)
  }
  assert.equal(store.all("SELECT * FROM deliveries WHERE channel='operations'").length,1)
  const metrics=await fetch('http://127.0.0.1:48100/actuator/prometheus',{signal:AbortSignal.timeout(3000)})
  assert.equal(metrics.status,200);assert.match(await metrics.text(),/yumpoo_ops_sample_timestamp_seconds/u)
  report.checks.push('Database stopped: independent UI stayed available, 90-second alert queued, in-memory metrics stayed readable')
  app.kill();await new Promise<void>(resolve=>{if(app!.exitCode!==null)resolve();else app!.once('exit',()=>resolve())})
  assert.equal(await healthy(),false)
  assert.equal((await fetch(`${address}/`)).status,200)
  assert.equal((await fetch(`${address}/api/v1/status`)).status,200)
  report.checks.push('Application stopped: independent status page and API stayed available')
  report.completed=new Date().toISOString()
  fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2)+'\n')
  console.log(report.checks.join('\n'))
} finally {
  if(app && app.exitCode===null)app.kill()
  if(container)docker(['rm','-f',name])
  await ui.close();await ingress.close();store.close()
}
