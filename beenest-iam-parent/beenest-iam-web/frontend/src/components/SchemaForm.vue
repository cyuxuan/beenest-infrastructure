<script setup lang="ts">
import { nextTick, reactive, ref, toValue, watch } from 'vue';
import type { FieldDef } from '@/lib/types';
import AppIcon from './AppIcon.vue';
import KvEditor from './KvEditor.vue';
import TagsInput from './TagsInput.vue';

const props = withDefaults(
  defineProps<{
    fields: FieldDef[];
    values?: Record<string, any>;
    mode?: 'create' | 'edit';
    validate?: (values: Record<string, any>) => string | void;
  }>(),
  { values: () => ({}), mode: 'create', validate: undefined },
);

const emit = defineEmits<{ (e: 'change', values: Record<string, any>): void }>();

let uid = 0;
const instanceId = ++uid;
const formError = ref('');
const fieldErrors = reactive<Record<string, string>>({});
const visiblePasswords = reactive<Record<string, boolean>>({});
const model = reactive<Record<string, any>>({});

function initialValue(field: FieldDef): any {
  if (field.name in props.values && props.values[field.name] !== undefined && props.values[field.name] !== null) {
    const value = props.values[field.name];
    if (field.type === 'tags') return Array.isArray(value) ? [...value] : [];
    if (field.type === 'multiselect') return Array.isArray(value) ? [...value] : [];
    if (field.type === 'kv') return { ...(value || {}) };
    if (field.type === 'switch') return Boolean(value);
    if (field.type === 'number') return String(value);
    if (field.type === 'select') return String(value);
    return value;
  }
  if (field.defaultValue !== undefined) {
    if (field.type === 'tags') return [...(field.defaultValue as string[])];
    if (field.type === 'multiselect') return [...((field.defaultValue as string[]) ?? [])];
    if (field.type === 'kv') return { ...(field.defaultValue as Record<string, string>) };
    if (field.type === 'switch') return Boolean(field.defaultValue);
    if (field.type === 'number' || field.type === 'select') return String(field.defaultValue);
    return field.defaultValue;
  }
  switch (field.type) {
    case 'switch':
      return false;
    case 'tags':
      return [];
    case 'kv':
      return {};
    case 'number':
      return '';
    case 'select':
      return !field.placeholder && toValue(field.options)?.length ? String(toValue(field.options)![0].value) : '';
    default:
      return '';
  }
}

for (const field of props.fields) {
  if (field.type === 'section') continue;
  model[field.name] = initialValue(field);
}

/**
 * 字段集合变化时补齐新增字段的初值。
 * <p>外部按当前取值重算字段时,新出现的字段要有初值,否则它会读不到值;
 * 已填过的字段保持原值,不能被重算冲掉。</p>
 */
watch(
  () => props.fields,
  (fields) => {
    for (const field of fields) {
      if (field.type === 'section') continue;
      if (!(field.name in model)) {
        model[field.name] = initialValue(field);
      }
    }
  },
  { deep: true },
);

/** 取值变化时通知外部:外部据此按当前值重算字段(动态表单)。 */
watch(
  model,
  () => {
    emit('change', { ...model });
  },
  { deep: true },
);

function fieldId(field: FieldDef) {
  return `sf-${instanceId}-${field.name}`;
}

function fieldClass(field: FieldDef) {
  return [field.full || field.span === 2 ? 'is-full' : '', `field-${field.type || 'text'}`];
}

function onNumberInput(name: string, event: Event) {
  model[name] = (event.target as HTMLInputElement).value;
}

/** 多选字段的勾选切换。 */
function toggleMulti(name: string, value: string, event: Event) {
  const target = event.target as HTMLInputElement;
  const current: string[] = Array.isArray(model[name]) ? [...model[name]] : [];
  if (target.checked) {
    if (!current.includes(value)) current.push(value);
  } else {
    const index = current.indexOf(value);
    if (index >= 0) current.splice(index, 1);
  }
  model[name] = current;
}

function isRequired(field: FieldDef) {
  return Boolean(field.required);
}

