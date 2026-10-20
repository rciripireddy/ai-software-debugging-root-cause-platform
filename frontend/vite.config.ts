import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// During `npm run dev`, backend calls are proxied to services running on localhost.
// In Docker, the nginx configuration in nginx.conf provides the same routes.
function backendProxy(prefix: string, target: string) {
  return {
    target,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(new RegExp(`^${prefix}`), ''),
  }
}

export default defineConfig({
  plugins: [react()],
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
})
