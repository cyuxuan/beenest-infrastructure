<script setup lang="ts">
withDefaults(defineProps<{ size?: number; glow?: boolean }>(), { size: 32, glow: false });
</script>

<template>
  <svg
    class="brand-mark"
    :class="{ 'is-glow': glow }"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    aria-hidden="true"
  >
    <path d="M16 1.6l12.3 7.1v14.6L16 30.4 3.7 23.3V8.7z" fill="#f5b32d" />
    <path d="M16 7.4l7 4v8.2l-7 4-7-4v-8.2z" fill="#0e1626" />
    <circle cx="16" cy="16" r="2.6" fill="#f5b32d" />
  </svg>
</template>
