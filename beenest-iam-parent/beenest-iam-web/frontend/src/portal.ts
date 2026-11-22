import { createApp } from 'vue';
import PortalApp from './portal/PortalApp.vue';
import './styles/portal.css';

/**
 * 门户入口:挂载点与服务端外壳 portal/index.html 约定。
 * 令牌默认值在 portal.css,运行时由主题包 tokens.css 按域覆盖(外壳 link 顺序保证)。
 */
createApp(PortalApp).mount('#app');
