import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DENSITY_STORAGE_KEY,
  initializeAppearance,
  resetAppearanceForTests,
  setDensityMode,
  setThemeMode,
  THEME_STORAGE_KEY,
  useAppearance,
} from './useAppearance'

interface MediaQueryController {
  matches: boolean
  dispatch(matches: boolean): void
}

function installMatchMedia(initialMatches = false): MediaQueryController {
  let listener: ((event: MediaQueryListEvent) => void) | undefined
  const controller = {
    matches: initialMatches,
    dispatch(matches: boolean) {
      controller.matches = matches
      listener?.({ matches } as MediaQueryListEvent)
    },
  }
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    get matches() {
      return controller.matches
    },
    media: '(prefers-color-scheme: dark)',
    onchange: null,
    addEventListener: (_type: string, next: (event: MediaQueryListEvent) => void) => {
      listener = next
    },
    removeEventListener: () => {
      listener = undefined
    },
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as MediaQueryList)))
  return controller
}

describe('外观运行时', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.removeAttribute('data-density')
    document.documentElement.className = ''
    document.documentElement.style.colorScheme = ''
    resetAppearanceForTests()
  })

  afterEach(() => {
    resetAppearanceForTests()
    vi.unstubAllGlobals()
  })

  it('非法持久化值回退到系统主题与舒适密度', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'sepia')
    localStorage.setItem(DENSITY_STORAGE_KEY, 'tiny')
    installMatchMedia(false)
    initializeAppearance()

    const appearance = useAppearance()
    expect(appearance.themeMode.value).toBe('system')
    expect(appearance.densityMode.value).toBe('comfortable')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.dataset.density).toBe('comfortable')
  })

  it('持久化 Night 与紧凑密度并应用 Dark 共用类', () => {
    installMatchMedia(false)
    initializeAppearance()
    setThemeMode('night')
    setDensityMode('compact')

    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('night')
    expect(localStorage.getItem(DENSITY_STORAGE_KEY)).toBe('compact')
    expect(document.documentElement.dataset.theme).toBe('night')
    expect(document.documentElement.dataset.density).toBe('compact')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('系统模式持续响应系统深浅色变化，手动主题不被覆盖', () => {
    const media = installMatchMedia(false)
    initializeAppearance()
    media.dispatch(true)
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)

    setThemeMode('light')
    media.dispatch(true)
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})
