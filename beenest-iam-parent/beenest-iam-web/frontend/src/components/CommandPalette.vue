<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { NAV_ITEMS, canAccessNav } from '@/navigation';
import { useOverlay } from '@/lib/useOverlay';
import AppIcon from './AppIcon.vue';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

const router = useRouter();
const panel = ref<HTMLElement | null>(null);
const input = ref<HTMLInputElement | null>(null);
const keyword = ref('');
const activeIndex = ref(0);
const RECENT_KEY = 'iam.console.recent-pages';

const recentPaths = ref<string[]>(readRecent());

function readRecent(): string[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.filter((item) => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

function remember(path: string) {
  const next = [path, ...recentPaths.value.filter((item) => item !== path)].slice(0, 5);
  recentPaths.value = next;
  localStorage.setItem(RECENT_KEY, JSON.stringify(next));
}

const accessible = computed(() => NAV_ITEMS.filter(canAccessNav));

const results = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) {
    const recent = recentPaths.value
      .map((path) => accessible.value.find((item) => item.path === path))
      .filter(Boolean)
      .slice(0, 5);
    const rest = accessible.value.filter((item) => !recent.some((entry) => entry?.path === item.path));
    return [...recent, ...rest].slice(0, 10) as typeof NAV_ITEMS;
  }
  return accessible.value
    .filter((item) =>
      [item.label, item.desc, item.group, item.path].some((field) => field.toLowerCase().includes(text)),
    )
    .slice(0, 10);
});

watch(keyword, () => {
  activeIndex.value = 0;
});

watch(
  () => props.open,
  async (open) => {
    if (!open) return;
    keyword.value = '';
    activeIndex.value = 0;
    recentPaths.value = readRecent();
    await nextTick();
    input.value?.focus();
  },
);

function close() {
  emit('close');
}

useOverlay(() => props.open, panel, close);

function move(delta: number) {
  if (!results.value.length) return;
  activeIndex.value = (activeIndex.value + delta + results.value.length) % results.value.length;
  nextTick(() => {
    panel.value?.querySelector<HTMLElement>('.command-item.is-active')?.scrollIntoView({ block: 'nearest' });
  });
}

function choose(index = activeIndex.value) {
  const item = results.value[index];
  if (!item) return;
  remember(item.path);
  close();
  router.push(item.path);
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    move(1);
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    move(-1);
  } else if (event.key === 'Enter') {
    event.preventDefault();
    choose();
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="open" class="overlay command-overlay" @mousedown.self="close" />
    </Transition>
    <Transition name="command">
      <div
        v-if="open"
        ref="panel"
        class="command-palette"
        role="dialog"
        aria-modal="true"
        aria-label="命令面板"
      >
        <div class="command-search">
          <AppIcon name="search" :size="18" />
          <input
            ref="input"
            v-model="keyword"
            type="text"
            placeholder="搜索页面、能力或权限域…"
            aria-label="搜索页面"
            autocomplete="off"
            @keydown="onKeydown"
          />
          <kbd>ESC</kbd>
        </div>
        <div class="command-body">
          <div class="command-label">
            {{ keyword ? '搜索结果' : '快速导航' }}
            <span>{{ results.length }} 项</span>
          </div>
          <button
            v-for="(item, index) in results"
            :key="item.path"
            class="command-item"
            :class="{ 'is-active': index === activeIndex }"
            type="button"
            @mouseenter="activeIndex = index"
            @click="choose(index)"
          >
            <span class="command-icon"><AppIcon :name="item.icon" :size="16" /></span>
            <span class="command-copy">
              <span class="command-title">{{ item.label }}</span>
              <span class="command-desc">{{ item.desc }}</span>
            </span>
            <span class="command-group">{{ item.group }}</span>
            <AppIcon name="arrowRight" :size="14" />
          </button>
          <div v-if="!results.length" class="command-empty">
            没有匹配的页面,试试「用户」「客户端」「审计」。
          </div>
        </div>
        <div class="command-foot">
          <span><kbd>↑</kbd><kbd>↓</kbd> 导航</span>
          <span><kbd>↵</kbd> 打开</span>
          <span class="command-foot-hint">仅展示当前账号有权访问的管理域</span>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
