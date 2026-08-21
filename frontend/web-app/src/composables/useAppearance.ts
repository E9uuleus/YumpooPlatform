import { computed, readonly, ref } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark' | 'night'
export type ResolvedTheme = Exclude<ThemeMode, 'system'>
export type DensityMode = 'comfortable' | 'compact'

export const THEME_STORAGE_KEY = 'yumpoo.appearance.theme.v1'
export const DENSITY_STORAGE_KEY = 'yumpoo.appearance.density.v1'

const themeModes: readonly ThemeMode[] = ['system', 'light', 'dark', 'night']
const densityModes: readonly DensityMode[] = ['comfortable', 'compact']
const themeMode = ref<ThemeMode>('system')
const densityMode = ref<DensityMode>('comfortable')
const systemPrefersDark = ref(false)
const resolvedTheme = computed<ResolvedTheme>(() => themeMode.value === 'system'
  ? (systemPrefersDark.value ? 'dark' : 'light')
  : themeMode.value)

let initialized = false
let mediaQuery: MediaQueryList | undefined

function isThemeMode(value: string | null): value is ThemeMode {
  return Boolean(value && themeModes.includes(value as ThemeMode))
}

function isDensityMode(value: string | null): value is DensityMode {
  return Boolean(value && densityModes.includes(value as DensityMode))
}

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // The root attributes still apply when storage is unavailable.
  }
}

function applyRootAppearance(): void {
  const root = document.documentElement
  root.dataset.theme = resolvedTheme.value
  root.dataset.density = densityMode.value
  root.classList.toggle('dark', resolvedTheme.value !== 'light')
  root.style.colorScheme = resolvedTheme.value === 'light' ? 'light' : 'dark'
}

function handleSystemThemeChange(event: MediaQueryListEvent | MediaQueryList): void {
  systemPrefersDark.value = event.matches
  if (themeMode.value === 'system') applyRootAppearance()
}

function connectSystemTheme(): void {
  mediaQuery = window.matchMedia?.('(prefers-color-scheme: dark)')
  systemPrefersDark.value = mediaQuery?.matches ?? false
  if (!mediaQuery) return
  mediaQuery.addEventListener('change', handleSystemThemeChange)
}

export function initializeAppearance(): void {
  if (initialized || typeof window === 'undefined') return
  const storedTheme = readStorage(THEME_STORAGE_KEY)
  const storedDensity = readStorage(DENSITY_STORAGE_KEY)
  themeMode.value = isThemeMode(storedTheme) ? storedTheme : 'system'
  densityMode.value = isDensityMode(storedDensity) ? storedDensity : 'comfortable'
  connectSystemTheme()
  applyRootAppearance()
  initialized = true
}

export function setThemeMode(next: ThemeMode): void {
  themeMode.value = next
  writeStorage(THEME_STORAGE_KEY, next)
  applyRootAppearance()
}

export function setDensityMode(next: DensityMode): void {
  densityMode.value = next
  writeStorage(DENSITY_STORAGE_KEY, next)
  applyRootAppearance()
}

export function useAppearance() {
  initializeAppearance()
  return {
    themeMode: readonly(themeMode),
    resolvedTheme: readonly(resolvedTheme),
    densityMode: readonly(densityMode),
    setThemeMode,
    setDensityMode,
  }
}

export function resetAppearanceForTests(): void {
  if (mediaQuery) {
    mediaQuery.removeEventListener('change', handleSystemThemeChange)
  }
  mediaQuery = undefined
  initialized = false
  themeMode.value = 'system'
  densityMode.value = 'comfortable'
  systemPrefersDark.value = false
}
