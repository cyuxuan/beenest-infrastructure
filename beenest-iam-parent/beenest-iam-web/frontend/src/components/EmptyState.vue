<script setup lang="ts">
import AppIcon from './AppIcon.vue';
import type { IconName } from '@/lib/icons';

withDefaults(
  defineProps<{
    title: string;
    hint?: string;
    icon?: IconName;
  }>(),
  { hint: '', icon: 'inbox' },
);
</script>

<template>
  <div class="empty">
    <div class="empty-mark"><AppIcon :name="icon" :size="24" /></div>
    <div class="empty-title">{{ title }}</div>
    <div v-if="hint" class="empty-hint">{{ hint }}</div>
    <slot />
  </div>
</template>