function emptyValue(field: FieldDef): boolean {
  const value = model[field.name];
  if (field.type === 'tags') return !Array.isArray(value) || value.length === 0;
  if (field.type === 'kv') return !value || Object.keys(value).length === 0;
  return value === undefined || value === null || String(value).trim() === '';
}

/** 读取提交体:创建态省略空文本(避免无意义的空串),编辑态保留空串以支持清空字段。 */
function read(): Record<string, any> {
  const out: Record<string, any> = {};
  for (const field of props.fields) {
    if (field.type === 'section' || field.type === 'static') continue;
    const value = model[field.name];
    if (field.type === 'number') {
      if (value === '' || value === null || value === undefined) continue;
      out[field.name] = Number(value);
      continue;
    }
    if (field.type === 'switch') {
      out[field.name] = Boolean(value);
      continue;
    }
    if (field.type === 'tags') {
      out[field.name] = Array.isArray(value) ? [...value] : [];
      continue;
    }
    if (field.type === 'multiselect') {
      // 空数组合法:白名单留空表示不限制。不能落到下面的空串分支,
      // 后端对 [] 与 "" 的处理不同
      out[field.name] = Array.isArray(value) ? [...value] : [];
      continue;
    }
    if (field.type === 'kv') {
      out[field.name] = { ...(value || {}) };
      continue;
    }
    const text = value === undefined || value === null ? '' : String(value);
    if (text === '') {
      if (field.type === 'select') {
        if (props.mode === 'create') continue;
        out[field.name] = '';
        continue;
      }
      if (props.mode === 'create' && !field.keepEmptyOnCreate) continue;
      if (field.omitWhenEmpty) continue;
      out[field.name] = '';
      continue;
    }
    out[field.name] = text;
  }
  return out;
}

async function validate(): Promise<{ ok: boolean; message?: string }> {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key];
  formError.value = '';
  for (const field of props.fields) {
    if (!field.required || field.type === 'switch' || field.type === 'section' || field.type === 'static') continue;
    if (emptyValue(field)) {
      const message = `${field.label}不能为空`;
      fieldErrors[field.name] = message;
      await nextTick();
      const node = document.getElementById(fieldId(field));
      const target = node || document.querySelector<HTMLElement>(`[name="${field.name}"]`);
      target?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      target?.focus({ preventScroll: true });
      return { ok: false, message };
    }
  }
  if (props.validate) {
    const message = props.validate(read());
    if (message) {
      formError.value = message;
      return { ok: false, message };
    }
  }
  return { ok: true };
}

function setError(message: string) {
  formError.value = message || '';
}

function focus(name: string) {
  document.getElementById(`sf-${instanceId}-${name}`)?.focus();
}

defineExpose({ read, validate, setError, focus, model });
</script>

