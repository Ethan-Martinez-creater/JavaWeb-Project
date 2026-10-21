import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { configureApiAuth } from './api/client'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import './style.css'

const pinia = createPinia()
const app = createApp(App)
app.use(pinia)

const auth = useAuthStore(pinia)
configureApiAuth({
    getToken: () => auth.accessToken,
    onUnauthorized: (requestToken) => {
        if (auth.accessToken !== requestToken) return
        const currentRoute = router.currentRoute.value
        const isProtectedPage = currentRoute.matched.some((record) => record.meta.roles?.length)
        auth.expireIfCurrent(requestToken)
        if (isProtectedPage) {
            void router.replace({ name: 'login', query: { redirect: currentRoute.fullPath } })
        }
    },
})

app.use(router)
app.use(ElementPlus)
app.mount('#app')
