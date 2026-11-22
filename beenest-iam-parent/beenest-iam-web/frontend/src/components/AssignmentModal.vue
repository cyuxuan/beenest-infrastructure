<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { Tone } from '@/lib/ui';
import AppIcon from './AppIcon.vue';
import BaseModal from './BaseModal.vue';
import EmptyState from './EmptyState.vue';
import SkeletonTable from './SkeletonTable.vue';

interface AssignmentItem {
  id: string | number;
  label: string;
  hint?: string;
  group?: string;
  tags?: Array<{ text: string; tone?: Tone }>;
  disabled?: boolean;
}

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    subtitle?: string;
    items: AssignmentItem[];
    modelValue?: Array<string | number>;
    loading?: boolean;
    saving?: boolean;
    error?: string;
    alert?: string;
    alertTone?: 'info' | 'warn' | 'danger' | 'success';
    searchable?: boolean;
    searchPlaceholder?: string;
    emptyText?: string;
    saveLabel?: string;
    countLabel?: string;
  }>(),
  {
    subtitle: '',
    modelValue: () => [],
    loading: false,
    saving: false,
    error: '',
    alert: '',
    alertTone: 'warn',
    searchable: true,
    searchPlaceholder: '按名称或编码过滤…',
    emptyText: '暂无可分配项',
    saveLabel: '保存授权',
    countLabel: '项',
  },
);

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'update:open', value: boolean): void;
  (e: 'save', ids: Array<string | number>): void;
}>();

const keyword = ref('');
const selected = ref<Set<string>>(new Set());

const itemsById = computed(() => {
  const map = new Map<string, AssignmentItem>();
  for (const item of props.items) map.set(String(item.id), item);
  return map;
});

const selectedCount = computed(() => selected.value.size);
const totalCount = computed(() => props.items.length);
/** 已选但不在当前候选列表中的数量(候选列表限量,这些项看不见但会被保留) */
const hiddenSelectedCount = computed(() => {
  const known = new Set(props.items.map((item) => String(item.id)));
  let count = 0;
  for (const id of selected.value) {
    if (!known.has(id)) count += 1;
  }
  return count;
});

function resetSelection() {
  selected.value = new Set((props.modelValue || []).map((id) => String(id)));
  keyword.value = '';
}

watch(
  () => props.open,
  (open) => {
    if (open) resetSelection();
  },
);

watch(
  () => props.modelValue,
  () => {
    if (props.open) resetSelection();
  },
  { deep: true },
);

// 候选列表变化时**不**丢弃已选项中不在列表里的那些:
// 候选列表是限量/分页的(组员候选取前 500 个用户),丢弃等于把
// 用户没看见的成员、角色静默踢掉——组员保存是覆盖式的,少一个 id 就是少一个人。
// 这些"看不见但已选"的项在下方单独提示。

const grouped = computed(() => props.items.some((item) => Boolean(item.group)));

const visibleItems = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  if (!text) return props.items;
  return props.items.filter((item) =>
    `${item.label} ${item.hint || ''} ${item.group || ''}`.toLowerCase().includes(text),
  );
});

const groups = computed(() => {
  const map = new Map<string, AssignmentItem[]>();
  for (const item of visibleItems.value) {
    const key = item.group || '';
    const bucket = map.get(key);
    if (bucket) bucket.push(item);
    else map.set(key, [item]);
  }
  return [...map.entries()].map(([label, items]) => ({ label, items }));
});

function isChecked(item: AssignmentItem) {
  return selected.value.has(String(item.id));
}

function toggle(item: AssignmentItem) {
  if (item.disabled) return;
  const key = String(item.id);
  if (selected.value.has(key)) selected.value.delete(key);
  else selected.value.add(key);
  selected.value = new Set(selected.value);
}

function groupChecked(items: AssignmentItem[]) {
  const enabled = items.filter((item) => !item.disabled);
  return enabled.length > 0 && enabled.every((item) => selected.value.has(String(item.id)));
}

function toggleGroup(items: AssignmentItem[]) {
  const enabled = items.filter((item) => !item.disabled);
  const next = new Set(selected.value);
  if (groupChecked(items)) {
    for (const item of enabled) next.delete(String(item.id));
  } else {
    for (const item of enabled) next.add(String(item.id));
  }
  selected.value = next;
}

function rawId(key: string): string | number {
  const item = itemsById.value.get(key);
  if (item) return item.id;
  return /^\d+$/.test(key) ? Number(key) : key;
}

function close() {
  emit('update:open', false);
  emit('close');
}

function save() {
  emit('save', [...selected.value].map(rawId));
}
</script>

