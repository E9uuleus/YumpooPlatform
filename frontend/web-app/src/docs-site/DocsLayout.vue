<script setup lang="ts">
import { ArrowDown, Download, Menu, Search } from '@element-plus/icons-vue'
import { ElButton, ElDrawer, ElIcon } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import brandLogo from '../assets/brand/logo.svg'
import YpThemeSwitcher from '../components/yp/YpThemeSwitcher.vue'
import { useAppearance } from '../composables/useAppearance'
import { AUDIENCE_LABELS, docSections, findDoc, type DocMeta } from './docsNav'
import DocsSearch from './DocsSearch.vue'
import { inDesktopShell } from './openDocs'
import './docs.css'

const route = useRoute()
const appearance = useAppearance()
const desktop = inDesktopShell()
const searchOpen = ref(false)
const drawerOpen = ref(false)
const shortcut = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K'
const current = computed(() => typeof route.params.slug === 'string' ? findDoc(route.params.slug) : undefined)
const home = computed(() => route.name === 'docs-home')
const expanded = ref(new Set<string>())
watch(() => current.value?.section, section => { if (section) expanded.value = new Set([...expanded.value, section]) }, { immediate: true })
watch(() => route.fullPath, () => { drawerOpen.value = false })

const badges = (doc: DocMeta) => doc.audience.flatMap(audience => AUDIENCE_LABELS[audience] ?? [])

function toggle(section: string): void {
  const next = new Set(expanded.value)
  if (next.has(section)) next.delete(section)
  else next.add(section)
  expanded.value = next
}

function onKeydown(event: KeyboardEvent): void {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchOpen.value = true
  }
}

const originalTitle = document.title
onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  document.title = originalTitle
})
</script>

<template>
  <div
    class="docs-site"
    :class="{ 'docs-site--home': home }"
  >
    <header class="docs-topbar">
      <button
        type="button"
        class="docs-topbar__menu"
        aria-label="打开文档目录"
        @click="drawerOpen = true"
      >
        <el-icon><Menu /></el-icon>
      </button>
      <router-link
        class="docs-brand"
        to="/docs"
      >
        <img
          :src="brandLogo"
          alt=""
        >
        <span>Yumpoo 文档</span>
      </router-link>
      <nav
        class="docs-topbar__sections"
        aria-label="文档分区"
      >
        <router-link
          v-for="section in docSections"
          :key="section.id"
          :to="`/docs/${section.docs[0]?.slug ?? ''}`"
          :class="{ 'is-active': current?.section === section.id }"
        >
          {{ section.label }}
        </router-link>
      </nav>
      <div class="docs-topbar__actions">
        <button
          type="button"
          class="docs-search-trigger"
          aria-label="搜索文档"
          @click="searchOpen = true"
        >
          <el-icon><Search /></el-icon>
          <span class="docs-search-trigger__text">搜索文档</span>
          <kbd>{{ shortcut }}</kbd>
        </button>
        <yp-theme-switcher
          class="docs-topbar__appearance"
          :theme="appearance.themeMode.value"
          :density="appearance.densityMode.value"
          @update:theme="appearance.setThemeMode"
          @update:density="appearance.setDensityMode"
        />
        <router-link
          v-if="!desktop"
          class="docs-topbar__download"
          to="/download"
        >
          <el-icon><Download /></el-icon>下载桌面端
        </router-link>
        <router-link
          v-slot="{ href, navigate }"
          to="/"
          custom
        >
          <el-button
            type="primary"
            tag="a"
            :href="href"
            class="docs-topbar__enter"
            @click="navigate"
          >
            进入工作台
          </el-button>
        </router-link>
      </div>
    </header>

    <div class="docs-body">
      <aside
        v-if="!home"
        class="docs-sidebar"
        aria-label="文档目录"
      >
        <nav class="docs-nav">
          <section
            v-for="section in docSections"
            :key="section.id"
            class="docs-nav__group"
          >
            <button
              type="button"
              class="docs-nav__section"
              :aria-expanded="expanded.has(section.id)"
              @click="toggle(section.id)"
            >
              {{ section.label }}
              <el-icon
                class="docs-nav__chevron"
                :class="{ 'is-open': expanded.has(section.id) }"
              >
                <ArrowDown />
              </el-icon>
            </button>
            <ul v-show="expanded.has(section.id)">
              <li
                v-for="doc in section.docs"
                :key="doc.slug"
              >
                <router-link
                  :to="`/docs/${doc.slug}`"
                  :class="{ 'is-active': current?.slug === doc.slug }"
                  :aria-current="current?.slug === doc.slug ? 'page' : undefined"
                >
                  {{ doc.title }}
                  <span
                    v-for="badge in badges(doc)"
                    :key="badge"
                    class="docs-audience"
                  >{{ badge }}</span>
                </router-link>
              </li>
            </ul>
          </section>
        </nav>
      </aside>
      <main class="docs-main">
        <router-view />
      </main>
    </div>

    <el-drawer
      v-model="drawerOpen"
      direction="ltr"
      size="min(320px, 86vw)"
      title="Yumpoo 文档"
      class="docs-drawer"
    >
      <nav
        class="docs-nav"
        aria-label="文档目录"
      >
        <section
          v-for="section in docSections"
          :key="section.id"
          class="docs-nav__group"
        >
          <p class="docs-nav__section docs-nav__section--static">
            {{ section.label }}
          </p>
          <ul>
            <li
              v-for="doc in section.docs"
              :key="doc.slug"
            >
              <router-link
                :to="`/docs/${doc.slug}`"
                :class="{ 'is-active': current?.slug === doc.slug }"
              >
                {{ doc.title }}
              </router-link>
            </li>
          </ul>
        </section>
        <router-link
          v-if="!desktop"
          class="docs-drawer__download"
          to="/download"
        >
          下载桌面端
        </router-link>
      </nav>
    </el-drawer>

    <docs-search v-model="searchOpen" />
  </div>
</template>
