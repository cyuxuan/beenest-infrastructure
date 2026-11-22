<script setup lang="ts">
import { ref } from 'vue';
import { useOverlay } from '@/lib/useOverlay';
import AppIcon from './AppIcon.vue';

const props = withDefaults(
  defineProps<{
    open: boolean;
    title?: string;
    subtitle?: string;
    width?: string;
    closeOnBackdrop?: boolean;
  }>(),
  { title: '', subtitle: '', width: '560px', closeOnBackdrop: true },
);

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update:open', value: boolean): void;
}>();

const panel = ref<HTMLElement | null>(null);

function close() {
  emit('close');
  emit('update:open', false);
}

useOverlay(() => props.open, panel, close);
</script>

<template>
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="open" class="overlay" @mousedown.self="closeOnBackdrop && close()" />
    </Transition>
    <Transition name="drawer">
      <aside
        v-if="open"
        ref="panel"
        class="drawer"
        :style="{ width }"
        role="dialog"
        aria-modal="true"
        :aria-label="title || '详情面板'"
        tabindex="-1"
      >
        <header class="drawer-head">
          <div class="drawer-head-main">
            <div v-if="title" class="drawer-title">{{ title }}</div>
            <div v-if="subtitle" class="drawer-sub">{{ subtitle }}</div>
          </div>
          <button class="icon-btn" type="button" aria-label="关闭" @click="close">
            <AppIcon name="close" />
          </button>
        </header>
        <div class="drawer-body">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="drawer-foot">
          <slot name="footer" :close="close" />
        </footer>
      </aside>
    </Transition>
  </Teleport>
</template>
