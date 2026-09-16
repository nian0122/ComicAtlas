import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import VueVirtualScroller from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
// TODO(FE-STYLE): 建立 styles/index.scss 统一加载 base/tokens/element-plus/utilities/animation，避免入口分散维护样式层次。
import './style.css'
import './styles/tokens.css'
import './styles/theme.scss'
import './styles/animation.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(VueVirtualScroller)
app.mount('#app')
