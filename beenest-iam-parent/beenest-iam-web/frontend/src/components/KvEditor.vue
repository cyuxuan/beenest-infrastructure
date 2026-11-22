<script setup lang="ts">
import { ref } from 'vue';
import AppIcon from './AppIcon.vue';

const props = withDefaults(
  defineProps<{
    modelValue: Record<string, string>;
    keyPlaceholder?: string;
    valuePlaceholder?: string;
    disabled?: boolean;
  }>(),
  { keyPlaceholder: '键', valuePlaceholder: '值', disabled: false },
);

const emit = defineEmits<{ (e: 'update:modelValue', value: Record<string, string>): void }>();

interface Row {
  key: string;
  value: string;
}

const rows = ref<Row[]>(
  Object.entries(props.modelValue || {}).map(([key, value]) => ({ key, value: String(value ?? '') })),
);
if (!rows.value.length) rows.value.push({ key: '', value: '' });

function sync() {
  const result: Record<string, string> = {};
  for (const row of rows.value) {
    const key = row.key.trim();
    if (key) result[key] = row.value;
  }
  emit('update:modelValue', result);
}

function addRow() {
  rows.value.push({ key: '', value: '' });
}

function removeRow(index: number) {
  rows.value.splice(index, 1);
  if (!rows.value.length) rows.value.push({ key: '', value: '' });
  sync();
}
</script>

<template>
  <div class="kv-editor">
    <div class="kv-editor-head">
      <span>配置键</span>
      <span>配置值</span>
      <span />
    </div>
    <div v-for="(row, index) in rows" :key="index" class="kv-editor-row">
      <input
        v-model="row.key"
        class="input is-mono"
        :placeholder="keyPlaceholder"
        :disabled="disabled"
        @input="sync"
      />
      <input
        v-model="row.value"
        class="input is-mono"
        :placeholder="valuePlaceholder"
        :disabled="disabled"
        @input="sync"
      />
      <button
        class="icon-btn is-danger"
        type="button"
        aria-label="删除这一项"
        :disabled="disabled"
        @click="removeRow(index)"
      >
        <AppIcon name="trash" :size="14" />
      </button>
    </div>
    <button class="btn btn-sm kv-add" type="button" :disabled="disabled" @click="addRow">
      <AppIcon name="plus" :size="13" />
      <span>新增一项</span>
    </button>
  </div>
</template>
