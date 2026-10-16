import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { packagePlaceholders, pomPlaceholder, versionPlaceholderViolations } from './version-placeholders.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

function fixture(t) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-version-test-'))
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }))
  for (const file of [...Object.keys(packagePlaceholders), 'backend/pom.xml']) {
    fs.mkdirSync(path.dirname(path.join(directory, file)), { recursive: true })
    fs.copyFileSync(path.join(root, file), path.join(directory, file))
  }
  return directory
}

test('repository version fields stay fixed placeholders', () => {
  assert.deepEqual(versionPlaceholderViolations(root), [])
})

test('bumping a workspace package or the server pom is rejected', t => {
  const directory = fixture(t)
  const file = path.join(directory, 'frontend/web-app/package.json')
  fs.writeFileSync(file, JSON.stringify({ ...JSON.parse(fs.readFileSync(file, 'utf8')), version: '2026.10.20' }))
  const pom = path.join(directory, 'backend/pom.xml')
  fs.writeFileSync(pom, fs.readFileSync(pom, 'utf8').replace(`<version>${pomPlaceholder}</version>`, '<version>1.0.0</version>'))
  const violations = versionPlaceholderViolations(directory)
  assert.equal(violations.length, 2)
  assert.match(violations[0], /frontend\/web-app\/package\.json/u)
  assert.match(violations[1], /backend\/pom\.xml/u)
})

test('the Spring Boot parent version is not mistaken for the server version', t => {
  const directory = fixture(t)
  const pom = path.join(directory, 'backend/pom.xml')
  fs.writeFileSync(pom, fs.readFileSync(pom, 'utf8').replace(/(<parent>[\s\S]*?<version>)[^<]+/u, '$19.9.9'))
  assert.deepEqual(versionPlaceholderViolations(directory), [])
})
