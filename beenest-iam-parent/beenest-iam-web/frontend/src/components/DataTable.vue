<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import type { ColumnDef, RowAction } from '@/lib/types';
import { can } from '@/stores/auth';
import AppIcon from './AppIcon.vue';
import CellContent from './CellContent.vue';
import EmptyState from './EmptyState.vue';
import SkeletonTable from './SkeletonTable.vue';

const props = withDefaults(
  defineProps<{
    columns: ColumnDef<any>[];
    rows: any[];
    rowKey?: string;
    selectable?: boolean;
    selected?: Array<string | number>;
    rowActions?: (row: any) => RowAction<any>[];
    actionsLabel?: string;
    rowClick?: (row: any) => void;
    emptyText?: string;
    loading?: boolean;
  }>(),
  {
    rowKey: 'id',
    selectable: false,
    selected: () => [],
    rowActions: undefined,
    actionsLabel: '操作',
    rowClick: undefined,
    emptyText: '没有匹配的数据',
    loading: false,
  },
);

const emit = defineEmits<{ (e: 'update:selected', value: Array<string | number>): void }>();

const headerCheckbox = ref<HTMLInputElement | null>(null);
const menuEl = ref<HTMLElement | null>(null);
const menu = ref<{ open: boolean; top: number; left: number; row: any; actions: RowAction<any>[] }>({
  open: false,
  top: 0,
  left: 0,
  row: null,
  actions: [],
});

const selectedSet = computed(() => new Set(props.selected.map(String)));
const allSelected = computed(() => props.rows.length > 0 && props.rows.every((row, index) => selectedSet.value.has(String(rowId(row, index)))));
const someSelected = computed(() => props.rows.some((row, index) => selectedSet.value.has(String(rowId(row, index)))) && !allSelected.value);

watch(
  [allSelected, someSelected, headerCheckbox],
  () => {
    if (headerCheckbox.value) headerCheckbox.value.indeterminate = someSelected.value;
  },
  { flush: 'post' },
);

function rowId(row: any, index: number): string | number {
  const value = row?.[props.rowKey];
  return value === undefined || value === null ? index : value;
}

function isSelected(row: any, index: number) {
  return selectedSet.value.has(String(rowId(row, index)));
}

function toggleRow(row: any, index: number) {
  const id = rowId(row, index);
  const exists = selectedSet.value.has(String(id));
  const next = exists
    ? props.selected.filter((item) => String(item) !== String(id))
    : [...props.selected, id];
  emit('update:selected', next);
}

function toggleAll() {
  emit('update:selected', allSelected.value ? [] : props.rows.map((row, index) => rowId(row, index)));
}

function visibleActions(row: any): RowAction<any>[] {
  return (props.rowActions?.(row) || []).filter((action) => !action.hidden?.(row) && can(action.perm));
}

function cellContent(column: ColumnDef<any>, row: any) {
  if (column.render) return column.render(row);
  return column.key ? row[column.key] ?? null : null;
}

function onRowClick(row: any, event: MouseEvent) {
  if (!props.rowClick) return;
  const target = event.target as HTMLElement;
  if (target.closest('button, a, input, label, select')) return;
  props.rowClick(row);
}

function removeMenuListeners() {
  document.removeEventListener('click', onDocumentClick);
  document.removeEventListener('keydown', onMenuKeydown);
  window.removeEventListener('scroll', closeMenu, true);
  window.removeEventListener('resize', closeMenu);
}

function closeMenu() {
  if (!menu.value.open) return;
  menu.value = { ...menu.value, open: false };
  removeMenuListeners();
}

function onDocumentClick(event: MouseEvent) {
  if (menuEl.value && !menuEl.value.contains(event.target as Node)) closeMenu();
}

function onMenuKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') closeMenu();
}

