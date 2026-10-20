// The deployment engine writes release.json last, from the published desktop Release; the web app only reads it.
export interface DesktopRelease {
  version: string
  tag: string
  installer: { name: string, bytes: number, sha256: string }
  notes: string[]
}

export const CHANNEL_PATH = '/desktop/stable/'

function parseRelease(value: unknown): DesktopRelease {
  const data = value as Partial<DesktopRelease> | null
  const installer = data?.installer
  if (typeof data?.version !== 'string' || !/^\d+\.\d+\.\d+$/u.test(data.version) || typeof data.tag !== 'string'
    || typeof installer?.name !== 'string' || !/^YumpooDesktop-Setup-[\d.]+-x64\.exe$/u.test(installer.name)
    || !Number.isSafeInteger(installer.bytes) || !/^[0-9a-f]{64}$/u.test(String(installer.sha256))
    || !Array.isArray(data.notes) || !data.notes.every(note => typeof note === 'string')) {
    throw new Error('Unexpected release.json')
  }
  return { version: data.version, tag: data.tag, installer: { name: installer.name, bytes: installer.bytes, sha256: installer.sha256 }, notes: data.notes }
}

/** The latest published desktop Release on the stable channel; the download page and the settings page share it. */
export async function loadDesktopRelease(): Promise<DesktopRelease> {
  const response = await fetch(`${CHANNEL_PATH}release.json`, { cache: 'no-store', credentials: 'omit' })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return parseRelease(await response.json())
}
