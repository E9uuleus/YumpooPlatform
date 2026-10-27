import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  root: 'src/desktop-menu',
  base: './',
  publicDir: false,
  plugins: [vue()],
  build: {
    target: 'chrome140',
    outDir: '../../../../desktop/desktop-shell/dist/diagnostics',
    emptyOutDir: false,
    assetsInlineLimit: Number.MAX_SAFE_INTEGER,
    cssCodeSplit: false,
    license: { fileName: 'menu-licenses.json' },
    rollupOptions: {
      input: 'src/desktop-menu/menu.html',
      output: { entryFileNames: 'menu.js', assetFileNames: 'menu.[ext]' },
    },
  },
})