async function openMenu(event: MouseEvent, row: any, actions: RowAction<any>[]) {
  closeMenu();
  if (!actions.length) return;
  const trigger = event.currentTarget as HTMLElement;
  const rect = trigger.getBoundingClientRect();
  const width = 184;
  menu.value = {
    open: true,
    top: rect.bottom + 6,
    left: Math.max(8, Math.min(rect.right - width, window.innerWidth - width - 8)),
    row,
    actions,
  };
  await nextTick();
  const height = menuEl.value?.offsetHeight || 0;
  if (rect.bottom + 6 + height > window.innerHeight - 8) {
    menu.value.top = Math.max(8, rect.top - height - 6);
  }
  window.setTimeout(() => {
    document.addEventListener('click', onDocumentClick);
    document.addEventListener('keydown', onMenuKeydown);
    window.addEventListener('scroll', closeMenu, true);
    window.addEventListener('resize', closeMenu);
  }, 0);
}

function runMenuAction(action: RowAction<any>) {
  const row = menu.value.row;
  closeMenu();
  action.onClick(row);
}

onBeforeUnmount(removeMenuListeners);
</script>

<template>
  <div class="table-wrap">
    <SkeletonTable v-if="loading" :rows="6" :columns="Math.min(columns.length + 1, 6)" />
    <EmptyState v-else-if="!rows.length" :title="emptyText" hint="调整筛选条件,或稍后刷新重试" />
    <table v-else class="table">
      <thead>
        <tr>
          <th v-if="selectable" class="col-check">
            <input
              ref="headerCheckbox"
              class="checkbox"
              type="checkbox"
              aria-label="全选当前页"
              :checked="allSelected"
              @change="toggleAll"
            />
          </th>
          <th
            v-for="(column, index) in columns"
            :key="`${column.label}-${index}`"
            :style="{ width: column.width }"
            :class="[
              column.align === 'right' ? 'is-right' : '',
              column.align === 'center' ? 'is-center' : '',
              column.className || '',
            ]"
          >
            {{ column.label }}
          </th>
          <th v-if="rowActions" class="col-actions">{{ actionsLabel }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(row, index) in rows"
          :key="String(rowId(row, index)) + '-' + index"
          :class="{ 'is-selected': isSelected(row, index), 'is-clickable': Boolean(rowClick) }"
          @click="onRowClick(row, $event)"
        >
          <td v-if="selectable" class="col-check" @click.stop>
            <input
              class="checkbox"
              type="checkbox"
              aria-label="选择该行"
              :checked="isSelected(row, index)"
              @change="toggleRow(row, index)"
            />
          </td>
          <td
            v-for="(column, columnIndex) in columns"
            :key="`${column.label}-${columnIndex}`"
            :class="[
              column.align === 'right' ? 'is-right' : '',
              column.align === 'center' ? 'is-center' : '',
              column.className || '',
            ]"
          >
            <CellContent :content="cellContent(column, row)" />
          </td>
          <td v-if="rowActions" class="col-actions" @click.stop>
            <span v-if="!visibleActions(row).length" class="muted">—</span>
            <span v-else class="row-actions">
              <template v-for="action in visibleActions(row).length > 3 ? visibleActions(row).slice(0, 2) : visibleActions(row)" :key="action.label">
                <button
                  class="link-btn"
                  :class="{ 'is-danger': action.danger }"
                  type="button"
                  @click.stop="action.onClick(row)"
                >
                  {{ action.label }}
                </button>
              </template>
              <button
                v-if="visibleActions(row).length > 3"
                class="link-btn is-icon"
                type="button"
                aria-label="更多操作"
                aria-haspopup="menu"
                title="更多操作"
                @click.stop="openMenu($event, row, visibleActions(row).slice(2))"
              >
                <AppIcon name="more" :size="16" />
              </button>
            </span>
          </td>
        </tr>
      </tbody>
    </table>

    <Teleport to="body">
      <Transition name="menu">
        <div
          v-if="menu.open"
          ref="menuEl"
          class="row-menu"
          role="menu"
          :style="{ top: `${menu.top}px`, left: `${menu.left}px` }"
        >
          <template v-for="(action, index) in menu.actions" :key="action.label">
            <div v-if="action.danger && index > 0" class="row-menu-divider" />
            <button
              class="row-menu-item"
              :class="{ 'is-danger': action.danger }"
              type="button"
              role="menuitem"
              @click="runMenuAction(action)"
            >
              {{ action.label }}
            </button>
          </template>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
