import test from 'node:test'
import assert from 'node:assert/strict'
import { Probes } from '../src/probes.ts'
import type { Config, ReleaseEvent } from '../src/model.ts'

const manifest = {schemaVersion:1,tag:'desktop-v1.2.3',version:'1.2.3',sourceCommit:'a'.repeat(40),installer:{name:'YumpooDesktop-Setup-1.2.3-x64.exe',bytes:1234,sha256:'a'.repeat(64)}}
const hash = Buffer.alloc(64).toString('base64')
const latest = () => `version: 1.2.3\npath: ${manifest.installer.name}\nsha512: ${hash}\nfiles:\n  - url: ${manifest.installer.name}\n    size: 1234\n    sha512: ${hash}\n`
const config = {siteUrl:'https://example.com',services:[{id:'server'},{id:'desktop'}]} as Config
const event = {payload:{target:'desktop'}} as ReleaseEvent

test('desktop release requires coherent updater metadata and accessible complete installer', async () => {
  let metadata = latest(); let status = 200; let size = '1234'
  const probes = new Probes(config, (async (url: string | URL | Request) => {
    if(String(url).endsWith('/release.json')) return Response.json(manifest)
    if(String(url).endsWith('/latest.yml')) return new Response(metadata)
    return new Response(null,{status,headers:{'content-length':size}})
  }) as typeof fetch)
  assert.equal((await probes.verify(event)).healthy,true)
  metadata = latest().replace('version: 1.2.3','version: 1.2.2')
  await assert.rejects(probes.verify(event),/Inconsistent/u)
  metadata = 'version: 1.2.3'
  await assert.rejects(probes.verify(event),/Inconsistent/u)
  metadata = latest(); status = 404
  assert.equal((await probes.verify(event)).healthy,false)
  status = 200; size = '100'
  assert.equal((await probes.verify(event)).healthy,false)
})

test('desktop discovery failure does not invalidate successful server asset discovery', async () => {
  const probes = new Probes(config, (async (url: string | URL | Request) => String(url).endsWith('/release.json')
    ? Response.json({schemaVersion:0}) : new Response('<script src="/assets/app.js"></script>')) as typeof fetch)
  await probes.discover()
  assert.equal(probes.discovery.get('server'),true)
  assert.equal(probes.discovery.get('desktop'),false)
  assert.equal(probes.discoverySuccess,false)
})
