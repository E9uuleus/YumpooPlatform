import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import { prometheusConfig } from './configure.mjs'
import { hostAlerts } from './expressions.mjs'
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
const alerts=rules.groups.flatMap(group=>group.rules).filter(rule=>rule.alert)
assert.ok(alerts.every(rule=>rule.keep_firing_for==='60s'))
for(const expected of hostAlerts) {
  const actual=alerts.find(rule=>rule.alert===expected.alert && rule.labels.severity===expected.severity)
  assert.ok(actual,`Missing host alert: ${expected.alert}/${expected.severity}`)
  assert.equal(actual.expr,`${expected.expr} ${expected.op} ${expected.value}`,`${expected.alert}/${expected.severity}: shared expression drift`)
}
const dashboard=JSON.parse(fs.readFileSync(path.join(directory,'grafana/dashboards/yumpoo.json'),'utf8'))
const flatten=panels=>panels.flatMap(panel=>[panel,...flatten(panel.panels ?? [])])
const panels=flatten(dashboard.panels)
const charts=panels.filter(panel=>panel.type!=='row')
assert.ok(charts.filter(panel=>panel.type==='timeseries').every(panel=>panel.fieldConfig.defaults.custom.spanNulls===false),'Missing samples must not be interpolated')
assert.ok(charts.every(panel=>panel.title?.trim() && panel.description?.trim()),'Every chart needs a title and description')
assert.ok(charts.every(panel=>panel.targets.every(target=>target.legendFormat!=='{{instance}} {{service}} {{rule}} {{severity}}')),'Generic legends hide metric meaning')
assert.equal(new Set(panels.map(panel=>panel.id)).size,panels.length,'Panel IDs must be unique, including collapsed rows')
assert.equal(dashboard.uid,'yumpoo-offsite')
assert.equal(compose.services.grafana.environment.GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH,'/var/lib/grafana/dashboards/yumpoo.json')
assert.ok(compose.services.grafana.volumes.includes('./grafana/dashboards:/var/lib/grafana/dashboards:ro'))
for(const [index,panel] of panels.entries()) {
  const {x,y,w,h}=panel.gridPos
  assert.ok([x,y,w,h].every(Number.isInteger) && x>=0 && y>=0 && w>0 && h>0 && x+w<=24,`${panel.title}: invalid grid position`)
  for(const other of panels.slice(index+1)) {
    const grid=other.gridPos
    assert.ok(x+w<=grid.x || grid.x+grid.w<=x || y+h<=grid.y || grid.y+grid.h<=y,`${panel.title} overlaps ${other.title}`)
  }
}
if(process.argv.includes('--rules')) {
  const image=compose.services.prometheus.image
  const result=spawnSync('docker',['run','--rm','-v',`${directory}:/work:ro`,'--entrypoint','promtool',image,'test','rules','/work/rules.test.yml'],{stdio:'inherit'})
  assert.equal(result.status,0,'Prometheus rule tests failed')
  const expressions=[...new Set([
    ...charts.flatMap(panel=>panel.targets.map(target=>target.expr)),
    ...dashboard.annotations.list.flatMap(annotation=>[annotation.expr,annotation.target?.expr].filter(Boolean)),
  ])].map(expression=>expression.replaceAll('$__range','6h').replaceAll('$__rate_interval','2m'))
  const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'yumpoo-dashboard-promql-'))
  const file=path.join(temporary,'expressions.yml')
  try {
    fs.writeFileSync(file,YAML.stringify({groups:[{name:'dashboard-expressions',rules:expressions.map((expression,index)=>({record:`dashboard_expr_${index}`,expr:expression}))}]}))
    // promtool runs as nobody; Linux mkdtemp directories are owner-only by default.
    fs.chmodSync(temporary,0o755)
    fs.chmodSync(file,0o644)
    const syntax=spawnSync('docker',['run','--rm','-v',`${temporary}:/expressions:ro`,'--entrypoint','promtool',image,'check','rules','/expressions/expressions.yml'],{stdio:'inherit'})
    assert.equal(syntax.status,0,'Dashboard PromQL syntax check failed')
  } finally {
    if(fs.existsSync(file))fs.unlinkSync(file)
    fs.rmdirSync(temporary)
  }
}
console.log(`Monitoring configuration verified; ${memory} MiB total container limits.`)
