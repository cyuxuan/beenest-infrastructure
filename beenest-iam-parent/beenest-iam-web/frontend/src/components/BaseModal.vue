<script setup lang="ts">
import { ref } from 'vue';
import { useOverlay } from '@/lib/useOverlay';
import AppIcon from './AppIcon.vue';

const props = withDefaults(
  defineProps<{
    open: boolean;
    title?: string;
    subtitle?: string;
    size?: 'sm' | 'md' | 'lg' | 'xl';
    closeOnBackdrop?: boolean;
    hideClose?: boolean;
    bodyClass?: string;
  }>(),
  { title: '', subtitle: '', size: 'md', closeOnBackdrop: true, hideClose: false, bodyClass: '' },
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
      <div
        v-if="open"
        class="overlay"
        @mousedown.self="closeOnBackdrop && close()"
      />
    </Transition>
    <Transition name="modal">
      <div
        v-if="open"
        ref="panel"
        class="modal"
        :class="`modal-${size}`"
        role="dialog"
        aria-modal="true"
        :aria-label="title || '对话框'"
        tabindex="-1"
      >
        <header class="modal-head">
          <div class="modal-head-main">
            <div v-if="title" class="modal-title">{{ title }}</div>
            <div v-if="subtitle" class="modal-sub">{{ subtitle }}</div>
          </div>
          <button v-if="!hideClose" class="icon-btn" type="button" aria-label="关闭" @click="close">
            <AppIcon name="close" />
          </button>
        </header>
        <div class="modal-body" :class="bodyClass">
          <slot />
        </div>
        <footer v-if="$slots.footer" class="modal-foot">
          <slot name="footer" :close="close" />
        </footer>
      </div>
    </Transition>
  </Teleport>
</template>
