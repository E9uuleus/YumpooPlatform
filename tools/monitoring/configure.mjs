import fs from 'node:fs'
import path from 'node:path'
import { randomBytes, scryptSync } from 'node:crypto'
import { pathToFileURL } from 'node:url'
import YAML from 'yaml'

export function prometheusConfig(siteUrl) {
  const site = new URL(siteUrl)
  if (site.protocol !== 'https:' || site.pathname !== '/' || site.search || site.username || site.password) throw new Error('Site URL must be an HTTPS origin')
  const targets = [
    ['/', 'web', 'home', 'server'], ['/healthz','health','health','server'],
    ['/version.json','version','version','server'], ['/api/v1/auth/me','auth','auth','server'],
    ['/download','web','download','desktop'], ['/desktop/stable/release.json','manifest','manifest','desktop'],
  ].map(([suffix,module,check,service]) => ({targets:[`${site.origin}${suffix}`],labels:{module,check,service}}))
  const scrape = (job, address, metrics_path = '/metrics') => ({ job_name:job, metrics_path, static_configs:[{targets:[address]}] })
  return {
    global:{scrape_interval:'30s',evaluation_interval:'30s',scrape_timeout:'12s'},
    rule_files:['/etc/prometheus/rules.yml'],
    alerting:{alertmanagers:[{static_configs:[{targets:['127.0.0.1:9093']}]}]},
    scrape_configs:[
      scrape('prometheus','127.0.0.1:9090'),scrape('blackbox','127.0.0.1:9115'),
      scrape('ubuntu','127.0.0.1:9100'),scrape('alertmanager','127.0.0.1:9093'),
      scrape('monitor-hub','127.0.0.1:8788'),scrape('windows','127.0.0.1:19100','/metrics/windows'),
      scrape('application','127.0.0.1:19100','/metrics/app'),
      {
        job_name:'external',metrics_path:'/probe',sample_limit:2000,
        static_configs:targets,file_sd_configs:[{files:['/discovery/targets.json'],refresh_interval:'30s'}],
        relabel_configs:[
          {source_labels:['__address__'],target_label:'__param_target'},
          {source_labels:['module'],target_label:'__param_module'},
          {source_labels:['__param_target'],target_label:'instance'},
          {target_label:'__address__',replacement:'127.0.0.1:9115'},
        ],
      },
    ],
  }
}
export function configure({ directory, siteUrl, environment, channels = {} }) {
  fs.mkdirSync(directory,{recursive:true,mode:0o700})
  const secretFile = path.join(directory,'secrets.json')
  if (fs.existsSync(secretFile)) throw new Error('Configuration already exists; edit the existing protected configuration')
  const password = randomBytes(24).toString('base64url')
  const grafanaPassword = randomBytes(24).toString('base64url')
  const salt = randomBytes(24).toString('hex')
  const secret = () => randomBytes(32).toString('hex')
  const config = {
    environment,database:'/data/hub.sqlite',siteUrl:new URL(siteUrl).origin,prometheusUrl:'http://127.0.0.1:9090',uiRoot:'/app/ui',discoveryFile:'/discovery/targets.json',
    admin:{username:'monitor-admin',salt,passwordHash:scryptSync(password,salt,64).toString('hex')},
    sources:{windows:{secret:secret(),types:['maintenance.planned','maintenance.started','deployment.completed','deployment.recovered']},github:{secret:secret(),types:['workflow.failed','workflow.notice']}},
    alertToken:secret(),channels,
    services:[{id:'server',name:'Yumpoo 协作平台',checks:['home','health','version','auth','assets']},{id:'desktop',name:'桌面下载与更新',checks:['download','manifest','installer']}],
  }
  const write = (name, content) => fs.writeFileSync(path.join(directory,name),content,{mode:0o600,flag:'wx'})
  write('secrets.json',`${JSON.stringify(config,null,2)}\n`)
  write('admin-access.json',`${JSON.stringify({username:'monitor-admin',password,grafanaPassword},null,2)}\n`)
  write('grafana-password',grafanaPassword)
  write('alert-token',config.alertToken)
  write('prometheus.yml',YAML.stringify(prometheusConfig(siteUrl)))
  return config
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const [directory,siteUrl,environment,channelsFile] = process.argv.slice(2)
  if (!directory || !siteUrl || !environment) throw new Error('Usage: node tools/monitoring/configure.mjs <protected-directory> <https-origin> <environment> [channels-json]')
  configure({directory,siteUrl,environment,channels:channelsFile ? JSON.parse(fs.readFileSync(channelsFile,'utf8')) : {}})
  console.log('Protected configuration created. Read admin-access.json locally; no credentials are printed.')
}
