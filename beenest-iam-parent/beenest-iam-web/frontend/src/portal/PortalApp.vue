<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { api, errorMessage } from '@/lib/api';
import { toast } from '@/lib/toast';
import AppIcon from '@/components/AppIcon.vue';
import ToastHost from '@/components/ToastHost.vue';
import PortalTopbar from './PortalTopbar.vue';
import PortalHero from './PortalHero.vue';
import PortalBody from './PortalBody.vue';
import LaunchMask from './LaunchMask.vue';
import {
  categoryCountOf,
  filterApps,
  groupByCategory,
  heroSubtitle,
  pushRecent,
  readRecent,
  summaryText,
} from './lib';
import type { PortalApp, PortalMe } from './types';

type Phase = 'booting' | 'ready' | 'error';

const phase = ref<Phase>('booting');
const loadError = ref('');
const me = ref<PortalMe | null>(null);
const apps = ref<PortalApp[]>([]);
const keyword = ref('');
const recentCodes = ref<string[]>([]);
const launching = ref<PortalApp | null>(null);

const isAdmin = computed(() => {
  const authorities = new Set(me.value?.authorities || []);
  return authorities.has('user:view') || authorities.has('dashboard:view');
});
const matched = computed(() => filterApps(apps.value, keyword.value));
const categoryCount = computed(() => categoryCountOf(apps.value));
const summary = computed(() =>
  summaryText(apps.value.length, categoryCount.value, keyword.value, matched.value.length),
);
const subtitle = computed(() => heroSubtitle(apps.value.length, categoryCount.value));
const groups = computed(() => groupByCategory(matched.value));
const recentApps = computed(() =>
  keyword.value.trim()
    ? []
    : recentCodes.value
        .map((code) => apps.value.find((item) => item.appCode === code))
        .filter((item): item is PortalApp => Boolean(item)),
);

async function boot() {
  try {
    const [profile, list] = await Promise.all([
      api<PortalMe>('/api/v1/auth/me'),
      api<PortalApp[]>('/api/v1/launchpad/apps'),
    ]);
    me.value = profile;
    apps.value = list || [];
    recentCodes.value = readRecent();
    phase.value = 'ready';
    reportLaunchError();
  } catch (error) {
    loadError.value = errorMessage(error);
    phase.value = 'error';
  }
}

/** 进入应用:目标由服务端判定并 302,门户只负责导航。 */
function launch(item: PortalApp) {
  launching.value = item;
  recentCodes.value = pushRecent(item.appCode);
  location.assign(`/portal/launch/${encodeURIComponent(item.appCode)}`);
}

/** 启动失败时服务端把原因带回门户页,提示一次并从地址栏抹掉。 */
function reportLaunchError() {
  const params = new URLSearchParams(location.search);
  const message = params.get('launchError');
  if (!message) return;
  toast(message, 'error');
  params.delete('launchError');
  params.delete('launchApp');
  const query = params.toString();
  history.replaceState(null, '', location.pathname + (query ? `?${query}` : ''));
}

/** 退出:表单 POST 走 Spring Security 登出入口(CSRF 隐藏域随表单提交)。 */
function logout() {
  const form = document.createElement('form');
  form.method = 'post';
  form.action = '/logout';
  // 表单字段名取令牌的参数名,不是请求头名:提交后由 CsrfFilter 按参数名校验,
  // 写成 X-XSRF-TOKEN 只会让校验读不到令牌,退出被拒后落到 403 错误页
  const parameter = document.querySelector('meta[name="_csrf_parameter"]')?.getAttribute('content') || '_csrf';
  const token = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || cookieToken();
  if (token) {
    const input = document.createElement('input');
    input.type = 'hidden';
    input.name = parameter;
    input.value = token;
    form.appendChild(input);
  }
  document.body.appendChild(form);
  form.submit();
}

/** 页面没有内嵌令牌时从 CSRF Cookie 取,避免提交一个空令牌。 */
function cookieToken(): string {
  const matched = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return matched ? decodeURIComponent(matched[1]) : '';
}

/** 加载失败后的重试:整页刷新,重新走启动流程。 */
function reload() {
  location.reload();
}

/** 外壳 #app 携带 is-booting 提供首屏底色,数据就绪后交还给门户布局。 */
watch(phase, (value) => {
  document.getElementById('app')?.classList.toggle('is-booting', value === 'booting');
});

onMounted(boot);
</script>

<template>
  <div v-if="phase === 'booting'" class="boot" role="status" aria-live="polite">
    <div class="boot-mark-wrap">
      <svg class="boot-mark" viewBox="0 0 32 32" aria-hidden="true">
        <defs>
          <linearGradient id="boot-gold" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#ffd777" />
            <stop offset="1" stop-color="#e09a12" />
          </linearGradient>
        </defs>
        <path d="M16 1.6l12.3 7.1v14.6L16 30.4 3.7 23.3V8.7z" fill="url(#boot-gold)" />
        <path d="M16 7.4l7 4v8.2l-7 4-7-4v-8.2z" fill="#0e1626" />
        <circle cx="16" cy="16" r="2.6" fill="url(#boot-gold)" />
      </svg>
    </div>
    <p class="boot-text">正在载入应用门户</p>
  </div>

  <template v-else-if="phase === 'ready' && me">
    <PortalTopbar :me="me" :is-admin="isAdmin" @logout="logout" />
    <main class="portal-main">
      <PortalHero :me="me" :subtitle="subtitle" :summary="summary" v-model:keyword="keyword" />
      <PortalBody
        :groups="groups"
        :recent="recentApps"
        :keyword="keyword"
        :total="apps.length"
        @clear-keyword="keyword = ''"
        @launch="launch"
      />
    </main>
    <footer class="portal-foot">
      <span class="foot-dot" />
      <span class="foot-brand">蜂巢身份 · 统一身份管理平台</span>
      <span class="foot-sep">/</span>
      <span>应用门户</span>
      <span class="foot-space" />
      <span>© {{ new Date().getFullYear() }} Beenest IAM</span>
    </footer>
  </template>

  <div v-else class="boot-error">
    <span class="boot-error-icon"><AppIcon name="alert" :size="24" /></span>
    <h1>门户加载失败</h1>
    <p>{{ loadError && loadError !== 'Failed to fetch' ? loadError : '无法连接到身份服务,请检查网络或稍后重试。' }}</p>
    <button class="btn btn-primary" type="button" @click="reload">重新加载</button>
  </div>

  <LaunchMask v-if="launching" :app="launching" />
  <ToastHost />
</template>
