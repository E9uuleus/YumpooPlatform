import assert from 'node:assert/strict'
import test from 'node:test'
import { EventEmitter } from 'node:events'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { DEFAULT_MONITOR_ENVIRONMENT, workflowEvent, eventHeaders, notifyMonitor } from './monitor-notify.mjs'
import { notifyWeCom, truncateUtf8 } from './notify.mjs'

test('source identity is deterministic and cannot impersonate host outcomes',()=>{
  const env={GITHUB_RUN_ID:'123',GITHUB_RUN_ATTEMPT:'2',GITHUB_JOB:'draft',GITHUB_REPOSITORY:'owner/repo'}
  const first=workflowEvent(env,'草稿就绪')
  assert.equal(first.type,'workflow.notice')
  assert.equal(first.id,workflowEvent(env,'草稿就绪').id)
  assert.notEqual(first.id,workflowEvent(env,'草稿失败').id)
  assert.equal(workflowEvent(env,'草稿失败').type,'workflow.failed')
  assert.equal(first.source,'github')
  assert.equal(eventHeaders('a'.repeat(32),'{"test":true}','1800000000000','b'.repeat(32))['x-monitor-signature'],'5325b63ea4e6c5e59d8cd9f8e6071e3ed5fb12abe508973201cb3cc70a85e138')
})
test('WeCom requires errcode zero and UTF-8 limit preserves characters',async()=>{
  const env={WECOM_RELEASE_WEBHOOK:'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test'}
  assert.equal(await notifyWeCom(env,'message',async()=>({ok:true,json:async()=>({errcode:45009})})),false)
  assert.equal(await notifyWeCom(env,'message',async()=>({ok:true,json:async()=>({})})),false)
  assert.ok(Buffer.byteLength(truncateUtf8('中文😀'.repeat(2000)))<=4096)
  assert.ok(!truncateUtf8('中文😀'.repeat(2000)).includes('\uFFFD'))
})
test('enabled hub never falls back to a second direct channel on invalid credentials',async()=>{
  let sent=false
  assert.equal(await notifyWeCom({MONITOR_ENABLED:'true',WECOM_RELEASE_WEBHOOK:'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test'},'message',async()=>{sent=true;return {ok:true}}),false)
  assert.equal(sent,false)
})
test('a restricted tunnel persists one idempotent event and is cleaned up',async()=>{
  let killed=false; let received
  const child=new EventEmitter(); child.exitCode=null; child.kill=()=>{killed=true;child.exitCode=0;child.emit('exit',0)}
  const env={MONITOR_SSH_HOST:'example.com',MONITOR_SSH_KEY:'PRIVATE KEY test fixture',MONITOR_KNOWN_HOSTS:'example.com ssh-ed25519 fixture',MONITOR_SOURCE_SECRET:'a'.repeat(64)}
  const result=await notifyMonitor(env,'test',{openTunnel:async args=>{assert.ok(args.includes('yumpoo-monitor-github@example.com'));assert.ok(args.includes('-N'));return child},send:async(url,options)=>{
    if(url.endsWith('/healthz'))return {ok:true}
    received=JSON.parse(options.body)
    return {ok:true,json:async()=>({accepted:true})}
  }})
  assert.equal(result,true);assert.equal(killed,true);assert.equal(received.source,'github')
})

function fixture(t, environment) {
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'monitor-notify-test-'))
  t.after(()=>fs.rmSync(directory,{recursive:true,force:true}))
  const summary=path.join(directory,'summary.md')
  fs.writeFileSync(summary,'')
  const child=new EventEmitter(); child.exitCode=null; child.kill=()=>{child.exitCode=0;child.emit('exit',0)}
  const env={MONITOR_SSH_HOST:'example.com',MONITOR_SSH_KEY:'PRIVATE KEY test fixture',MONITOR_KNOWN_HOSTS:'example.com ssh-ed25519 fixture',MONITOR_SOURCE_SECRET:'a'.repeat(64),MONITOR_ENVIRONMENT:environment,GITHUB_STEP_SUMMARY:summary}
  return {env,openTunnel:async()=>child,summary:()=>fs.readFileSync(summary,'utf8')}
}

test('unset or blank environment uses the shared default with one summary notice',async t=>{
  for(const environment of [undefined,'   ']) {
    const f=fixture(t,environment)
    let received
    assert.equal(await notifyMonitor(f.env,'notice',{openTunnel:f.openTunnel,send:async(url,options)=>{
      if(url.endsWith('/healthz'))return {ok:true}
      received=JSON.parse(options.body)
      return {ok:true,status:200,json:async()=>({accepted:true})}
    }}),true)
    assert.equal(received.environment,DEFAULT_MONITOR_ENVIRONMENT)
    assert.equal(received.environment,'wecom-dev')
    assert.equal(f.summary().match(/MONITOR_ENVIRONMENT/gu).length,1)
  }
  assert.equal(workflowEvent({MONITOR_ENVIRONMENT:' staging '},'notice').environment,'staging')
})

test('HTTP 400 rejects immediately, even without a JSON response',async t=>{
  const f=fixture(t,'staging')
  let posts=0
  assert.equal(await notifyMonitor(f.env,'notice',{openTunnel:f.openTunnel,send:async url=>{
    if(url.endsWith('/healthz'))return {ok:true}
    posts++
    return {ok:false,status:400,json:async()=>{throw new Error('not JSON')}}
  }}),false)
  assert.equal(posts,1)
  assert.match(f.summary(),/\*\*rejected\*\* \(HTTP 400\)/u)
  assert.doesNotMatch(f.summary(),/not accepted|default/u)
})

test('transient server, rate-limit and network failures retry the same event',async t=>{
  for(const failure of [503,429,'network']) {
    const f=fixture(t,'staging')
    const bodies=[]
    assert.equal(await notifyMonitor(f.env,'notice',{openTunnel:f.openTunnel,send:async(url,options)=>{
      if(url.endsWith('/healthz'))return {ok:true}
      bodies.push(options.body)
      if(bodies.length===1) {
        if(failure==='network')throw new Error('connection reset')
        return {ok:false,status:failure,json:async()=>({})}
      }
      return {ok:true,status:200,json:async()=>({accepted:true})}
    }}),true)
    assert.equal(bodies.length,2)
    assert.equal(bodies[0],bodies[1])
    assert.equal(f.summary(),'')
  }
})
