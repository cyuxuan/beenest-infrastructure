<script setup lang="ts">
import { computed } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import { initial } from '@/lib/format';
import type { PortalMe } from './types';

const { me, isAdmin } = defineProps<{ me: PortalMe; isAdmin: boolean }>();
const emit = defineEmits<{ logout: [] }>();

const displayName = computed(() => me.nickname || me.username || '用户');
</script>

<template>
  <header class="topbar">
    <a class="brand" href="/portal" aria-label="应用门户首页">
      <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
        <defs>
          <linearGradient id="portal-gold" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#ffd777" />
            <stop offset="1" stop-color="#e09a12" />
          </linearGradient>
        </defs>
        <path d="M16 1.6l12.3 7.1v14.6L16 30.4 3.7 23.3V8.7z" fill="url(#portal-gold)" />
        <path d="M16 7.4l7 4v8.2l-7 4-7-4v-8.2z" fill="#0e1626" />
        <circle cx="16" cy="16" r="2.6" fill="url(#portal-gold)" />
      </svg>
      <span class="brand-copy">
        <span class="brand-name">蜂巢身份</span>
        <span class="brand-sub">Application Portal</span>
      </span>
    </a>

    <span class="realm-chip" title="当前身份域">
      <span class="realm-pulse" />
      <span class="realm-chip-label">身份域</span>
      <strong>{{ me.realmKey || '—' }}</strong>
    </span>

    <span class="topbar-space" />

    <a class="topbar-link" href="/account" title="管理我的账户">
      <span class="icon"><AppIcon name="user" :size="15" /></span>
      <span class="topbar-link-text">我的账户</span>
    </a>

    <a v-if="isAdmin" class="topbar-link" href="/console" title="进入管理控制台">
      <span class="icon"><AppIcon name="grid" :size="15" /></span>
      <span class="topbar-link-text">管理控制台</span>
    </a>

    <span class="topbar-sep" />

    <div class="user" :title="displayName">
      <span class="avatar">{{ initial(displayName) }}</span>
      <span class="user-meta">
        <span class="user-name">{{ displayName }}</span>
        <span class="user-sub">{{ me.nickname && me.username ? me.username : '统一身份账号' }}</span>
      </span>
    </div>

    <button
      class="btn btn-logout"
      type="button"
      title="退出登录"
      aria-label="退出登录"
      @click="emit('logout')"
    >
      <span class="icon"><AppIcon name="logout" :size="15" /></span>
      <span class="btn-label">退出</span>
    </button>
  </header>
</template>
