<script setup lang="ts">
import { toastState, dismissToast } from '@/lib/toast';
import type { IconName } from '@/lib/icons';
import AppIcon from './AppIcon.vue';

const ICONS: Record<string, IconName> = {
  success: 'check',
  error: 'alert',
  warn: 'alert',
  info: 'info',
};
</script>

<template>
  <Teleport to="body">
    <div class="toast-stack" aria-live="polite" aria-atomic="false">
      <TransitionGroup name="toast">
        <div
          v-for="item in toastState.items"
          :key="item.id"
          class="toast"
          :class="`is-${item.type}`"
          role="status"
          :style="{ '--toast-duration': item.type === 'error' ? '4600ms' : '3600ms' }"
        >
          <span class="toast-icon"><AppIcon :name="ICONS[item.type] || 'info'" :size="15" /></span>
          <span class="toast-text">{{ item.message }}</span>
          <button class="toast-close" type="button" aria-label="关闭提示" @click="dismissToast(item.id)">
            <AppIcon name="close" :size="13" />
          </button>
          <span class="toast-timer" />
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>
