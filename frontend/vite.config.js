import { defineConfig } from 'vite';

export default defineConfig({
  optimizeDeps: {
    include: ['@volcengine/rtc']
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});


