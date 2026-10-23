import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
    plugins: [vue()],
    server: {
        proxy: {
            '/api/v1': {
                target: process.env.EVENTFLOW_API_PROXY ?? 'http://127.0.0.1:8080',
                changeOrigin: false,
            },
            '/actuator/health': {
                target: process.env.EVENTFLOW_API_PROXY ?? 'http://127.0.0.1:8080',
                changeOrigin: false,
            },
        },
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts'],
    },
})
