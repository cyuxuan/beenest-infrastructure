<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string;
    desc?: string;
    eyebrow?: string;
  }>(),
  { desc: '', eyebrow: '控制面 / CONTROL PLANE' },
);
</script>

<template>
  <header class="page-head">
    <div class="page-head-main">
      <div class="page-kicker">
        <span class="kicker-hex" aria-hidden="true" />
        <span>{{ eyebrow }}</span>
        <slot name="meta" />
      </div>
      <h1 class="page-title">{{ title }}</h1>
      <p v-if="desc" class="page-desc">{{ desc }}</p>
    </div>
    <div v-if="$slots.actions" class="page-head-actions">
      <slot name="actions" />
    </div>
    <div class="page-head-orbit" aria-hidden="true">
      <span class="orbit-hex orbit-hex-a" />
      <span class="orbit-hex orbit-hex-b" />
      <span class="orbit-line" />
    </div>
    <slot name="under" />
  </header>
</template>
