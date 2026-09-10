import path from 'node:path'
import {fileURLToPath} from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers'
import Components from 'unplugin-vue-components/vite'
import {defineConfig, loadEnv} from 'vite'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig(({mode}) => {
    const env = loadEnv(mode, __dirname, '')

    return {
        plugins: [
            vue(),
            tailwindcss(),
            AutoImport({
                imports: ['vue', 'vue-router', 'pinia'],
                dirs: ['src/utils'],
                dts: 'src/types/auto-imports.d.ts',
                resolvers: [ElementPlusResolver()],
                eslintrc: {
                    enabled: true,
                },
            }),
            Components({
                dts: 'src/types/components.d.ts',
                resolvers: [ElementPlusResolver()],
            }),
        ],
        resolve: {
            alias: {
                '@': path.resolve(__dirname, 'src'),
            },
        },
        server: {
            host: '0.0.0.0',
            port: 5173,
            allowedHosts: ['app.pc'],
            proxy: {
                '/api': {
                    target: env.VITE_API_BASE_URL || 'http://localhost:8080',
                    changeOrigin: true,
                },
            },
        },
    }
})
