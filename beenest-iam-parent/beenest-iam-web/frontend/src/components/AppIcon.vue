<script setup lang="ts">
import { ICON_PATHS, type IconName } from '@/lib/icons';

withDefaults(
  defineProps<{
    name: IconName;
    size?: number;
    stroke?: number;
  }>(),
  { size: 16, stroke: 1.7 },
);

const paths = ICON_PATHS;
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
    v-html="paths[name]"
  />
</template>
