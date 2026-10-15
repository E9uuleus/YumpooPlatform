import { readonly, ref } from 'vue'

export interface ReleaseVersion {
  release: string
  commit: string
}

const version = ref<ReleaseVersion | null>(null)
let loading: Promise<void> | undefined

export function parseReleaseVersion(body: unknown): ReleaseVersion | null {
  if (typeof body !== 'object' || body === null) return null
  const { release, build } = body as { release?: { tag?: unknown }, build?: { commit?: unknown } }
  return typeof release?.tag === 'string' && typeof build?.commit === 'string'
    ? { release: release.tag, commit: build.commit }
    : null
}

// Nginx 把 /version.json 映射到 Actuator info；发布标签由部署引擎在上线时注入。
export function useReleaseVersion() {
  loading ??= fetch('/version.json', { cache: 'no-store', credentials: 'omit' })
    .then(response => response.ok ? response.json() : null)
    .then((body: unknown) => { version.value = parseReleaseVersion(body) })
    .catch(() => { version.value = null })
  return readonly(version)
}
