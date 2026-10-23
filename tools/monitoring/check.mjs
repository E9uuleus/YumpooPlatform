import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import { prometheusConfig } from './configure.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
const directory=path.join(root,'deployment/monitoring')
const read=name=>YAML.parse(fs.readFileSync(path.join(directory,name),'utf8'), { merge: true })
const compose=read('compose.yml')
let memory=0
for(const [name,service] of Object.entries(compose.services)) {
  assert.equal(service.network_mode,'host',`${name}: Linux loopback network required`)
  assert.equal(service.restart,'unless-stopped')
  assert.equal(service.read_only,true)
  assert.deepEqual(service.cap_drop,['ALL'])
  assert.ok(!service.ports)
  assert.match(service.mem_limit,/^\d+m$/u)
  memory+=parseInt(service.mem_limit)
  if(name!=='hub')assert.match(service.image,/:v?\d+\.\d+\.\d+@sha256:[a-f0-9]{64}$/u)
  if(service.command && name!=='node-exporter') assert.ok(service.command.some(s=>s.includes('127.0.0.1:')))
}
assert.ok(memory<=1536,'Total memory limit must fit the monitoring budget')
const prometheus=prometheusConfig('https://example.com')
assert.equal(prometheus.global.scrape_interval,'30s')
assert.ok(prometheus.scrape_configs.find(s=>s.job_name==='external').static_configs.length>=6)
for(const module of Object.values(read('blackbox.yml').modules))assert.equal(module.http.fail_if_not_ssl,true)
const rules=read('rules.yml')
assert.ok(rules.groups.flatMap(g=>g.rules).some(r=>r.alert==='ApplicationSamplesStale'))
assert.ok(rules.groups.flatMap(g=>g.rules).every(r=>r.keep_firing_for==='60s'))
const dashboard=JSON.parse(fs.readFileSync(path.join(directory,'grafana/dashboards/yumpoo.json'),'utf8'))
assert.ok(dashboard.panels.every(p=>p.fieldConfig.defaults.custom.spanNulls===false),'Missing samples must not be interpolated')
if(process.argv.includes('--rules')) {
  const image=compose.services.prometheus.image
  const result=spawnSync('docker',['run','--rm','-v',`${directory}:/work:ro`,'--entrypoint','promtool',image,'test','rules','/work/rules.test.yml'],{stdio:'inherit'})
  assert.equal(result.status,0,'Prometheus rule tests failed')
}
console.log(`Monitoring configuration verified; ${memory} MiB total container limits.`)
