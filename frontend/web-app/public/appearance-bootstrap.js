(() => {
  const themeKey = 'yumpoo.appearance.theme.v1'
  const densityKey = 'yumpoo.appearance.density.v1'
  const themes = new Set(['system', 'light', 'dark', 'night'])
  const densities = new Set(['comfortable', 'compact'])

  let theme = 'system'
  let density = 'comfortable'

  try {
    const storedTheme = window.localStorage.getItem(themeKey)
    const storedDensity = window.localStorage.getItem(densityKey)
    if (storedTheme && themes.has(storedTheme)) theme = storedTheme
    if (storedDensity && densities.has(storedDensity)) density = storedDensity
  } catch {
    // Storage may be unavailable in hardened or private browser contexts.
  }

  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  const resolvedTheme = theme === 'system' ? (prefersDark ? 'dark' : 'light') : theme
  const root = document.documentElement

  root.dataset.theme = resolvedTheme
  root.dataset.density = density
  root.classList.toggle('dark', resolvedTheme !== 'light')
  root.style.colorScheme = resolvedTheme === 'light' ? 'light' : 'dark'
})()
