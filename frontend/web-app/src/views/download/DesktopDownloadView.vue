<script setup lang="ts">
import { ElButton } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import StatusScene from '../../components/motion/StatusScene.vue'
import YpResultPage from '../../components/yp/YpResultPage.vue'

// The deployment engine writes release.json last, from the published desktop Release; this page only reads it.
export interface DesktopRelease {
  version: string
  tag: string
  installer: { name: string, bytes: number, sha256: string }
  notes: string[]
}

const CHANNEL_PATH = '/desktop/stable/'
const release = ref<DesktopRelease>()
const failed = ref(false)
// The shell refuses downloads other than attachments; installing a new version starts from a browser.
const inDesktopShell = window.yumpooDesktop?.client === 'electron'
const pageUrl = `${window.location.origin}/download`

function parseRelease(value: unknown): DesktopRelease {
  const data = value as Partial<DesktopRelease> | null
  const installer = data?.installer
  if (typeof data?.version !== 'string' || !/^\d+\.\d+\.\d+$/u.test(data.version) || typeof data.tag !== 'string'
    || typeof installer?.name !== 'string' || !/^YumpooDesktop-Setup-[\d.]+-x64\.exe$/u.test(installer.name)
    || !Number.isSafeInteger(installer.bytes) || !/^[0-9a-f]{64}$/u.test(String(installer.sha256))
    || !Array.isArray(data.notes) || !data.notes.every(note => typeof note === 'string')) {
    throw new Error('Unexpected release.json')
  }
  return { version: data.version, tag: data.tag, installer: { name: installer.name, bytes: installer.bytes, sha256: installer.sha256 }, notes: data.notes }
}

async function load(): Promise<void> {
  failed.value = false
  try {
    const response = await fetch(`${CHANNEL_PATH}release.json`, { cache: 'no-store', credentials: 'omit' })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    release.value = parseRelease(await response.json())
  } catch {
    release.value = undefined
    failed.value = true
  }
}

const sizeLabel = computed(() => release.value ? `${(release.value.installer.bytes / 1024 / 1024).toFixed(1)} MB` : '')
const downloadUrl = computed(() => release.value ? `${CHANNEL_PATH}${encodeURIComponent(release.value.installer.name)}` : '')
const title = computed(() => release.value ? `Yumpoo Desktop ${release.value.version}` : '下载 Yumpoo Desktop')
const description = computed(() => failed.value
  ? '暂时无法获取桌面客户端版本信息，可以稍后重试，或继续使用浏览器访问。'
  : '适用于 Windows 10/11 64 位。安装后自动更新，无需管理员权限。')

onMounted(load)
</script>

<template>
  <main class="status-page">
    <yp-result-page
      eyebrow="桌面客户端"
      :title="title"
      :description="description"
    >
      <template #scene>
        <status-scene variant="upgrade" />
      </template>
      <template
        v-if="release"
        #extra
      >
        <dl class="desktop-download__facts">
          <dt>文件</dt>
          <dd>{{ release.installer.name }}（{{ sizeLabel }}）</dd>
          <dt>SHA-256</dt>
          <dd><code class="desktop-download__digest">{{ release.installer.sha256 }}</code></dd>
        </dl>
        <section
          v-if="release.notes.length"
          class="desktop-download__section"
        >
          <h2>本版本更新</h2>
          <ul>
            <li
              v-for="note in release.notes"
              :key="note"
            >
              {{ note }}
            </li>
          </ul>
        </section>
        <section class="desktop-download__section">
          <h2>首次安装提示</h2>
          <ol>
            <li>安装包暂未使用代码签名证书，Windows 可能提示「Windows 已保护你的电脑」。</li>
            <li>确认文件名与上面的 SHA-256 一致后，点击「更多信息」，再点击「仍要运行」。</li>
            <li>已安装旧便携版的同事直接安装即可，登录状态与偏好设置会保留。</li>
            <li>开启了「智能应用控制」的电脑无法运行未签名版本，请先使用浏览器访问。</li>
          </ol>
        </section>
      </template>
      <template #actions>
        <p
          v-if="release && inDesktopShell"
          class="desktop-download__shell"
        >
          桌面客户端内不能直接下载安装包，请在浏览器中打开 <code>{{ pageUrl }}</code> 下载。
        </p>
        <el-button
          v-else-if="release"
          class="desktop-download__link"
          tag="a"
          type="primary"
          :href="downloadUrl"
          download
        >
          下载安装包
        </el-button>
        <el-button
          v-else-if="failed"
          type="primary"
          @click="load"
        >
          重试
        </el-button>
      </template>
    </yp-result-page>
  </main>
</template>

<style scoped>
.desktop-download__facts {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: var(--yp-space-2) var(--yp-space-4);
  margin: 0;
  text-align: left;
}

.desktop-download__facts dt {
  color: var(--el-text-color-secondary);
}

.desktop-download__facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.desktop-download__digest {
  font-size: 12px;
}

.desktop-download__link {
  text-decoration: none;
}

.desktop-download__shell {
  margin: 0;
  overflow-wrap: anywhere;
}

.desktop-download__section {
  margin-top: var(--yp-space-6);
  text-align: left;
}

.desktop-download__section h2 {
  margin: 0 0 var(--yp-space-2);
  font-size: 14px;
}

.desktop-download__section ul,
.desktop-download__section ol {
  margin: 0;
  padding-left: 20px;
}
</style>
