import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import 'vue-virtual-scroller/index.css'
import './styles/index.css'
createApp(App).use(createPinia()).use(router).mount('#app')
