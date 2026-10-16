import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import {
  classifyMigration, evaluateNewMigrations, exceptionsPath, migrationDigest, migrationInventory, migrationRoot, splitStatements,
} from './migration-policy.mjs'

test('statement splitting ignores separators inside comments, quotes and dollar-quoted bodies', () => {
  assert.deepEqual(splitStatements(`
    -- leading; comment
    CREATE TABLE a (note text DEFAULT 'x;y');
    /* block; comment */
    DO $body$ BEGIN PERFORM 1; END $body$;
    COMMENT ON TABLE a IS 'it''s; fine'
  `).map(statement => statement.replace(/\s+/gu, ' ')), [
    "CREATE TABLE a (note text DEFAULT 'x;y')",
    'DO $body$ BEGIN PERFORM 1; END $body$',
    "COMMENT ON TABLE a IS 'it''s; fine'",
  ])
})

test('additive migrations that older code can ignore are expand-only', () => {
  for (const sql of [
    `CREATE TABLE yumpoo.feature (id uuid PRIMARY KEY, name text NOT NULL);
     CREATE UNIQUE INDEX uq_feature_name ON yumpoo.feature (name);
     ALTER TABLE yumpoo.feature ADD CONSTRAINT ck_feature_name CHECK (name <> '');
     INSERT INTO yumpoo.feature (id, name) SELECT id, name FROM yumpoo.project;`,
    'ALTER TABLE yumpoo.project ADD COLUMN archived_note text, ADD COLUMN pinned boolean NOT NULL DEFAULT false;',
    'CREATE INDEX ix_project_name ON yumpoo.project (name); COMMENT ON COLUMN yumpoo.project.name IS \'名称\';',
    'CREATE SEQUENCE yumpoo.ticket_seq; GRANT SELECT ON yumpoo.feature TO yumpoo_app;',
  ]) {
    assert.deepEqual(classifyMigration(sql), { classification: 'expand', reasons: [] }, sql)
  }
})

test('changes that can break the previous release are classified as breaking', () => {
  for (const sql of [
    'ALTER TABLE yumpoo.project ADD COLUMN owner_id uuid NOT NULL;',
    'ALTER TABLE yumpoo.project DROP COLUMN legacy_name;',
    'ALTER TABLE yumpoo.project RENAME COLUMN name TO title;',
    'ALTER TABLE yumpoo.project ALTER COLUMN name SET NOT NULL;',
    'ALTER TABLE yumpoo.project ADD CONSTRAINT ck_project_name CHECK (name <> \'\');',
    'CREATE UNIQUE INDEX uq_project_name ON yumpoo.project (name);',
    'DROP INDEX yumpoo.uq_work_item_running_timer;',
    'UPDATE yumpoo.project SET name = trim(name);',
    'INSERT INTO yumpoo.project_type (code) VALUES (\'NEW\');',
    'DO $$ BEGIN PERFORM 1; END $$;',
    'CREATE OR REPLACE VIEW yumpoo.project_view AS SELECT 1;',
    "CREATE TABLE broken (note text DEFAULT 'unterminated);",
  ]) {
    const result = classifyMigration(sql)
    assert.equal(result.classification, 'breaking', sql)
    assert(result.reasons.length > 0, sql)
  }
})

test('new-table exemptions require proof the table was created by this migration (REL-P1 F1–F3)', () => {
  for (const sql of [
    // F1：IF NOT EXISTS 可能命中已有表
    'CREATE TABLE IF NOT EXISTS yumpoo.work_item (id integer, title text); ALTER TABLE yumpoo.work_item DROP COLUMN title;',
    // F2：其他 schema 的同名新表不能豁免业务表
    'CREATE TABLE scratch.work_item (id integer); ALTER TABLE yumpoo.work_item DROP COLUMN title;',
    'CREATE TABLE work_item (id integer); DELETE FROM yumpoo.work_item;',
    'CREATE TABLE "Work_Item" (id integer); UPDATE work_item SET title = NULL;',
    // F3：新增列的内联约束可以收紧已有列
    "ALTER TABLE yumpoo.work_item ADD COLUMN codex_probe text CHECK (title <> 'blocked');",
    'ALTER TABLE yumpoo.work_item ADD COLUMN codex_probe text CONSTRAINT ck_probe CHECK (codex_probe <> \'\');',
  ]) {
    assert.equal(classifyMigration(sql).classification, 'breaking', sql)
  }
  assert.deepEqual(classifyMigration('CREATE TABLE IF NOT EXISTS yumpoo.audit_probe (id integer);'), { classification: 'expand', reasons: [] })
  assert.deepEqual(classifyMigration('CREATE TABLE Yumpoo.Feature (id integer); ALTER TABLE yumpoo.feature DROP COLUMN id;'), { classification: 'expand', reasons: [] })
  assert.deepEqual(classifyMigration('CREATE TABLE "yumpoo"."feature" (id integer); INSERT INTO yumpoo.feature VALUES (1);'), { classification: 'expand', reasons: [] })
})

