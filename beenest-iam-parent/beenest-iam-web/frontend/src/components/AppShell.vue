<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import {
  NAV_GROUPS,
  canAccessNav,
  firstAccessiblePath,
  navGroupByPath,
  navItemByPath,
} from '@/navigation';
import { auth, can } from '@/stores/auth';
import { api } from '@/lib/api';
import { copyText } from '@/lib/format';
import { toast } from '@/lib/toast';
import AppIcon from './AppIcon.vue';
import BrandMark from './BrandMark.vue';
import CommandPalette from './CommandPalette.vue';

const route = useRoute();
const router = useRouter();
const COLLAPSE_KEY = 'iam.console.sidebar.collapsed';

const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1');
const mobileOpen = ref(false);
const commandOpen = ref(false);
const userMenuOpen = ref(false);
const isMobile = ref(window.innerWidth <= 960);

const groups = computed(() =>
  NAV_GROUPS.map((group) => ({ ...group, items: group.items.filter(canAccessNav) })).filter(
    (group) => group.items.length > 0,
  ),
);

const currentItem = computed(() => navItemByPath(route.path));
const currentGroup = computed(() => navGroupByPath(route.path));
const displayName = computed(() => auth.me?.nickname || auth.me?.username || '管理员');
const avatarText = computed(() => displayName.value.trim().slice(0, 1).toUpperCase());
const permCount = computed(() => auth.perms.size);
const canViewRealms = computed(() => can('realm:view'));
const hasAnyPage = computed(() => Boolean(firstAccessiblePath()));

function toggleCollapse() {
  if (isMobile.value) {
    mobileOpen.value = !mobileOpen.value;
    return;
  }
  collapsed.value = !collapsed.value;
  localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0');
}

function onResize() {
  isMobile.value = window.innerWidth <= 960;
  if (!isMobile.value) mobileOpen.value = false;
}

function onDocumentClick() {
  userMenuOpen.value = false;
}

function onGlobalKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null;
  const typing = target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable);
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault();
    if (!document.querySelector('.modal, .drawer')) commandOpen.value = true;
    return;
  }
  if (event.key === '/' && !typing && !commandOpen.value) {
    event.preventDefault();
    commandOpen.value = true;
  }
}

async function logout() {
  try {
    await api('/api/v1/auth/logout', { method: 'POST', silent: true });
  } catch {
    /* 会话可能已失效,继续离开 */
  }
  location.replace('/login');
}

function refreshPage() {
  userMenuOpen.value = false;
  window.location.reload();
}

async function copyUsername() {
  userMenuOpen.value = false;
  if (auth.me?.username) await copyText(auth.me.username, '登录名已复制');
}

function openPortal() {
  userMenuOpen.value = false;
  window.location.href = '/portal';
}

function goRealms() {
  if (!canViewRealms.value) {
    toast('当前账号没有身份域查看权限', 'warn');
    return;
  }
  mobileOpen.value = false;
  router.push('/realms');
}

watch(
  () => route.path,
  () => {
    mobileOpen.value = false;
    userMenuOpen.value = false;
    commandOpen.value = false;
  },
);

onMounted(() => {
  document.addEventListener('click', onDocumentClick);
  document.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('resize', onResize);
});

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick);
  document.removeEventListener('keydown', onGlobalKeydown);
  window.removeEventListener('resize', onResize);
});
</script>

