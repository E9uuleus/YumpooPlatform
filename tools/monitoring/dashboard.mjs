import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
const panels = [
  ['外网探测成功率','probe_success','short'],
  ['外网响应耗时','probe_duration_seconds','s'],
  ['Windows CPU 使用率','1 - avg(rate(windows_cpu_time_total{mode="idle"}[5m]))','percentunit'],
  ['Windows 可用内存','windows_memory_available_bytes','bytes'],
  ['Windows 磁盘可用空间','windows_logical_disk_free_bytes','bytes'],
  ['Windows 关键服务','windows_service_state{state="running"}','short'],
  ['Ubuntu CPU 使用率','1 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m]))','percentunit'],
  ['Ubuntu 可用内存','node_memory_MemAvailable_bytes','bytes'],
  ['Ubuntu 磁盘可用空间','node_filesystem_avail_bytes{mountpoint="/"}','bytes'],
  ['JVM 堆占用率','yumpoo_ops_heap_ratio','percentunit'],
  ['HTTP P95 延迟','yumpoo_ops_http_p95','ms'],
  ['HTTP 错误率','yumpoo_ops_http_errorrate','percentunit'],
  ['数据库连接池','{__name__=~"yumpoo_ops_db_pool_active|yumpoo_ops_db_pool_pending|yumpoo_ops_db_pool_max"}','short'],
  ['事件队列最老积压','yumpoo_ops_outbox_oldestage','s'],
  ['应用活动规则','yumpoo_ops_alert_active','short'],
  ['应用采样年龄','time() - yumpoo_ops_sample_timestamp_seconds','s'],
  ['监控组件与采集链路','up','short'],
  ['通知积压与失败','{__name__=~"monitor_delivery_pending|monitor_delivery_dead"}','short'],
  ['证书剩余天数','(probe_ssl_earliest_cert_expiry-time())/86400','d'],
  ['Prometheus 存储块','prometheus_tsdb_storage_blocks_bytes','bytes'],
]
const dashboard={uid:'yumpoo-offsite',title:'Yumpoo 异机监控',schemaVersion:39,version:1,refresh:'30s',timezone:'browser',time:{from:'now-6h',to:'now'},tags:['yumpoo','offsite'],panels:panels.map(([title,expr,unit],i)=>({id:i+1,title,type:'timeseries',datasource:{type:'prometheus',uid:'prometheus'},gridPos:{x:(i%2)*12,y:Math.floor(i/2)*8,w:12,h:8},targets:[{refId:'A',expr,legendFormat:'{{instance}} {{service}} {{rule}} {{severity}}'}],fieldConfig:{defaults:{unit,custom:{spanNulls:false,lineWidth:2,fillOpacity:5}},overrides:[]},options:{legend:{displayMode:'list',placement:'bottom'},tooltip:{mode:'multi'}}}))}
const file=path.join(root,'deployment/monitoring/grafana/dashboards/yumpoo.json')
const text=`${JSON.stringify(dashboard,null,2)}\n`
if(process.argv.includes('--check')){if(fs.readFileSync(file,'utf8')!==text)throw new Error('Regenerate the monitoring dashboard')}
else {fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,text)}