<template>
  <BaseModal
    :open="open"
    :title="title"
    :subtitle="subtitle"
    size="lg"
    :close-on-backdrop="!saving"
    @close="close"
  >
    <div class="assign">
      <div v-if="alert" class="alert" :class="`is-${alertTone}`">
        <AppIcon name="info" :size="15" />
        <span>{{ alert }}</span>
      </div>

      <div v-if="searchable" class="assign-toolbar">
        <div class="assign-search">
          <AppIcon name="search" :size="15" />
          <input v-model="keyword" class="input" type="search" :placeholder="searchPlaceholder" />
        </div>
        <span class="assign-count">
          已选 <strong>{{ selectedCount }}</strong> / {{ totalCount }}
          <em v-if="hiddenSelectedCount > 0" class="assign-hint">
            (其中 {{ hiddenSelectedCount }} 项不在当前列表中,保存时保留)
          </em>
        </span>
      </div>

      <SkeletonTable v-if="loading" :rows="5" :columns="2" />
      <div v-else-if="error" class="alert is-danger">
        <AppIcon name="alert" :size="15" />
        <span>{{ error }}</span>
      </div>
      <EmptyState v-else-if="!visibleItems.length" :title="emptyText" hint="调整搜索条件或稍后再试" icon="inbox" />

      <div v-else class="assign-list" :class="{ 'is-grouped': grouped }">
        <section v-for="group in groups" :key="group.label" class="assign-group">
          <div v-if="grouped" class="assign-group-head">
            <div class="assign-group-title">
              <span>{{ group.label || '其他' }}</span>
              <span class="assign-group-count">{{ group.items.length }} {{ countLabel }}</span>
            </div>
            <button class="link-btn" type="button" @click="toggleGroup(group.items)">
              {{ groupChecked(group.items) ? '取消本组' : '全选本组' }}
            </button>
          </div>
          <div class="assign-grid">
            <label
              v-for="item in group.items"
              :key="item.id"
              class="assign-item"
              :class="{ 'is-checked': isChecked(item), 'is-disabled': item.disabled }"
            >
              <input
                class="checkbox"
                type="checkbox"
                :checked="isChecked(item)"
                :disabled="item.disabled"
                @change="toggle(item)"
              />
              <span class="assign-copy">
                <span class="assign-label">{{ item.label }}</span>
                <span v-if="item.hint" class="assign-hint">{{ item.hint }}</span>
              </span>
              <span v-if="item.tags?.length" class="assign-tags">
                <span v-for="tag in item.tags" :key="tag.text" class="badge" :class="tag.tone">{{ tag.text }}</span>
              </span>
            </label>
          </div>
        </section>
      </div>
    </div>

    <template #footer>
      <span class="assign-foot-note">保存后相关账号重新登录生效</span>
      <button class="btn btn-ghost" type="button" :disabled="saving" @click="close">取消</button>
      <button class="btn btn-primary" type="button" :disabled="loading || Boolean(error) || saving" @click="save">
        <span v-if="saving" class="btn-spinner" />
        <span>{{ saveLabel }}</span>
      </button>
    </template>
  </BaseModal>
</template>

<style scoped>
.assign {
  display: flex;
  flex-direction: column;
  gap: 13px;
}

.assign-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.assign-search {
  position: relative;
  flex: 1;
  min-width: 220px;
  display: flex;
  align-items: center;
}

.assign-search > svg {
  position: absolute;
  left: 11px;
  color: var(--faint);
  pointer-events: none;
}

.assign-search .input {
  padding-left: 33px;
}

.assign-count {
  font-size: 11.5px;
  color: var(--muted);
  white-space: nowrap;
}

.assign-count strong {
  font-family: var(--font-mono);
  color: var(--ink);
}

.assign-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 52vh;
  overflow-y: auto;
  padding-right: 3px;
}

.assign-list.is-grouped {
  gap: 16px;
}

.assign-group {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.assign-group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--line);
}

.assign-group-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  font-weight: 650;
  color: var(--ink);
}

.assign-group-count {
  font-size: 10px;
  font-weight: 500;
  color: var(--faint);
}

.assign-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 6px;
}

.assign-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--line-soft);
  border-radius: 9px;
  background: var(--surface-2);
  cursor: pointer;
  transition: border-color 0.14s ease, background 0.14s ease;
}

.assign-item:hover {
  border-color: var(--line-strong);
  background: var(--surface);
}

.assign-item.is-checked {
  border-color: rgba(168, 115, 10, 0.4);
  background: rgba(242, 181, 46, 0.09);
}

.assign-item.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.assign-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}

.assign-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assign-hint {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assign-tags {
  margin-left: auto;
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.assign-foot-note {
  margin-right: auto;
  font-size: 10.5px;
  color: var(--faint);
}

@media (max-width: 560px) {
  .assign-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .assign-foot-note {
    display: none;
  }
}
</style>
