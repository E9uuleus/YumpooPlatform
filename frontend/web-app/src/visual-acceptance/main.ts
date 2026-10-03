import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import { createApp } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { initializeAppearance } from '../composables/useAppearance'
import '../styles/tokens.css'
import '../styles/element-plus.css'
import '../styles/main.css'
import '../styles/projects.css'
import VisualLanguagePreview from './VisualLanguagePreview.vue'

initializeAppearance()
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
createApp(VisualLanguagePreview).use(ElementPlus).use(router).mount('#app')
