import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','..')
const backend=path.join(root,'backend')
const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'yumpoo-m216-'))
const baseline=path.join(temporary,'openapi-baseline.yaml')
const metadata=path.join(temporary,'openapi-baseline.metadata.json')
const baseRef=process.env.YUMPOO_M216_BASE_REF??'origin/dev'
try{
  runPnpmSync(['run','validate:event-contracts'],{cwd:root})
  runPnpmSync(['run','doc-sync'],{cwd:root})
  runPnpmSync(['run','verify:m2-16:assets'],{cwd:root})
  runSync(process.execPath,[path.join(root,'tools','openapi','extract-openapi-baseline.mjs'),baseRef,baseline,metadata],{cwd:root})
  runPnpmSync(['run','check:openapi-compat','--',baseline],{cwd:root})
  if(process.platform==='win32')runSync('cmd.exe',['/d','/s','/c','mvnw.cmd clean verify'],{cwd:backend})
  else runSync('./mvnw',['clean','verify'],{cwd:backend})
  runPnpmSync(['run','verify:node'],{cwd:root})
  console.log('M2-16 Work Item 独立讨论、富文本净化与项目成员提及完整门禁已通过。')
}finally{fs.rmSync(temporary,{recursive:true,force:true})}
