<script setup lang="ts">
import { nextTick, ref } from 'vue';
import AppIcon from './AppIcon.vue';

const props = withDefaults(
  defineProps<{
    modelValue: string[];
    placeholder?: string;
    disabled?: boolean;
    invalid?: boolean;
    id?: string;
  }>(),
  { placeholder: '输入后按回车添加', disabled: false, invalid: false, id: undefined },
);

const emit = defineEmits<{ (e: 'update:modelValue', value: string[]): void }>();

const input = ref<HTMLInputElement | null>(null);
const draft = ref('');

function commit() {
  const parts = draft.value.split(/[,，\n]/).map((part) => part.trim()).filter(Boolean);
  if (!parts.length) {
    draft.value = '';
    return;
  }
  const next = [...props.modelValue];
  for (const part of parts) {
    if (!next.includes(part)) next.push(part);
  }
  emit('update:modelValue', next);
  draft.value = '';
}

function remove(index: number) {
  const next = [...props.modelValue];
  next.splice(index, 1);
  emit('update:modelValue', next);
}

function onKeydown(event: KeyboardEvent) {
  // 中文输入法未上屏时,回车是"确认候选词"而不是"添加标签"。
  // 此时 keyCode 为 229 或 isComposing 为 true:必须让输入法先消化这次按键,
  // 否则表现为「按回车没反应」或标签被截断成半个词。
  if (event.isComposing || event.keyCode === 229) return;
  if (event.key === 'Enter' || event.key === ',' || event.key === '，') {
    event.preventDefault();
    commit();
  } else if (event.key === 'Backspace' && !draft.value && props.modelValue.length) {
    remove(props.modelValue.length - 1);
  }
}

async function addFromPaste(event: ClipboardEvent) {
  const text = event.clipboardData?.getData('text');
  if (!text || !/[,，\n]/.test(text)) return;
  event.preventDefault();
  const parts = text.split(/[,，\n]/).map((part) => part.trim()).filter(Boolean);
  const next = [...props.modelValue];
  for (const part of parts) if (!next.includes(part)) next.push(part);
  emit('update:modelValue', next);
  draft.value = '';
  await nextTick();
}
</script>

<template>
  <div
    class="tags-input"
    :class="{ 'is-invalid': invalid, 'is-disabled': disabled }"
    @click="input?.focus()"
  >
    <span v-for="(value, index) in modelValue" :key="value + index" class="tag-chip">
      <span class="tag-chip-text">{{ value }}</span>
      <button
        v-if="!disabled"
        class="tag-chip-remove"
        type="button"
        :aria-label="`移除 ${value}`"
        @click.stop="remove(index)"
      >
        <AppIcon name="close" :size="11" />
      </button>
    </span>
    <input
      :id="id"
      ref="input"
      v-model="draft"
      class="tags-input-native"
      type="text"
      :placeholder="modelValue.length ? '' : placeholder"
      :disabled="disabled"
      @keydown="onKeydown"
      @paste="addFromPaste"
      @blur="commit"
    />
  </div>
</template>
