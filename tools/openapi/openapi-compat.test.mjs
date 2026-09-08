import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import test from 'node:test'
import { stringify } from 'yaml'
import { verificationEnvironment } from '../ci/environment.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const baseline = {
  openapi: '3.0.3', info: { title: 'Compatibility probe', version: '1.0.0' },
  paths: { '/items': { get: {
    operationId: 'listItems',
    parameters: [{ name: 'state', in: 'query', schema: { type: 'string', enum: ['OPEN', 'DONE'] } }],
    responses: { 200: { description: 'OK', content: { 'application/json': { schema: {
      type: 'object', required: ['id', 'state'], properties: {
        id: { type: 'string' }, state: { type: 'string', enum: ['OPEN', 'DONE'] },
      },
    } } } } },
  } } },
}

test('real openapi-diff accepts additive changes and blocks endpoint and enum incompatibilities', { timeout: 180_000 }, context => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-openapi-diff-'))
  context.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  const oldFile = path.join(directory, 'before.yaml')
  const newFile = path.join(directory, 'after.yaml')
  fs.writeFileSync(oldFile, stringify(baseline))
  const response = spec => spec.paths['/items'].get.responses[200].content['application/json'].schema
  for (const [label, compatible, mutate] of [
    ['optional response property', true, spec => { response(spec).properties.note = { type: 'string' } }],
    ['request enum expansion', true, spec => { spec.paths['/items'].get.parameters[0].schema.enum.push('PAUSED') }],
    ['endpoint deletion', false, spec => { delete spec.paths['/items'] }],
    ['request enum contraction', false, spec => { spec.paths['/items'].get.parameters[0].schema.enum.pop() }],
    ['response enum expansion', false, spec => { response(spec).properties.state.enum.push('PAUSED') }],
  ]) {
    const current = structuredClone(baseline)
    mutate(current)
    fs.writeFileSync(newFile, stringify(current))
    const args = ['-q', '-f', '../tools/openapi/openapi-diff-pom.xml', 'verify',
      `-Dopenapi.oldSpec=${pathToFileURL(oldFile).href}`, `-Dopenapi.newSpec=${pathToFileURL(newFile).href}`]
    const command = process.platform === 'win32' ? 'cmd.exe' : './mvnw'
    const invocation = process.platform === 'win32' ? ['/d', '/s', '/c', `mvnw.cmd ${args.join(' ')}`] : args
    const result = spawnSync(command, invocation, { cwd: path.join(root, 'backend'),
      env: verificationEnvironment(process.env), encoding: 'utf8', timeout: 60_000, windowsHide: true })
    assert.ifError(result.error)
    const diagnostic = `${result.stdout}\n${result.stderr}`
    if (compatible) assert.equal(result.status, 0, `${label}: ${diagnostic.slice(-2000)}`)
    else {
      assert.notEqual(result.status, 0, `${label} was incorrectly accepted`)
      assert.match(diagnostic, /The API changes broke backward compatibility/u, `${label}: ${diagnostic.slice(-2000)}`)
    }
  }
})
