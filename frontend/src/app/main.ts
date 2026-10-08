import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { installAppPlugins, installState } from './providers'
import './styles/index.scss'

const app = createApp(App)
installState(app)
app.use(router)
installAppPlugins(app)
app.mount('#app')
