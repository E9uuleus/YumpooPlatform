import vue from '@vitejs/plugin-vue'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import { docsMarkdown } from './build/docs/markdown.ts'

const apiProxy = {
  '/api': {
    target: 'http://127.0.0.1:8100',
    changeOrigin: false,
  },
  '/version.json': {
    target: 'http://127.0.0.1:8100',
    changeOrigin: false,
    rewrite: () => '/actuator/info',
  },
}

// 与 deployment/windows/nginx/yumpoo-wecom.conf 的 CSP 保持一致，让 vite preview 与本地冒烟复现线上约束。
const productionCsp = "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"

// 部署验收用该 meta 核对线上前端字节来自被发布的提交。
function buildCommitMeta(commit: string): Plugin {
  return {
    name: 'yumpoo-build-commit',
    transformIndexHtml: () => [
      { tag: 'meta', attrs: { name: 'yumpoo-commit', content: commit }, injectTo: 'head' },
    ],
  }
}

export default defineConfig(({ mode }) => {
  const { YUMPOO_BUILD_COMMIT: commit = '' } = loadEnv(mode, '.', 'YUMPOO_BUILD_')
  return {
    plugins: [docsMarkdown(), vue(), buildCommitMeta(/^[0-9a-f]{40}$/u.test(commit) ? commit : 'unknown')],
    server: {
      host: '127.0.0.1',
      port: 18173,
      strictPort: true,
      proxy: apiProxy,
    },
    preview: {
      host: '127.0.0.1',
      port: 18174,
      strictPort: true,
      proxy: apiProxy,
      headers: { 'Content-Security-Policy': productionCsp },
    },
    test: {
      environment: 'happy-dom',
      include: ['src/**/*.spec.ts', 'build/**/*.spec.ts'],
      clearMocks: true,
      testTimeout: 20_000,
      // Source-constraint specs read stylesheets as ?raw text; normal CSS imports stay stubbed.
      css: { include: [/\.css\?raw$/u] },
    },
  }
})