<template>
  <div class="schema-form">
    <div v-if="formError" class="alert is-danger is-full" role="alert">
      <AppIcon name="alert" :size="16" />
      <span>{{ formError }}</span>
    </div>

    <template v-for="field in fields" :key="field.name">
      <div v-if="field.type === 'section'" class="form-section">
        <span class="form-section-title">{{ field.label }}</span>
        <span v-if="field.help" class="form-section-hint">{{ field.help }}</span>
      </div>

      <div v-else-if="field.type === 'static'" class="field field-static" :class="fieldClass(field)">
        <div class="field-label">{{ field.label }}</div>
        <div class="field-static-value">{{ model[field.name] ?? '—' }}</div>
      </div>

      <label
        v-else-if="field.type === 'switch'"
        class="field field-switch"
        :class="fieldClass(field)"
      >
        <span class="switch">
          <input v-model="model[field.name]" type="checkbox" :disabled="field.disabled" />
          <span class="switch-track"><span class="switch-thumb" /></span>
          <span class="switch-text">{{ model[field.name] ? field.onText || '启用' : field.offText || '关闭' }}</span>
        </span>
        <span class="field-switch-copy">
          <span class="field-label">{{ field.label }}</span>
          <span v-if="field.help" class="field-help">{{ field.help }}</span>
        </span>
      </label>

      <div v-else class="field" :class="fieldClass(field)">
        <label class="field-label" :for="fieldId(field)">
          {{ field.label }}
          <span v-if="isRequired(field)" class="req" aria-hidden="true">*</span>
        </label>

        <TagsInput
          v-if="field.type === 'tags'"
          :id="fieldId(field)"
          v-model="model[field.name]"
          :placeholder="field.placeholder"
          :disabled="field.disabled"
          :invalid="Boolean(fieldErrors[field.name])"
        />

        <KvEditor
          v-else-if="field.type === 'kv'"
          v-model="model[field.name]"
          :key-placeholder="field.keyPlaceholder"
          :value-placeholder="field.valuePlaceholder"
          :disabled="field.disabled"
        />

        <div
          v-else-if="field.type === 'multiselect'"
          :id="fieldId(field)"
          class="input multiselect"
          :class="{ 'is-invalid': fieldErrors[field.name] }"
        >
          <label v-for="option in toValue(field.options) || []" :key="String(option.value)" class="multiselect-item">
            <input
              type="checkbox"
              :value="String(option.value)"
              :checked="(model[field.name] || []).includes(String(option.value))"
              :disabled="field.disabled"
              @change="toggleMulti(field.name, String(option.value), $event)"
            />
            <span>{{ option.label }}</span>
          </label>
        </div>

        <select
          v-else-if="field.type === 'select'"
          :id="fieldId(field)"
          v-model="model[field.name]"
          class="input select"
          :class="{ 'is-invalid': fieldErrors[field.name], 'is-mono': field.mono }"
          :disabled="field.disabled"
        >
          <option v-if="field.placeholder" value="">{{ field.placeholder }}</option>
          <option v-for="option in toValue(field.options) || []" :key="String(option.value)" :value="String(option.value)">
            {{ option.label }}
          </option>
        </select>

        <textarea
          v-else-if="field.type === 'textarea'"
          :id="fieldId(field)"
          v-model="model[field.name]"
          class="input textarea"
          :class="{ 'is-invalid': fieldErrors[field.name], 'is-mono': field.mono }"
          :rows="field.rows || 3"
          :placeholder="field.placeholder"
          :disabled="field.disabled"
        />

        <div v-else-if="field.type === 'password'" class="input-affix">
          <input
            :id="fieldId(field)"
            v-model="model[field.name]"
            class="input is-mono"
            :class="{ 'is-invalid': fieldErrors[field.name] }"
            :type="visiblePasswords[field.name] ? 'text' : 'password'"
            :placeholder="field.placeholder"
            :autocomplete="field.autocomplete || 'new-password'"
            :disabled="field.disabled"
          />
          <button
            class="input-affix-btn"
            type="button"
            :aria-label="visiblePasswords[field.name] ? '隐藏口令' : '显示口令'"
            @click="visiblePasswords[field.name] = !visiblePasswords[field.name]"
          >
            <AppIcon :name="visiblePasswords[field.name] ? 'eyeOff' : 'eye'" :size="15" />
          </button>
        </div>

        <input
          v-else-if="field.type === 'number'"
          :id="fieldId(field)"
          class="input"
          :class="{ 'is-invalid': fieldErrors[field.name] }"
          type="number"
          :value="model[field.name]"
          :placeholder="field.placeholder"
          :disabled="field.disabled"
          :min="field.min"
          :max="field.max"
          :step="field.step"
          @input="onNumberInput(field.name, $event)"
        />

        <input
          v-else
          :id="fieldId(field)"
          v-model="model[field.name]"
          class="input"
          :class="{ 'is-invalid': fieldErrors[field.name], 'is-mono': field.mono }"
          :type="field.type || 'text'"
          :placeholder="field.placeholder"
          :autocomplete="field.autocomplete"
          :disabled="field.disabled"
        />

        <span v-if="fieldErrors[field.name]" class="field-error">{{ fieldErrors[field.name] }}</span>
        <span v-else-if="field.help" class="field-help">{{ field.help }}</span>
      </div>
    </template>
  </div>
</template>
