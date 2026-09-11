import { createApp } from 'vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import { initializeAppearance } from './composables/useAppearance'
import router from './router'
import './styles/fonts.css'
import './styles/tokens.css'
import './styles/element-plus.css'
import './styles/main.css'
import './styles/projects.css'

initializeAppearance()
if (window.yumpooDesktop && window.location.pathname === '/timer') document.documentElement.classList.add('timer-surface')
createApp(App).use(router).mount('#app')
