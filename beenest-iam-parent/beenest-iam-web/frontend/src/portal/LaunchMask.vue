<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue';
import type { PortalApp } from './types';

defineProps<{ app: PortalApp }>();
</script>

<template>
  <div class="launching" role="status" aria-live="polite">
    <div class="launch-panel">
      <span class="launch-spinner"><AppIcon name="hexagon" :size="26" /></span>
      <strong class="launch-title">正在进入 {{ app.appName }}</strong>
      <span class="launch-sub">正在打开应用,请稍候…</span>
    </div>
  </div>
</template>
