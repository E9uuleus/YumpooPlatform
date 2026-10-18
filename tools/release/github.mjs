import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { repository, validateArtifactEntries } from './policy.mjs'

export function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 60_000, ...options, shell: false })
  if (result.error || result.status !== 0) {
    throw new Error(`${command} failed (${result.status ?? result.error?.code ?? 'unknown'}): ${(result.stderr ?? '').trim()}`)
  }
  return result.stdout
}

export function createGithub(env = process.env, execute = run) {
  assert(env.GH_TOKEN, 'GH_TOKEN is required')
  const base = `/repos/${repository}`
  const apiArgs = endpoint => ['api', '--hostname', 'github.com', `${base}${endpoint}`, '--method', 'GET', '-H', 'X-GitHub-Api-Version: 2022-11-28']
  return {
    get(endpoint) {
      return JSON.parse(execute('gh', apiArgs(endpoint), { env }))
    },
    list(endpoint, field) {
      const pages = JSON.parse(execute('gh', [...apiArgs(endpoint), '--paginate', '--slurp'], { env }))
      return pages.flatMap(page => field ? page[field] : page)
    },
    download(endpoint, destination) {
      const output = fs.openSync(destination, 'wx', 0o600)
      try {
        const accept = endpoint.startsWith('/actions/artifacts/') ? 'application/vnd.github+json' : 'application/octet-stream'
        execute('gh', [...apiArgs(endpoint), '-H', `Accept: ${accept}`], {
          env, stdio: ['ignore', output, 'pipe'], timeout: 300_000,
        })
      } finally {
        fs.closeSync(output)
      }
    },
    send(method, endpoint, body) {
      // No DELETE: release tooling must never be able to remove a Release, tag or branch.
      assert(['POST', 'PATCH'].includes(method), 'Unsupported GitHub write method')
      const args = ['api', '--hostname', 'github.com', `${base}${endpoint}`, '--method', method, '-H', 'X-GitHub-Api-Version: 2022-11-28']
      const output = execute('gh', body === undefined ? args : [...args, '--input', '-'],
        { env, input: body === undefined ? undefined : JSON.stringify(body) })
      return output.trim() ? JSON.parse(output) : null
    },
    // Draft Releases have no tag yet, so assets go to the upload endpoint by Release ID.
    uploadAsset(releaseId, file) {
      assert(Number.isSafeInteger(releaseId) && releaseId > 0, 'Invalid Release ID')
      const url = `https://uploads.github.com${base}/releases/${releaseId}/assets?name=${encodeURIComponent(path.basename(file))}`
      return JSON.parse(execute('gh', ['api', url, '--method', 'POST', '-H', 'Content-Type: application/octet-stream',
        '-H', 'X-GitHub-Api-Version: 2022-11-28', '--input', file], { env, timeout: 300_000 }))
    },
  }
}

export async function fileDigest(file) {
  const hash = createHash('sha256')
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk)
  return hash.digest('hex')
}

export function readJson(file) {
  assert(fs.lstatSync(file).isFile(), `Expected a regular file: ${path.basename(file)}`)
  return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/u, ''))
}

export function unpackArtifact(archive, destination, validateEntries = validateArtifactEntries) {
  const entries = run('unzip', ['-Z1', archive]).trimEnd().split(/\r?\n/u)
  validateEntries(entries)
  fs.mkdirSync(destination, { recursive: true })
  for (const name of entries) {
    const output = fs.openSync(path.join(destination, name), 'wx', 0o600)
    try {
      run('unzip', ['-p', archive, name], { stdio: ['ignore', output, 'pipe'], timeout: 300_000 })
    } finally {
      fs.closeSync(output)
    }
  }
}
