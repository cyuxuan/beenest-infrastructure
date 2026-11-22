<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import { fmtClock, fmtDate, greeting } from './lib';
import type { PortalMe } from './types';

const { me, subtitle, summary, keyword } = defineProps<{
  me: PortalMe;
  subtitle: string;
  summary: string;
  keyword: string;
}>();
const emit = defineEmits<{ 'update:keyword': [string] }>();

const now = ref(new Date());
const searchBox = ref<HTMLInputElement | null>(null);
const displayName = computed(() => me.nickname || me.username || '用户');

const clockTimer = window.setInterval(() => {
  now.value = new Date();
}, 1000);
onUnmounted(() => window.clearInterval(clockTimer));

function onInput(event: Event) {
  emit('update:keyword', (event.target as HTMLInputElement).value);
}

function onSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && (event.target as HTMLInputElement).value) {
    event.preventDefault();
    emit('update:keyword', '');
  }
}

/** "/" 快捷聚焦:输入态不抢按键,避免打断搜索本身。 */
function onGlobalKeydown(event: KeyboardEvent) {
  if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.altKey) return;
  const active = document.activeElement;
  const tag = active ? active.tagName : '';
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (active && (active as HTMLElement).isContentEditable)) return;
  if (event.key === '/') {
    event.preventDefault();
    searchBox.value?.focus();
  }
}

onMounted(() => document.addEventListener('keydown', onGlobalKeydown));
onUnmounted(() => document.removeEventListener('keydown', onGlobalKeydown));
</script>

<template>
  <section class="hero">
    <div class="hero-inner">
      <div class="hero-copy">
        <span class="hero-kicker">
          <span class="status-pulse" />
          <span>IDENTITY PORTAL · 会话已建立</span>
        </span>
        <h1>
          {{ greeting(now) }}，<span class="hero-name">{{ displayName }}</span>
        </h1>
        <p class="hero-sub">{{ subtitle }}</p>
        <div class="hero-meta">
          <span class="hero-date">{{ fmtDate(now) }}</span>
          <span class="hero-sep" />
          <span class="hero-clock">{{ fmtClock(now) }}</span>
          <span class="hero-sep" />
          <span class="hero-realm">身份域 <strong>{{ me.realmKey || '—' }}</strong></span>
        </div>
      </div>
      <div class="hero-art" aria-hidden="true">
        <span class="hero-hex hero-hex-a" />
        <span class="hero-hex hero-hex-b" />
        <span class="hero-hex hero-hex-c" />
        <span class="hero-orbit" />
        <span class="hero-core"><AppIcon name="hexagon" :size="34" /></span>
      </div>
    </div>
    <div class="hero-search-row">
      <label class="hero-search">
        <span class="icon hero-search-icon"><AppIcon name="search" :size="16" /></span>
        <input
          ref="searchBox"
          type="search"
          placeholder="搜索应用名称或编码…"
          aria-label="搜索应用"
          autocomplete="off"
          spellcheck="false"
          :value="keyword"
          @input="onInput"
          @keydown="onSearchKeydown"
        />
        <kbd>/</kbd>
      </label>
      <span class="hero-summary" aria-live="polite">{{ summary }}</span>
    </div>
  </section>
</template>
