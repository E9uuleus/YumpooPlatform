import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 18173,
    strictPort: true,
  },
  preview: {
    host: '127.0.0.1',
    port: 18174,
    strictPort: true,
  },
  test: {
    environment: 'happy-dom',
    include: ['src/**/*.spec.ts'],
    clearMocks: true,
  },
})