<template>
  <div class="shell" :class="{ 'is-collapsed': collapsed && !isMobile }">
    <aside class="sidebar" :class="{ 'is-collapsed': collapsed && !isMobile, 'is-mobile-open': mobileOpen }">
      <div class="sidebar-brand">
        <RouterLink class="brand-link" to="/" aria-label="管理控制台首页">
          <BrandMark :size="30" />
          <span class="brand-copy">
            <span class="brand-name">蜂巢身份</span>
            <span class="brand-sub">Admin Console</span>
          </span>
        </RouterLink>
        <button class="sidebar-toggle" type="button" aria-label="收起侧栏" @click="toggleCollapse">
          <AppIcon name="panelLeft" :size="16" />
        </button>
      </div>

      <button class="sidebar-realm" type="button" :title="canViewRealms ? '管理身份域' : '当前身份域'" @click="goRealms">
        <span class="realm-glyph"><AppIcon name="realm" :size="15" /></span>
        <span class="realm-copy">
          <span class="realm-label">当前身份域</span>
          <strong>{{ auth.realmKey }}</strong>
        </span>
        <AppIcon v-if="canViewRealms" class="realm-arrow" name="chevronRight" :size="14" />
      </button>

      <nav class="sidebar-nav" aria-label="主导航">
        <div v-for="group in groups" :key="group.key" class="nav-group">
          <div class="nav-group-head">
            <span class="nav-group-title">{{ group.label }}</span>
            <span class="nav-group-hint">{{ group.hint }}</span>
          </div>
          <RouterLink
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            :class="{ 'is-active': route.path === item.path }"
            :title="item.label"
          >
            <span class="nav-icon"><AppIcon :name="item.icon" :size="17" /></span>
            <span class="nav-label">{{ item.label }}</span>
            <span v-if="route.path === item.path" class="nav-active-dot" aria-hidden="true" />
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-foot">
        <div class="sidebar-status">
          <span class="status-pulse" />
          <span class="status-copy">安全会话 · 审计在线</span>
        </div>
        <div class="sidebar-version">Beenest IAM <span>v2026.10</span></div>
      </div>
    </aside>

    <div v-if="mobileOpen" class="sidebar-scrim" @click="mobileOpen = false" />

    <div class="main">
      <header class="topbar">
        <button class="icon-btn topbar-toggle" type="button" aria-label="切换导航" @click="toggleCollapse">
          <AppIcon name="menu" :size="18" />
        </button>

        <div class="crumb">
          <span class="crumb-group">{{ currentGroup?.label || '控制台' }}</span>
          <span class="crumb-sep" aria-hidden="true">/</span>
          <strong class="crumb-current">{{ currentItem?.label || '无访问权限' }}</strong>
        </div>

        <div class="topbar-space" />

        <button class="command-trigger" type="button" aria-label="打开命令面板" @click="commandOpen = true">
          <AppIcon name="search" :size="15" />
          <span class="command-trigger-text">搜索页面与能力</span>
          <kbd>⌘K</kbd>
        </button>

        <button
          class="realm-chip"
          type="button"
          :title="canViewRealms ? '身份域设置' : '当前身份域'"
          @click="goRealms"
        >
          <span class="realm-pulse" />
          <span class="realm-chip-label">身份域</span>
          <strong>{{ auth.realmKey }}</strong>
        </button>

        <div class="user-menu">
          <button
            class="user-trigger"
            type="button"
            :aria-expanded="userMenuOpen"
            aria-haspopup="menu"
            @click.stop="userMenuOpen = !userMenuOpen"
          >
            <span class="avatar">{{ avatarText }}</span>
            <span class="user-meta">
              <span class="user-name">{{ displayName }}</span>
              <span class="user-sub">{{ permCount }} 项权限</span>
            </span>
            <AppIcon name="chevronDown" :size="14" class="user-chevron" :class="{ 'is-open': userMenuOpen }" />
          </button>

          <Transition name="menu">
            <div v-if="userMenuOpen" class="user-dropdown" role="menu" @click.stop>
              <div class="user-card">
                <span class="avatar is-lg">{{ avatarText }}</span>
                <div class="user-card-copy">
                  <strong>{{ displayName }}</strong>
                  <span>{{ auth.me?.username }} · {{ auth.me?.realmKey }}</span>
                </div>
              </div>
              <div class="dropdown-divider" />
              <button class="dropdown-item" type="button" role="menuitem" @click="refreshPage">
                <AppIcon name="refresh" :size="15" /><span>刷新当前页</span>
              </button>
              <button class="dropdown-item" type="button" role="menuitem" @click="openPortal">
                <AppIcon name="globe" :size="15" /><span>前往应用门户</span>
              </button>
              <button class="dropdown-item" type="button" role="menuitem" @click="copyUsername">
                <AppIcon name="copy" :size="15" /><span>复制登录名</span>
              </button>
              <div class="dropdown-divider" />
              <div class="dropdown-note">权限变更将在下一次认证时生效</div>
              <button class="dropdown-item is-danger" type="button" role="menuitem" @click="logout">
                <AppIcon name="logout" :size="15" /><span>退出登录</span>
              </button>
            </div>
          </Transition>
        </div>
      </header>

      <main class="view-host">
        <div class="ambient-grid" aria-hidden="true" />
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" :key="route.path" />
          </Transition>
        </RouterView>
        <footer v-if="hasAnyPage" class="console-foot">
          <span>Beenest IAM · 管理控制面</span>
          <span class="console-foot-sep">/</span>
          <span>{{ currentGroup?.label }}</span>
          <span class="console-foot-space" />
          <span>所有操作均写入审计日志</span>
        </footer>
      </main>
    </div>

    <CommandPalette :open="commandOpen" @close="commandOpen = false" />
  </div>
</template>
