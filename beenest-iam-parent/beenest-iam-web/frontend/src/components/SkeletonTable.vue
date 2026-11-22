<script setup lang="ts">
withDefaults(defineProps<{ rows?: number; columns?: number }>(), { rows: 6, columns: 5 });
</script>

<template>
  <div class="skeleton-table" aria-hidden="true">
    <div v-for="row in rows" :key="row" class="skeleton-row">
      <span
        v-for="column in columns"
        :key="column"
        class="skeleton"
        :style="{ width: `${52 + ((row * 13 + column * 17) % 40)}%` }"
      />
    </div>
  </div>
</template>
