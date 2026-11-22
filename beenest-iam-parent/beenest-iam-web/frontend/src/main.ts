import { createApp } from 'vue';
import App from './App.vue';
import router from './router';
import { api, ApiError } from './lib/api';
import { applyManagedApps, applyMe } from './stores/auth';
import { notifyError } from './lib/toast';
import type { ManagedApp, Me } from './lib/types';
import './styles/console.css';

function renderFatal(message: string) {
  const root = document.getElementById('app');
  if (!root) return;
  root.classList.remove('is-booting');
  const box = document.createElement('div');
  box.className = 'boot-error';
  box.innerHTML = `
    <div class="boot-error-mark">!</div>
    <h1>无法进入管理控制台</h1>
    <p></p>
    <a class="btn btn-primary" href="/portal">返回应用门户</a>
  `;
  box.querySelector('p')!.textContent = message;
  root.replaceChildren(box);
}

async function bootstrap() {
  try {
    const me = await api<Me>('/api/v1/auth/me', { silent: true });
    applyMe(me);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return;
    renderFatal(error instanceof Error ? error.message : '服务暂时不可用,请稍后重试');
    return;
  }

  try {
    const apps = await api<ManagedApp[]>('/api/v1/admin/apps/managed', { silent: true });
    applyManagedApps(Array.isArray(apps) ? apps : []);
  } catch {
    applyManagedApps([]);
  }

  const app = createApp(App);
  app.config.errorHandler = (error) => {
    console.error(error);
    notifyError(error instanceof Error ? error.message : '页面出现异常');
  };
  app.use(router);
  await router.isReady();
  app.mount('#app');
  document.getElementById('app')?.classList.remove('is-booting');
}

bootstrap();
