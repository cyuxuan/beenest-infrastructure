<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { api, errorMessage } from '@/lib/api';
import type {
  BatchAction,
  HeaderAction,
  PageResult,
  ResourceConfig,
  ResourceContext,
  RowAction,
} from '@/lib/types';
import { confirmDialog } from '@/lib/confirm';
import { toast } from '@/lib/toast';
import { can } from '@/stores/auth';
import AppIcon from './AppIcon.vue';
import BaseDrawer from './BaseDrawer.vue';
import DataTable from './DataTable.vue';
import PagerBar from './PagerBar.vue';
import PageHeader from './PageHeader.vue';
import SchemaForm from './SchemaForm.vue';

const props = defineProps<{ config: ResourceConfig }>();

const config = props.config;
const initialFilters = (typeof config.initialFilters === 'function' ? config.initialFilters() : config.initialFilters) || {};
const filterDraft = reactive<Record<string, string>>({});
for (const filter of config.filters || []) {
  filterDraft[filter.name] = String(initialFilters[filter.name] ?? '');
}
const activeFilters = reactive<Record<string, string>>({ ...filterDraft });

const rows = ref<any[]>([]);
const loading = ref(false);
const loadError = ref('');
const pageNum = ref(1);
const total = ref(0);
const pages = ref(0);
const pageSize = ref(config.pageSize || 20);
const selected = ref<Array<string | number>>([]);

let requestSeq = 0;

const rowIdKey = config.rowKey || 'id';
const deleteName = config.deleteName || '记录';
const paged = config.paged !== false;

const enabledBatchActions = computed<BatchAction<any>[]>(() =>
  (config.batchActions || []).filter((action) => can(action.perm)),
);
const selectable = computed(() => enabledBatchActions.value.length > 0);
const showCreate = computed(
  () => Boolean(config.form) && typeof config.permCreate === 'string' && can(config.permCreate),
);

const extraActions = computed<HeaderAction[]>(() =>
  (config.headerActions || []).filter((action) => can(action.perm)),
);

const headerBusy = ref('');
const batchBusy = ref('');

function rowId(row: any): string | number {
  return row?.[rowIdKey];
}

function visibleRowActions(row: any): RowAction<any>[] {
  return (config.rowActions?.(row, context) || []).filter((action) => !action.hidden?.(row) && can(action.perm));
}

function clientFiltered(list: any[]): any[] {
  const entries = Object.entries(activeFilters).filter(([, value]) => value !== '' && value !== undefined && value !== null);
  if (!entries.length) return list;
  return list.filter((row) =>
    entries.every(([key, value]) => String(row[key] ?? '').toLowerCase().includes(String(value).toLowerCase())),
  );
}

