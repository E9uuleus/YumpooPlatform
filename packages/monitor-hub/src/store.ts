import { DatabaseSync, type SQLInputValue } from 'node:sqlite'

export class Store {
  db: DatabaseSync
  constructor(filename: string) {
    this.db = new DatabaseSync(filename)
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, correlation TEXT NOT NULL, kind TEXT NOT NULL, at INTEGER NOT NULL, body TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS deployments(id TEXT PRIMARY KEY, rank INTEGER NOT NULL, event TEXT NOT NULL, verify_until INTEGER, verified INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS maintenance(id TEXT PRIMARY KEY, service TEXT NOT NULL, title TEXT NOT NULL, starts INTEGER NOT NULL, ends INTEGER NOT NULL, state TEXT NOT NULL, automatic INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS observations(service TEXT PRIMARY KEY, state TEXT NOT NULL, at INTEGER NOT NULL, since INTEGER NOT NULL, recovery_since INTEGER, incident TEXT, ops INTEGER NOT NULL DEFAULT 0, public INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS history(id INTEGER PRIMARY KEY, service TEXT NOT NULL, state TEXT NOT NULL, at INTEGER NOT NULL, maintained INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS history_at ON history(at);
      CREATE TABLE IF NOT EXISTS alerts(id TEXT PRIMARY KEY, body TEXT NOT NULL, state TEXT NOT NULL, last_notice INTEGER NOT NULL, notified INTEGER NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS deliveries(id INTEGER PRIMARY KEY, dedupe TEXT UNIQUE NOT NULL, incident TEXT, channel TEXT NOT NULL, content TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL, expires INTEGER, created INTEGER NOT NULL, sent INTEGER, error TEXT);
      CREATE INDEX IF NOT EXISTS deliveries_pending ON deliveries(state,next_at);
      CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, action TEXT NOT NULL, subject TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS nonces(id TEXT PRIMARY KEY, at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS attempts(id INTEGER PRIMARY KEY, channel TEXT NOT NULL, at INTEGER NOT NULL);
    `)
  }
  run(sql: string, ...values: SQLInputValue[]) { return this.db.prepare(sql).run(...values) }
  get<T>(sql: string, ...values: SQLInputValue[]) { return this.db.prepare(sql).get(...values) as T | undefined }
  all<T>(sql: string, ...values: SQLInputValue[]) { return this.db.prepare(sql).all(...values) as T[] }
  transaction<T>(fn: () => T): T {
    this.db.exec('BEGIN IMMEDIATE')
    try { const result = fn(); this.db.exec('COMMIT'); return result } catch (e) { this.db.exec('ROLLBACK'); throw e }
  }
  audit(action: string, subject: string, now: number) { this.run('INSERT INTO audit(at,action,subject) VALUES(?,?,?)', now, action, subject) }
  prune(now: number) {
    this.transaction(() => {
      const cutoff = now - 180 * 86400_000
      for (const table of ['events', 'history', 'audit']) this.run(`DELETE FROM ${table} WHERE at < ?`, cutoff)
      this.run("DELETE FROM deliveries WHERE created < ? AND state != 'pending'", cutoff)
      this.run('DELETE FROM nonces WHERE at < ?', now - 600_000)
      this.run('DELETE FROM attempts WHERE at < ?', now - 60_000)
      this.run('DELETE FROM maintenance WHERE ends < ?', cutoff)
      this.run("DELETE FROM alerts WHERE state='resolved' AND last_notice < ?", cutoff)
      this.run('DELETE FROM deployments WHERE verified != 0 AND id NOT IN (SELECT correlation FROM events)')
    })
  }
  close() { this.db.close() }
}
