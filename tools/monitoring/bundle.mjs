import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createGzip } from 'node:zlib'
import { pipeline } from 'node:stream/promises'
import YAML from 'yaml'
const directory=path.resolve(process.argv[2] || 'out/monitoring/bundle')
const run=(cmd,args,options={})=>{
  const result=spawnSync(cmd,args,{encoding:'utf8',...options})
  if(result.status!==0)throw new Error(`${cmd} failed: ${result.stderr || result.status}`)
  return result.stdout?.trim()
}
const commit=run('git',['rev-parse','HEAD'])
if(run('git',['status','--porcelain']).trim())throw new Error('Commit the reviewed monitoring source before creating a deployment bundle')
fs.mkdirSync(directory,{recursive:true})
const compose=YAML.parse(fs.readFileSync('deployment/monitoring/compose.yml','utf8'),{merge:true})
run('docker',['compose','-f','deployment/monitoring/compose.yml','build','hub'],{stdio:'inherit',env:{...process.env,MONITOR_BUILD:commit}})
const images={}; const overrides={services:{}}
for(const [name,service] of Object.entries(compose.services)) {
  const source=name==='hub'?`yumpoo-monitor-hub:${commit}`:service.image
  if(name!=='hub')run('docker',['pull',source],{stdio:'inherit'})
  const id=run('docker',['image','inspect',source,'--format','{{.Id}}'])
  const tag=`yumpoo-monitor-offline/${name}:${id.slice(7)}`
  run('docker',['tag',source,tag])
  images[name]={source,id,tag}
  overrides.services[name]={image:tag,pull_policy:'never'}
}
const archive=path.join(directory,'images.tar')
run('docker',['save','-o',archive,...Object.values(images).map(i=>i.tag)],{stdio:'inherit'})
await pipeline(fs.createReadStream(archive),createGzip({level:1}),fs.createWriteStream(`${archive}.gz`))
fs.unlinkSync(archive)
const hash=createHash('sha256')
for await (const chunk of fs.createReadStream(`${archive}.gz`))hash.update(chunk)
fs.writeFileSync(path.join(directory,'images.lock.json'),JSON.stringify({schemaVersion:1,commit,archiveSha256:hash.digest('hex'),images},null,2)+'\n')
fs.writeFileSync(path.join(directory,'compose.offline.yml'),YAML.stringify(overrides))
run('git',['archive','--format=tar','-o',path.join(directory,'source.tar'),commit,'deployment/monitoring'])
console.log(`Offline monitoring bundle created for ${commit}. Verify archiveSha256 before importing.`)
