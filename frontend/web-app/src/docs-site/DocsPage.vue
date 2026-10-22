<script setup lang="ts">
import { ArrowLeft, ArrowRight, Guide, TopRight } from '@element-plus/icons-vue'
import { ElButton, ElIcon } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import YpResultPage from '../components/yp/YpResultPage.vue'
import { SECTIONS } from './content/sections'
import { AUDIENCE_LABELS, findDoc, loadDocHtml, neighbours } from './docsNav'

const route = useRoute()
const router = useRouter()
const slug = computed(() => typeof route.params.slug === 'string' ? route.params.slug : '')
const doc = computed(() => findDoc(slug.value))
const html = ref('')
const article = ref<HTMLElement>()
const activeId = ref('')
const tocOpen = ref(false)
const sectionLabel = computed(() => SECTIONS.find(section => section.id === doc.value?.section)?.label)
const toc = computed(() => doc.value?.headings ?? [])
const around = computed(() => neighbours(slug.value))
const audiences = computed(() => (doc.value?.audience ?? []).flatMap(item => AUDIENCE_LABELS[item] ? [AUDIENCE_LABELS[item]] : []))
let observer: IntersectionObserver | undefined
let revision = 0

function scrollToHash(): void {
  const id = route.hash ? decodeURIComponent(route.hash.slice(1)) : ''
  const target = id ? document.getElementById(id) : null
  if (target) target.scrollIntoView()
  else window.scrollTo(0, 0)
}

function observeHeadings(): void {
  observer?.disconnect()
  if (typeof IntersectionObserver === 'undefined' || !article.value) return
  const headings = [...article.value.querySelectorAll<HTMLElement>('h2[id], h3[id]')]
  activeId.value = headings[0]?.id ?? ''
  observer = new IntersectionObserver(entries => {
    const visible = entries.filter(entry => entry.isIntersecting).sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0]
    if (visible) activeId.value = visible.target.id
  }, { rootMargin: '-72px 0px -70% 0px' })
  headings.forEach(heading => observer!.observe(heading))
}

watch(slug, async value => {
  const current = ++revision
  html.value = ''
  tocOpen.value = false
  const meta = findDoc(value)
  if (!meta) return
  document.title = `${meta.title} · Yumpoo 文档`
  const content = await loadDocHtml(value)
  if (current !== revision) return
  html.value = content
  await nextTick()
  scrollToHash()
  observeHeadings()
}, { immediate: true })

watch(() => route.hash, () => { if (html.value) scrollToHash() })

function onClick(event: MouseEvent): void {
  const target = event.target as HTMLElement
  const copy = target.closest<HTMLButtonElement>('[data-docs-copy]')
  if (copy) {
    const code = copy.parentElement?.querySelector('code')?.textContent ?? ''
    void navigator.clipboard.writeText(code).then(() => {
      copy.textContent = '已复制'
      setTimeout(() => { copy.textContent = '复制' }, 1500)
    })
    return
  }
  const link = target.closest<HTMLAnchorElement>('a[href]')
  if (!link || event.ctrlKey || event.metaKey || event.shiftKey) return
  const href = link.getAttribute('href') ?? ''
  if (link.hasAttribute('data-docs-link')) {
    event.preventDefault()
    void router.push(href)
  } else if (href.startsWith('#')) {
    event.preventDefault()
    if (route.hash === href) scrollToHash()
    else void router.replace({ hash: href })
  }
}

function openInApp(): void {
  if (doc.value?.appRoute) void router.push({ name: doc.value.appRoute })
}

onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div
    v-if="doc"
    class="docs-page"
  >
    <div class="docs-page__content">
      <nav
        class="docs-breadcrumb"
        aria-label="面包屑"
      >
        <router-link to="/docs">
          文档
        </router-link>
        <span aria-hidden="true">/</span>
        <span>{{ sectionLabel }}</span>
      </nav>
      <header class="docs-page__header">
        <h1>{{ doc.title }}</h1>
        <p class="docs-page__lead">
          {{ doc.description }}
        </p>
        <div
          v-if="audiences.length || doc.appRoute"
          class="docs-page__meta"
        >
          <span
            v-for="audience in audiences"
            :key="audience"
            class="docs-audience"
          >{{ audience }}</span>
          <el-button
            v-if="doc.appRoute"
            size="small"
            class="docs-page__open"
            @click="openInApp"
          >
            在应用中打开<el-icon class="el-icon--right">
              <TopRight />
            </el-icon>
          </el-button>
        </div>
        <p
          v-if="doc.tour"
          class="docs-page__tour"
        >
          <el-icon><Guide /></el-icon>本页功能有界面导览：在应用对应页面点击右上角的「查看本页引导」按钮。
        </p>
      </header>
      <details
        v-if="toc.length"
        class="docs-toc-inline"
        :open="tocOpen"
        @toggle="tocOpen = ($event.target as HTMLDetailsElement).open"
      >
        <summary>本页目录</summary>
        <ul>
          <li
            v-for="heading in toc"
            :key="heading.id"
            :class="`is-depth-${heading.depth}`"
          >
            <a
              :href="`#${heading.id}`"
              @click.prevent="router.replace({ hash: `#${heading.id}` })"
            >{{ heading.text }}</a>
          </li>
        </ul>
      </details>
      <!-- html 只来自构建期编译、已通过 CSP 检查的仓库内文档。 -->
      <article
        ref="article"
        class="docs-prose"
        @click="onClick"
        v-html="html"
      />
      <nav
        class="docs-pager"
        aria-label="上一篇与下一篇"
      >
        <router-link
          v-if="around.previous"
          class="docs-pager__link"
          :to="`/docs/${around.previous.slug}`"
        >
          <span class="docs-pager__label"><el-icon><ArrowLeft /></el-icon>上一篇</span>
          <span class="docs-pager__title">{{ around.previous.title }}</span>
        </router-link>
        <router-link
          v-if="around.next"
          class="docs-pager__link docs-pager__link--next"
          :to="`/docs/${around.next.slug}`"
        >
          <span class="docs-pager__label">下一篇<el-icon><ArrowRight /></el-icon></span>
          <span class="docs-pager__title">{{ around.next.title }}</span>
        </router-link>
      </nav>
    </div>
    <aside
      v-if="toc.length"
      class="docs-toc"
      aria-label="本页目录"
    >
      <p class="docs-toc__title">
        本页目录
      </p>
      <ul>
        <li
          v-for="heading in toc"
          :key="heading.id"
          :class="[`is-depth-${heading.depth}`, { 'is-active': activeId === heading.id }]"
        >
          <a
            :href="`#${heading.id}`"
            @click.prevent="router.replace({ hash: `#${heading.id}` })"
          >{{ heading.text }}</a>
        </li>
      </ul>
    </aside>
  </div>
  <div
    v-else
    class="docs-missing"
  >
    <yp-result-page
      eyebrow="404"
      title="没有找到这篇文档"
      description="链接可能已经过期，或者文档已经调整位置。"
    >
      <template #actions>
        <router-link
          v-slot="{ href, navigate }"
          to="/docs"
          custom
        >
          <el-button
            type="primary"
            tag="a"
            :href="href"
            @click="navigate"
          >
            返回文档首页
          </el-button>
        </router-link>
      </template>
    </yp-result-page>
  </div>
</template>
