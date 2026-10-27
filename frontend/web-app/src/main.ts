import { createApp } from 'vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import TimerQuickMenu from './components/timer/TimerQuickMenu.vue'
import { SHELL_SURFACE, shellSurfacePaths } from './components/desktop/surfaces'
import { dismissBootSplash } from './brand/boot-splash'
import { vBrandLoading } from './brand/loading'
import { initializeAppearance } from './composables/useAppearance'
import router from './router'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/element-plus.css'
import './styles/main.css'
import './styles/projects.css'
import './styles/timer-surface.css'

initializeAppearance()
const desktopSurface = window.yumpooDesktop ? window.location.pathname : ''
if (shellSurfacePaths.includes(desktopSurface)) document.documentElement.classList.add('timer-surface')
// The quick panel is driven only by the desktop shell over IPC, so it mounts without the router, session or timer polling.
if (desktopSurface === SHELL_SURFACE.menu) {
  createApp(TimerQuickMenu).mount('#app')
  dismissBootSplash()
} else {
  createApp(App).directive('loading', vBrandLoading).use(router).mount('#app')
  // The first navigation waits for the session check; the splash covers that gap instead of a blank page.
  void router.isReady().catch(() => undefined).finally(() => dismissBootSplash())
}
