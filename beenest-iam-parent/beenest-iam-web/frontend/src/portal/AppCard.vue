<script setup lang="ts">
import { computed, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import { initial, safeHttpUrl } from '@/lib/format';
import type { PortalApp } from './types';

const { app } = defineProps<{ app: PortalApp }>();
const emit = defineEmits<{ launch: [PortalApp] }>();

const imageFailed = ref(false);
const iconSrc = safeHttpUrl(app.iconUrl);

/**
 * 不可进入角标。
 * 卡片仍可点:点击后由服务端判定并带回原因,这里先给出视觉提示,
 * 免得用户点进去才发现进不去。
 */
const badge = computed(() => {
  if (app.launchable !== false) return null;
  const label = '不可进入';
  return { label, title: app.launchHint || label };
});
</script>

<template>
  <button
    class="app-card"
    type="button"
    :title="app.appName"
    :aria-label="`进入 ${app.appName}`"
    @click="emit('launch', app)"
  >
    <span class="app-card-top">
      <span v-if="iconSrc && !imageFailed" class="app-icon has-image">
        <img :src="iconSrc" alt="" @error="imageFailed = true" />
      </span>
      <span v-else class="app-icon">{{ initial(app.appName) }}</span>
      <span class="app-arrow"><AppIcon name="arrowRight" :size="14" /></span>
    </span>
    <span class="app-name">{{ app.appName }}</span>
    <span class="app-desc">{{ app.description || '暂无应用描述' }}</span>
    <span class="app-tags">
      <span class="tag is-gold">{{ app.ssoMode || 'OIDC' }}</span>
      <span v-if="app.category" class="tag">{{ app.category }}</span>
      <span v-if="app.menuMode === 'APP'" class="tag">菜单自管</span>
      <span v-if="badge" class="tag is-warn" :title="badge.title">{{ badge.label }}</span>
    </span>
  </button>
</template>
