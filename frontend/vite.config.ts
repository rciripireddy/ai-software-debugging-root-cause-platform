import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import { CLOUDFLARE_PAGES_FILES } from './deploy/cloudflarePages.ts'

// During `npm run dev`, backend calls are proxied to services running on localhost.
// In Docker, the nginx configuration in nginx.conf provides the same routes.
function backendProxy(prefix: string, target: string) {
  return {
    target,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(new RegExp(`^${prefix}`), ''),
  }
}

// The demo build is the public preview on Cloudflare Pages; its headers file is part of that build only.
function cloudflarePagesFiles(): Plugin {
  return {
    name: 'cloudflare-pages-files',
    generateBundle() {
      for (const [fileName, source] of Object.entries(CLOUDFLARE_PAGES_FILES)) {
        this.emitFile({ type: 'asset', fileName, source })
      }
    },
  }
}

export default defineConfig(({ mode }) => {
  const demo = mode === 'demo'
  return {
    plugins: demo ? [react(), cloudflarePagesFiles()] : [react()],
    // The demo build contains no network code at all: the module preload polyfill (fetch-based,
    // only used by browsers without <link rel="modulepreload">) is left out too.
    build: demo ? { modulePreload: { polyfill: false } } : {},
    resolve: {
      // Live builds load a stub instead of the demo dataset, so the dataset is not bundled at all.
      alias: demo
        ? []
        : [{ find: /^\.\/demo\/loadDemoData$/, replacement: '/src/demo/noDemoData.ts' }],
    },
    server: {
      port: 5173,
      proxy: {
        '/api/ingestion': backendProxy('/api/ingestion', 'http://localhost:8081'),
        '/api/incident': backendProxy('/api/incident', 'http://localhost:8082'),
        '/api/analysis': backendProxy('/api/analysis', 'http://localhost:8000'),
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
    },
  }
})
