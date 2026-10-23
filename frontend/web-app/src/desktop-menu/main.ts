import { createApp, nextTick } from 'vue'
import TimerQuickMenu from '../components/timer/TimerQuickMenu.vue'
import '../styles/fonts.css'
import '../styles/tokens.css'
import '../styles/timer-surface.css'
import './menu.css'

const menu = window.yumpooMenu
if (!menu) throw new Error('LOCAL_MENU_BRIDGE_MISSING')
const state = await menu.getState()
document.documentElement.classList.add('timer-surface')
createApp(TimerQuickMenu, { initialState: state }).mount('#app')
await nextTick()
await menu.ready()