async function load() {
  const seq = ++requestSeq;
  loading.value = true;
  loadError.value = '';
  try {
    let nextRows: any[] = [];
    if (paged) {
      const page = await api<PageResult<any>>(config.endpoint, {
        query: { ...activeFilters, pageNum: pageNum.value, pageSize: pageSize.value },
        silent: true,
      });
      nextRows = page?.records || [];
      total.value = page?.total || 0;
      pages.value = page?.pages || 0;
      if (page?.pageNum) pageNum.value = page.pageNum;
    } else {
      const raw = await api<any>(config.endpoint, { query: { ...activeFilters }, silent: true });
      const list = config.transform ? config.transform(raw) : (Array.isArray(raw) ? raw : []);
      nextRows = config.clientFilter === false ? list : clientFiltered(list);
      total.value = nextRows.length;
      pages.value = nextRows.length ? 1 : 0;
      pageNum.value = 1;
    }
    if (seq !== requestSeq) return;
    rows.value = nextRows;
    selected.value = [];
  } catch (error) {
    if (seq !== requestSeq) return;
    rows.value = [];
    total.value = 0;
    pages.value = 0;
    loadError.value = errorMessage(error);
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

async function applyFilters() {
  for (const filter of config.filters || []) {
    activeFilters[filter.name] = filterDraft[filter.name] ?? '';
  }
  pageNum.value = 1;
  await load();
}

async function resetFilters() {
  for (const filter of config.filters || []) {
    filterDraft[filter.name] = '';
    activeFilters[filter.name] = '';
  }
  pageNum.value = 1;
  await load();
}

async function changePage(next: number) {
  pageNum.value = next;
  await load();
}

async function changePageSize(size: number) {
  pageSize.value = size;
  pageNum.value = 1;
  await load();
}

const context: ResourceContext<any> = {
  reload: load,
  openEditor: (row?: any) => openEditor(row),
  remove: (row: any) => removeRow(row),
};

onMounted(load);

/* ---------------- 编辑器 ---------------- */

const editorOpen = ref(false);
const editorLoading = ref(false);
const editorBusy = ref(false);
const editorRow = ref<any>(null);
const editorFields = ref<any[]>([]);
const editorValues = ref<Record<string, any>>({});
const editorError = ref('');
const editorKey = ref(0);
const formRef = ref<InstanceType<typeof SchemaForm> | null>(null);

const isEdit = computed(() => Boolean(editorRow.value));

/**
 * 表单取值变化时按当前取值重算字段。
 * <p>动态表单的前提:改一个选项(比如提供方类型),配置项随即换成该类型声明的字段。
 * 字段声明是函数时才有意义,静态字段不受影响。</p>
 */
function onFormChange(values: Record<string, any>) {
  const form = config.form;
  if (!form || typeof form.fields !== 'function') return;
  editorValues.value = { ...editorValues.value, ...values };
  const fields = form.fields(Boolean(editorRow.value), editorRow.value, values);
  if (Array.isArray(fields)) {
    editorFields.value = fields;
  }
}

async function openEditor(row?: any) {
  if (!config.form) return;
  editorRow.value = row || null;
  editorOpen.value = true;
  editorLoading.value = true;
  editorError.value = '';
  editorFields.value = [];
  editorValues.value = {};
  const form = config.form;
  try {
    const declared = row && form.editFields ? form.editFields : form.fields;
    const fields = typeof declared === 'function' ? await declared(Boolean(row), row) : declared;
    editorFields.value = fields;
    editorValues.value = row
      ? form.fromRow
        ? form.fromRow(row)
        : { ...row }
      : { ...(typeof form.defaults === 'function' ? form.defaults() : form.defaults || {}) };
    editorKey.value += 1;
  } catch (error) {
    editorError.value = errorMessage(error);
  } finally {
    editorLoading.value = false;
  }
}

async function saveEditor() {
  const form = config.form;
  const formApi = formRef.value;
  if (!form || !formApi) return;
  const check = await formApi.validate();
  if (!check.ok) return;
  editorBusy.value = true;
  try {
    const payload = formApi.read();
    if (form.beforeSubmit) {
      const message = await form.beforeSubmit(payload, isEdit.value, editorRow.value || undefined);
      if (message) {
        formApi.setError(message);
        return;
      }
    }
    if (isEdit.value) {
      const body = form.toUpdate ? form.toUpdate(payload, editorRow.value) : payload;
      await api(`${config.endpoint}/${rowId(editorRow.value)}`, { method: 'PUT', body });
      toast('已保存', 'success');
    } else {
      const body = form.toCreate ? form.toCreate(payload) : payload;
      const created = await api(config.endpoint, { method: 'POST', body });
      toast('已创建', 'success');
      form.onCreated?.(created);
    }
    editorOpen.value = false;
    await load();
  } catch (error) {
    formApi.setError(errorMessage(error));
  } finally {
    editorBusy.value = false;
  }
}

async function removeRow(row: any) {
  const name = config.rowName ? config.rowName(row) : row?.name || row?.username || row?.roleName || row?.clientName || row?.id;
  const ok = await confirmDialog({
    title: `删除${deleteName}`,
    message: `确认删除「${name}」吗?`,
    detail: '该操作会记录审计日志;数据采用软删除,仍可在审计中追溯。',
    confirmText: '删除',
  });
  if (!ok) return;
  try {
    await api(`${config.endpoint}/${rowId(row)}`, { method: 'DELETE' });
    toast('已删除', 'success');
    if (rows.value.length === 1 && pageNum.value > 1) pageNum.value -= 1;
    await load();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

async function runHeaderAction(action: HeaderAction) {
  if (headerBusy.value) return;
  headerBusy.value = action.label;
  try {
    await action.onClick({ reload: load, filters: { ...activeFilters } });
  } finally {
    headerBusy.value = '';
  }
}

async function runBatch(action: BatchAction<any>) {
  if (!selected.value.length || batchBusy.value) return;
  batchBusy.value = action.label;
  try {
    await action.onClick(selected.value as number[], { reload: load, openEditor, remove: removeRow });
    selected.value = [];
    await load();
  } finally {
    batchBusy.value = '';
  }
}
</script>

<template>
  <div class="view-inner">
    <PageHeader :title="config.title" :desc="config.desc">
      <template #actions>
        <button
          v-if="showCreate"
          class="btn btn-primary"
          type="button"
          @click="openEditor()"
        >
          <AppIcon name="plus" :size="15" />
          <span>{{ config.createLabel || '新建' }}</span>
        </button>
        <button
          v-for="action in extraActions"
          :key="action.label"
          class="btn"
          :class="{ 'is-busy': headerBusy === action.label }"
          type="button"
          :disabled="Boolean(headerBusy)"
          @click="runHeaderAction(action)"
        >
          <AppIcon v-if="action.icon" :name="action.icon" :size="15" />
          <span>{{ action.label }}</span>
        </button>
      </template>
    </PageHeader>

    <section class="card table-card">
      <div class="card-head">
        <div class="card-head-main">
          <div class="card-title">{{ config.cardTitle || config.title }}</div>
          <div class="card-sub">
            <span v-if="!loading && !loadError">共 {{ total }} 条记录</span>
            <span v-else-if="loading" class="card-sub-dim">正在同步数据…</span>
            <span v-else class="card-sub-dim">数据暂不可用</span>
          </div>
        </div>
        <div class="card-actions">
          <button class="icon-btn" type="button" title="刷新" aria-label="刷新" :disabled="loading" @click="load">
            <AppIcon name="refresh" :size="16" :class="{ 'spin': loading }" />
          </button>
        </div>
      </div>

      <div v-if="(config.filters || []).length" class="filter-bar">
        <div
          v-for="filter in config.filters"
          :key="filter.name"
          class="filter"
          :style="{ width: filter.width }"
        >
          <label class="filter-label" :for="`f-${filter.name}`">{{ filter.label }}</label>
          <select
            v-if="filter.type === 'select'"
            :id="`f-${filter.name}`"
            v-model="filterDraft[filter.name]"
            class="input select"
            @change="applyFilters"
          >
            <option
              v-for="option in filter.options || []"
              :key="String(option.value)"
              :value="String(option.value)"
            >
              {{ option.label }}
            </option>
          </select>
          <input
            v-else
            :id="`f-${filter.name}`"
            v-model="filterDraft[filter.name]"
            class="input"
            :type="filter.type === 'datetime' ? 'datetime-local' : 'search'"
            :placeholder="filter.placeholder || `按${filter.label}筛选`"
            @keydown.enter="applyFilters"
          />
        </div>
        <div class="filter-actions">
          <button class="btn btn-accent" type="button" @click="applyFilters">
            <AppIcon name="search" :size="15" />
            <span>查询</span>
          </button>
          <button class="btn" type="button" @click="resetFilters">重置</button>
        </div>
      </div>

      <div v-if="selected.length" class="bulk-bar">
        <div class="bulk-copy">
          <span class="bulk-count">{{ selected.length }}</span>
          <span>项已选择</span>
          <button class="link-btn" type="button" @click="selected = []">取消选择</button>
        </div>
        <div class="bulk-actions">
          <button
            v-for="action in enabledBatchActions"
            :key="action.label"
            class="btn btn-sm"
            :class="action.danger ? 'btn-danger-soft' : ''"
            type="button"
            :disabled="Boolean(batchBusy)"
            @click="runBatch(action)"
          >
            {{ action.label }}
          </button>
        </div>
      </div>

      <div v-if="loadError" class="load-error">
        <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
        <div class="load-error-copy">
          <strong>数据加载失败</strong>
          <span>{{ loadError }}</span>
        </div>
        <button class="btn btn-sm" type="button" @click="load">重试</button>
      </div>

      <DataTable
        v-else
        :columns="config.columns"
        :rows="rows"
        :row-key="config.rowKey || 'id'"
        :selectable="selectable"
        v-model:selected="selected"
        :row-actions="config.rowActions ? visibleRowActions : undefined"
        :actions-label="config.rowActionsLabel || '操作'"
        :row-click="config.rowClick"
        :empty-text="config.emptyText || '没有匹配的数据'"
        :loading="loading"
      />

      <PagerBar
        v-if="paged && !loadError && !loading && rows.length"
        :page-num="pageNum"
        :pages="pages"
        :total="total"
        :page-size="pageSize"
        :sizes="config.pageSizeOptions || [10, 20, 50, 100]"
        @change="changePage"
        @update:page-size="changePageSize"
      />
    </section>

    <BaseDrawer
      v-if="config.form"
      v-model:open="editorOpen"
      :title="isEdit ? config.form.editTitle || `编辑${config.form.title}` : config.form.createTitle || `新建${config.form.title}`"
      :subtitle="config.form.subtitle"
      width="620px"
      :close-on-backdrop="false"
    >
      <div v-if="config.form.hint && !isEdit" class="alert is-warn form-hint">
        <AppIcon name="info" :size="16" />
        <span>{{ config.form.hint }}</span>
      </div>
      <SkeletonTable v-if="editorLoading" :rows="4" :columns="2" />
      <div v-else-if="editorError" class="alert is-danger">
        <AppIcon name="alert" :size="16" />
        <span>{{ editorError }}</span>
      </div>
      <SchemaForm
        v-else
        :key="editorKey"
        ref="formRef"
        :fields="editorFields"
        :values="editorValues"
        :mode="isEdit ? 'edit' : 'create'"
        :validate="config.form.validate"
        @change="onFormChange"
      />
      <template #footer>
        <button class="btn btn-ghost" type="button" :disabled="editorBusy" @click="editorOpen = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="editorBusy || editorLoading" @click="saveEditor">
          <span v-if="editorBusy" class="btn-spinner" />
          <span>{{ isEdit ? '保存更改' : '创建' }}</span>
        </button>
      </template>
    </BaseDrawer>
  </div>
</template>
