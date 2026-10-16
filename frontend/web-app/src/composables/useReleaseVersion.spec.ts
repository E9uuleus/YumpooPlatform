import { describe, expect, it } from 'vitest'
import { parseReleaseVersion } from './useReleaseVersion'

describe('parseReleaseVersion', () => {
  it('reads the injected release tag and build commit from Actuator info', () => {
    expect(parseReleaseVersion({
      release: { tag: 'server-v2026.10.20.1' },
      build: { commit: '0123456789abcdef0123456789abcdef01234567', version: '0.0.0-SNAPSHOT' },
    })).toEqual({ release: 'server-v2026.10.20.1', commit: '0123456789abcdef0123456789abcdef01234567' })
  })

  it('hides the version when the payload is not Actuator info', () => {
    expect(parseReleaseVersion(null)).toBeNull()
    expect(parseReleaseVersion('<!doctype html>')).toBeNull()
    expect(parseReleaseVersion({ release: { tag: 'server-v2026.10.20.1' } })).toBeNull()
    expect(parseReleaseVersion({ release: { tag: 1 }, build: { commit: 'abc' } })).toBeNull()
  })
})
