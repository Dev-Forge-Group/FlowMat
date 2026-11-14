import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { dirname, resolve } from 'path'
import process from 'process'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return

          if (id.includes('@xyflow/react') || id.includes('html-to-image')) {
            return 'workspace-vendor'
          }

          if (id.includes('@stomp/stompjs')) {
            return 'sync-vendor'
          }

          if (id.includes('@tanstack/react-query') || id.includes('zustand')) {
            return 'state-vendor'
          }

          if (
            id.includes('react-router-dom') ||
            id.includes('react-dom') ||
            id.includes('\\react\\') ||
            id.includes('/react/')
          ) {
            return 'react-vendor'
          }
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        // A second copy on the same machine points at its own backend (docs/local-development.md).
        target: process.env.FLOWMAT_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
