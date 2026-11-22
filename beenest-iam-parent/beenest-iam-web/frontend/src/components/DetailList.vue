<script setup lang="ts">
withDefaults(
  defineProps<{
    rows: Array<{ label: string; value: unknown; mono?: boolean }>;
  }>(),
  {},
);

function display(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  return String(value);
}
</script>

<template>
  <div class="kv-list">
    <div v-for="row in rows" :key="row.label" class="kv-row">
      <span class="kv-key">{{ row.label }}</span>
      <span class="kv-val" :class="{ 'cell-mono': row.mono }">{{ display(row.value) }}</span>
    </div>
  </div>
</template>
