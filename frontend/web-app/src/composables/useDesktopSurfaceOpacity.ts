import { readonly, ref } from 'vue'

export const SURFACE_OPACITY_KEY = 'yumpoo.desktop.surface-opacity.v1'
const opacity = ref(96)
let initialized = false

const valid = (value: number) => Number.isInteger(value) && value >= 70 && value <= 100

/** Quick panel and rail backgrounds mix `--yp-bg-raised` with transparency by this percentage. */
function apply(): void {
  document.documentElement.style.setProperty('--yp-desktop-surface-alpha', `${opacity.value}%`)
}

/** Each desktop window keeps its own copy; the storage event carries changes from the settings page to the panel and the rail. */
function initialize(): void {
  if (initialized) return
  initialized = true
  try {
    const stored = Number(window.localStorage.getItem(SURFACE_OPACITY_KEY))
    if (valid(stored)) opacity.value = stored
  } catch { /* The default applies when storage is unavailable. */ }
  window.addEventListener('storage', event => {
    const value = Number(event.newValue)
    if (event.key !== SURFACE_OPACITY_KEY || !valid(value)) return
    opacity.value = value
    apply()
  })
  apply()
}

export function useDesktopSurfaceOpacity() {
  initialize()
  function setOpacity(value: number): void {
    opacity.value = value
    try { window.localStorage.setItem(SURFACE_OPACITY_KEY, String(value)) } catch { /* Still applies to this window. */ }
    apply()
  }
  return { opacity: readonly(opacity), setOpacity }
}
