import vue from '@vitejs/plugin-vue'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

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
    plugins: [vue(), buildCommitMeta(/^[0-9a-f]{40}$/u.test(commit) ? commit : 'unknown')],
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
    },
    test: {
      environment: 'happy-dom',
      include: ['src/**/*.spec.ts'],
      clearMocks: true,
      testTimeout: 20_000,
      // Source-constraint specs read stylesheets as ?raw text; normal CSS imports stay stubbed.
      css: { include: [/\.css\?raw$/u] },
    },
  }
})
