import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import net from 'node:net'
import { spawn } from 'node:child_process'
import { createHash, createHmac, randomUUID } from 'node:crypto'

export const DEFAULT_MONITOR_ENVIRONMENT = 'wecom-dev'

export function workflowEvent(env, content, now = new Date()) {
  const hash = createHash('sha256').update(content).digest('hex').slice(0,16)
  const id = `github-${env.GITHUB_RUN_ID || 'local'}-${env.GITHUB_RUN_ATTEMPT || '1'}-${env.GITHUB_JOB || 'notify'}-${hash}`.replace(/[^a-zA-Z0-9._:-]/gu,'-').slice(0,160)
  const workflowUrl = `https://github.com/${env.GITHUB_REPOSITORY}/actions/runs/${env.GITHUB_RUN_ID}`
  const payload = { target: /桌面|desktop/iu.test(content) ? 'desktop':'server', summary: content.slice(0,2000) }
  if (/^https:\/\/github\.com\/[\w.-]+\/[\w.-]+\/actions\/runs\/\d+$/u.test(workflowUrl)) payload.workflowUrl=workflowUrl
  return { schemaVersion:1,id,source:'github',environment:env.MONITOR_ENVIRONMENT?.trim() || DEFAULT_MONITOR_ENVIRONMENT,type:/失败|异常|failed|failure/iu.test(content) ? 'workflow.failed':'workflow.notice',occurredAt:now.toISOString(),correlationId:id,payload }
}
export function eventHeaders(secret, body, timestamp=String(Date.now()), nonce=randomUUID()) {
  return { 'content-type':'application/json','x-monitor-source':'github','x-monitor-time':timestamp,'x-monitor-nonce':nonce,'x-monitor-signature':createHmac('sha256',secret).update(`${timestamp}\n${nonce}\n${body}`).digest('hex') }
}
async function unusedPort() {
  const server=net.createServer()
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve)})
  const port=server.address().port
  await new Promise((resolve,reject)=>server.close(e=>e?reject(e):resolve()))
  return port
}
const sleep = ms => new Promise(resolve=>setTimeout(resolve,ms))
export async function notifyMonitor(env, content, {send=fetch, openTunnel}={}) {
  const event=workflowEvent(env,content)
  let directory; let child
  try {
    if(!env.MONITOR_ENVIRONMENT?.trim() && env.GITHUB_STEP_SUMMARY)fs.appendFileSync(env.GITHUB_STEP_SUMMARY,`\nMonitoring environment variable \`MONITOR_ENVIRONMENT\` is not set; the default \`${DEFAULT_MONITOR_ENVIRONMENT}\` was used. Events are rejected when it differs from the monitor configuration.\n`)
    if (!/^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$/u.test(env.MONITOR_SSH_HOST ?? '') || !env.MONITOR_SSH_KEY?.includes('PRIVATE KEY') || !env.MONITOR_KNOWN_HOSTS?.trim() || (env.MONITOR_SOURCE_SECRET?.length ?? 0)<32) throw new Error('Missing monitor credentials')
    directory=fs.mkdtempSync(path.join(os.tmpdir(),'yumpoo-monitor-'))
    const key=path.join(directory,'key'); const hosts=path.join(directory,'known_hosts')
    fs.writeFileSync(key,`${env.MONITOR_SSH_KEY.trim()}\n`,{mode:0o600,flag:'wx'})
    fs.writeFileSync(hosts,`${env.MONITOR_KNOWN_HOSTS.trim()}\n`,{mode:0o600,flag:'wx'})
    const port=await unusedPort()
    const nullFile=process.platform==='win32'?'NUL':'/dev/null'
    const args=['-N','-T','-F',nullFile,'-i',key,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','IdentityAgent=none','-o','StrictHostKeyChecking=yes','-o',`UserKnownHostsFile=${hosts}`,'-o',`GlobalKnownHostsFile=${nullFile}`,'-o','ConnectTimeout=10','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','-o','ExitOnForwardFailure=yes','-L',`127.0.0.1:${port}:127.0.0.1:8788`,`yumpoo-monitor-github@${env.MONITOR_SSH_HOST}`]
    child=openTunnel ? await openTunnel(args) : spawn('ssh',args,{stdio:'ignore',windowsHide:true,env:{PATH:process.env.PATH,SystemRoot:process.env.SystemRoot}})
    let failed=false
    child.on('error',()=>{failed=true})
    const url=`http://127.0.0.1:${port}`
    let ready=false
    for(let n=0;n<20;n++) {
      if(failed || child.exitCode!==null) break
      try {ready=(await send(`${url}/healthz`,{signal:AbortSignal.timeout(1000)})).ok} catch { }
      if(ready)break
      await sleep(500)
    }
    if(!ready)throw new Error('Monitor tunnel unavailable')
    const body=JSON.stringify(event)
    for(let attempt=0;attempt<3;attempt++) {
      try {
        const response=await send(`${url}/internal/v1/events`,{method:'POST',headers:eventHeaders(env.MONITOR_SOURCE_SECRET,body),body,signal:AbortSignal.timeout(5000)})
        if(response.status>=400 && response.status<500 && response.status!==429)throw Object.assign(new Error('Monitor rejected event'),{status:response.status})
        const result=await response.json()
        if(response.ok && (result.accepted || result.duplicate || result.obsolete)) return true
      } catch(error) {
        if(error.status>=400 && error.status<500 && error.status!==429)throw error
      }
      if(attempt<2)await sleep(1000*2**attempt)
    }
    throw new Error('Monitor did not acknowledge event')
  } catch(error) {
    const rejected=error.status>=400 && error.status<500 && error.status!==429
    console.warn(`Monitoring event ${rejected ? `rejected (HTTP ${error.status})` : 'not accepted'}: ${event.id}. Deployment behavior is unchanged.`)
    if(env.GITHUB_STEP_SUMMARY)fs.appendFileSync(env.GITHUB_STEP_SUMMARY,rejected
      ? `\nMonitoring event **rejected** (HTTP ${error.status}). Check that \`MONITOR_ENVIRONMENT\`, \`MONITOR_SOURCE_SECRET\` and the allowed event types match the monitor configuration. Event: \`${event.id}\`.\n`
      : `\nMonitoring notification **not accepted** after bounded retries. Event: \`${event.id}\`. No durable receipt is claimed; inspect the monitor connection and retry the notification.\n`)
    return false
  } finally {
    if(child) { child.kill(); await new Promise(resolve=>{if(child.exitCode!==null)return resolve();child.once('exit',resolve);setTimeout(resolve,2000).unref()}) }
    if(directory)fs.rmSync(directory,{recursive:true,force:true})
  }
}
