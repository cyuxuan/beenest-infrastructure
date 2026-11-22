<script setup lang="ts">
import { computed } from 'vue';
import { confirmState, settleConfirm } from '@/lib/confirm';
import AppIcon from './AppIcon.vue';
import BaseModal from './BaseModal.vue';

const options = computed(() => confirmState.options);
const open = computed(() => Boolean(confirmState.options));
</script>

<template>
  <BaseModal
    :open="open"
    :title="options?.title || '请确认'"
    size="sm"
    :close-on-backdrop="false"
    @close="settleConfirm(false)"
  >
    <div v-if="options" class="confirm-body">
      <div class="confirm-icon" :class="options.danger ? 'is-danger' : 'is-gold'">
        <AppIcon :name="options.danger ? 'alert' : 'info'" :size="21" />
      </div>
      <div class="confirm-copy">
        <p class="confirm-message">{{ options.message }}</p>
        <p v-if="options.detail" class="confirm-detail">{{ options.detail }}</p>
      </div>
    </div>
    <template #footer>
      <button class="btn btn-ghost" type="button" data-autofocus @click="settleConfirm(false)">
        {{ options?.cancelText || '取消' }}
      </button>
      <button
        class="btn"
        :class="options?.danger ? 'btn-danger' : 'btn-primary'"
        type="button"
        @click="settleConfirm(true)"
      >
        {{ options?.confirmText || '确认执行' }}
      </button>
    </template>
  </BaseModal>
</template>