function repository(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-migration-policy-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const git = (...args) => {
    const result = spawnSync('git', ['-c', 'user.name=test', '-c', 'user.email=test@example.invalid', ...args], { cwd: root, encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
    return result.stdout.trim()
  }
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    fs.writeFileSync(path.join(root, file), text)
  }
  git('init', '-q')
  write(`${migrationRoot}/project/V1__create_project.sql`, 'CREATE TABLE yumpoo.project (id uuid PRIMARY KEY, name text);\n')
  write(exceptionsPath, `${JSON.stringify({ schemaVersion: 1, exceptions: [] }, null, 2)}\n`)
  git('add', '.')
  git('commit', '-q', '-m', 'base')
  return { root, git, write, base: git('rev-parse', 'HEAD') }
}

test('only migrations added after the baseline are gated', t => {
  const { root, write, base } = repository(t)
  write(`${migrationRoot}/project/V2__add_note.sql`, 'ALTER TABLE yumpoo.project ADD COLUMN note text;\n')
  write(`${migrationRoot}/V3__root_level_index.sql`, 'CREATE INDEX ix_project_note ON yumpoo.project (note);\n')
  const result = evaluateNewMigrations(root, base)
  assert.deepEqual(result.added.map(item => [item.version, item.file, item.classification]), [
    [2, 'project/V2__add_note.sql', 'expand'],
    [3, 'V3__root_level_index.sql', 'expand'],
  ])
  assert.deepEqual(result.violations, [])
})

test('a breaking migration fails until an exact reviewed exception is registered', t => {
  const { root, write, base } = repository(t)
  const sql = 'ALTER TABLE yumpoo.project DROP COLUMN name;\n'
  write(`${migrationRoot}/project/V2__drop_name.sql`, sql)
  assert.equal(evaluateNewMigrations(root, base).violations.length, 1)

  const register = entry => write(exceptionsPath, JSON.stringify({ schemaVersion: 1, exceptions: [entry] }))
  register({ file: 'project/V2__drop_name.sql', sha256: '0'.repeat(64), classification: 'expand', reason: '只删除未使用列' })
  assert.throws(() => evaluateNewMigrations(root, base), /内容不一致/u)
  register({ file: 'project/V2__drop_name.sql', sha256: migrationDigest(sql), classification: 'breaking', reason: '移除列' })
  assert.throws(() => evaluateNewMigrations(root, base), /Agent Note/u)
  register({ file: 'project/V2__drop_name.sql', sha256: migrationDigest(sql), classification: 'expand', reason: '旧版本不读取该列' })
  assert.deepEqual(evaluateNewMigrations(root, base).violations, [])
  assert.equal(migrationInventory(root).find(item => item.version === 2).classification, 'expand')
})

test('registered exceptions are append-only against the baseline', t => {
  const { root, git, write } = repository(t)
  const sql = 'ALTER TABLE yumpoo.project DROP COLUMN name;\n'
  const entry = { file: 'project/V2__drop_name.sql', sha256: migrationDigest(sql), classification: 'expand', reason: '旧版本不读取该列' }
  write(`${migrationRoot}/project/V2__drop_name.sql`, sql)
  write(exceptionsPath, JSON.stringify({ schemaVersion: 1, exceptions: [entry] }))
  git('add', '.')
  git('commit', '-q', '-m', 'register')
  const base = git('rev-parse', 'HEAD')
  write(exceptionsPath, JSON.stringify({ schemaVersion: 1, exceptions: [{ ...entry, reason: '改写理由' }] }))
  assert.throws(() => evaluateNewMigrations(root, base), /只允许追加/u)
})
