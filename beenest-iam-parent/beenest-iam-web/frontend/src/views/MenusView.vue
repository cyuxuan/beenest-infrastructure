<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { useRoute } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import DataTable from '@/components/DataTable.vue';
import PageHeader from '@/components/PageHeader.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { toast } from '@/lib/toast';
import type { FieldDef, FilterOption, PageResult } from '@/lib/types';
import { badge, dotBadge, mono, muted, strongSub } from '@/lib/ui';
import { can } from '@/stores/auth';

const route = useRoute();

const TYPES = [
  { value: 'MENU', label: '菜单' },
  { value: 'BUTTON', label: '按钮' },
];

const MODE_HINTS: Record<string, { tone: string; title: string; text: string }> = {
  IAM: {
    tone: 'is-info',
    title: 'IAM 全量托管',
    text: '该应用菜单全量由 IAM 托管:应用直接渲染下方菜单树。',
  },
  HYBRID: {
    tone: 'is-warn',
    title: '仅一级模块托管',
    text: 'IAM 只托管一级模块(推荐):深层页面与图标排序由应用自管,双方通过权限码对齐。',
  },
  APP: {
    tone: 'is-warn',
    title: '应用自管菜单',
    text: '该应用菜单自管:IAM 不下发菜单,应用改用权限码渲染本地菜单。此处配置仅用于维护权限映射。',
  },
};

/* ---------------- 应用与菜单数据 ---------------- */

const apps = ref<any[]>([]);
const appsLoading = ref(true);
const appsError = ref('');
const appId = ref('');
const rows = ref<any[]>([]);
const loading = ref(false);
const loadError = ref('');
const total = ref(0);

const selectedApp = computed(() => apps.value.find((app) => String(app.id) === appId.value) || null);
const modeHint = computed(() => MODE_HINTS[selectedApp.value?.menuMode || 'HYBRID'] || MODE_HINTS.HYBRID);
const canCreate = computed(() => can('menu:create'));

async function loadApps() {
  appsLoading.value = true;
  appsError.value = '';
  try {
    const page = await api<PageResult<any>>('/api/v1/admin/application-services', {
      query: { pageNum: 1, pageSize: 300 },
      silent: true,
    });
    apps.value = page?.records || [];
    const desired = String(route.query.appServiceId || '');
    if (desired && apps.value.some((app) => String(app.id) === desired)) {
      appId.value = desired;
    } else if (!appId.value && apps.value.length === 1) {
      appId.value = String(apps.value[0].id);
    }
    if (appId.value) await loadMenus();
  } catch (error) {
    appsError.value = errorMessage(error);
  } finally {
    appsLoading.value = false;
  }
}

async function loadMenus() {
  if (!appId.value) {
    rows.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const list = await api<any[]>('/api/v1/admin/menus', {
      query: { appServiceId: appId.value },
      silent: true,
    });
    rows.value = orderTree(list || []);
    total.value = rows.value.length;
  } catch (error) {
    rows.value = [];
    total.value = 0;
    loadError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

async function changeApp() {
  rows.value = [];
  total.value = 0;
  loadError.value = '';
  await loadMenus();
}

function orderTree(list: any[]): any[] {
  const byParent = new Map<string, any[]>();
  for (const row of list) {
    const key = row.parentId == null ? '0' : String(row.parentId);
    if (!byParent.has(key)) byParent.set(key, []);
    byParent.get(key)!.push(row);
  }
  for (const siblings of byParent.values()) {
    siblings.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || Number(a.id) - Number(b.id));
  }
  const result: any[] = [];
  const seen = new Set<any>();
  const walk = (parentKey: string, depth: number) => {
    for (const row of byParent.get(parentKey) || []) {
      if (seen.has(row.id)) continue;
      seen.add(row.id);
      result.push({ ...row, _depth: depth });
      walk(String(row.id), depth + 1);
    }
  };
  walk('0', 0);
  for (const row of list) {
    if (!seen.has(row.id)) result.push({ ...row, _depth: 0 });
  }
  return result;
}

/* ---------------- 编辑器 ---------------- */

const editorOpen = ref(false);
const editorLoading = ref(false);
const editorBusy = ref(false);
const editorRow = ref<any>(null);
const editorParent = ref<any>(null);
const editorError = ref('');
const editorFields = ref<FieldDef[]>([]);
const editorValues = ref<Record<string, any>>({});
const editorKey = ref(0);
const formRef = ref<InstanceType<typeof SchemaForm> | null>(null);

const isEdit = computed(() => Boolean(editorRow.value));

async function permissionOptions(label: string): Promise<FilterOption[]> {
  const base: FilterOption[] = [{ value: '', label }];
  if (!appId.value) return base;
  try {
    const page = await api<PageResult<any>>('/api/v1/admin/permissions', {
      query: { pageNum: 1, pageSize: 500, appServiceId: appId.value },
      silent: true,
    });
    return base.concat(
      (page?.records || []).map((permission: any) => ({
        value: permission.id,
        label: `${permission.permissionCode} · ${permission.permissionName}`,
      })),
    );
  } catch {
    return base;
  }
}

async function openEditor(row?: any, parent?: any) {
  if (!appId.value) {
    toast('请先选择所属应用', 'warn');
    return;
  }
  const edit = Boolean(row);
  editorRow.value = row || null;
  editorParent.value = parent || null;
  editorOpen.value = true;
  editorLoading.value = true;
  editorBusy.value = false;
  editorError.value = '';
  editorFields.value = [];
  editorValues.value = {};
  try {
    const options = await permissionOptions(edit ? '保持不变' : '不挂载权限');
    const parentName = edit
      ? row.parentId
        ? `#${row.parentId}`
        : '顶级菜单'
      : parent?.menuName || '顶级菜单';
    editorFields.value = edit
      ? [
          { name: 'appLabel', label: '所属应用', type: 'static', full: true },
          { name: 'parentLabel', label: '上级菜单', type: 'static', full: true },
          { name: 'menuCode', label: '菜单编码', type: 'static', full: true },
          { name: 'menuName', label: '菜单名称', required: true, placeholder: '如 我的报销' },
          { name: 'menuType', label: '类型', type: 'select', options: TYPES },
          { name: 'path', label: '前端路由', placeholder: '/expense/list' },
          { name: 'icon', label: '图标', placeholder: 'wallet' },
          {
            name: 'permissionId',
            label: '挂载权限',
            type: 'select',
            options,
            full: true,
            help: '只显示所属应用下的权限;选择「保持不变」不会解绑现有权限,解绑需删除后重建菜单',
          },
          { name: 'sortOrder', label: '排序值', type: 'number', placeholder: '数值越小越靠前' },
          { name: 'enabled', label: '启用菜单', type: 'switch', onText: '启用中', help: '停用后立即从应用菜单下发中移除' },
        ]
      : [
          { name: 'appLabel', label: '所属应用', type: 'static', full: true },
          { name: 'parentLabel', label: '上级菜单', type: 'static', full: true },
          {
            name: 'menuCode',
            label: '菜单编码',
            required: true,
            mono: true,
            placeholder: '如 expense.list',
            help: '应用内唯一,建议以应用编码为前缀;创建后不可修改',
          },
          { name: 'menuName', label: '菜单名称', required: true, placeholder: '如 我的报销' },
          { name: 'menuType', label: '类型', type: 'select', options: TYPES },
          { name: 'path', label: '前端路由', placeholder: '/expense/list', mono: true },
          { name: 'icon', label: '图标', placeholder: 'wallet' },
          {
            name: 'permissionId',
            label: '挂载权限',
            type: 'select',
            options,
            full: true,
            help: '只显示所属应用下的权限;挂载后:授予该菜单的角色成员即获得此权限',
          },
          { name: 'sortOrder', label: '排序值', type: 'number', placeholder: '数值越小越靠前' },
          { name: 'enabled', label: '启用菜单', type: 'switch', onText: '启用中' },
        ];
    editorValues.value = edit
      ? {
          appLabel: selectedApp.value ? `${selectedApp.value.appName}(${selectedApp.value.appCode})` : '—',
          parentLabel: parentName,
          menuCode: row.menuCode,
          menuName: row.menuName,
          menuType: row.menuType || 'MENU',
          path: row.path || '',
          icon: row.icon || '',
          permissionId: row.permissionId ? String(row.permissionId) : '',
          sortOrder: row.sortOrder ?? 0,
          enabled: row.enabled !== false,
        }
      : {
          appLabel: selectedApp.value ? `${selectedApp.value.appName}(${selectedApp.value.appCode})` : '—',
          parentLabel: parentName,
          menuCode: '',
          menuName: '',
          menuType: 'MENU',
          path: '',
          icon: '',
          permissionId: '',
          sortOrder: 0,
          enabled: true,
        };
    editorKey.value += 1;
  } catch (error) {
    editorError.value = errorMessage(error);
  } finally {
    editorLoading.value = false;
  }
}

async function saveEditor() {
  const form = formRef.value;
  if (!form) return;
  const check = await form.validate();
  if (!check.ok) return;
  const values = form.read();
  if (!isEdit.value && !/^[A-Za-z0-9_.:-]{2,100}$/.test(String(values.menuCode || ''))) {
    form.setError('菜单编码只能包含字母、数字、下划线、点、冒号、中划线,长度 2-100');
    return;
  }
  const permissionId = values.permissionId ? Number(values.permissionId) : null;
  editorBusy.value = true;
  try {
    if (isEdit.value) {
      await api(`/api/v1/admin/menus/${editorRow.value.id}`, {
        method: 'PUT',
        body: {
          menuName: values.menuName,
          menuType: values.menuType,
          path: values.path || null,
          icon: values.icon || null,
          permissionId,
          sortOrder: values.sortOrder ?? 0,
          enabled: Boolean(values.enabled),
        },
      });
      toast('菜单已保存', 'success');
    } else {
      await api('/api/v1/admin/menus', {
        method: 'POST',
        body: {
          appServiceId: Number(appId.value),
          parentId: editorParent.value?.id ?? null,
          menuCode: values.menuCode,
          menuName: values.menuName,
          menuType: values.menuType,
          path: values.path || null,
          icon: values.icon || null,
          permissionId,
          sortOrder: values.sortOrder ?? 0,
          enabled: Boolean(values.enabled),
        },
      });
      toast('菜单已创建', 'success');
    }
    editorOpen.value = false;
    await loadMenus();
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    editorBusy.value = false;
  }
}

async function removeMenu(row: any) {
  const ok = await confirmDialog({
    title: '删除菜单',
    message: `将删除「${row.menuName}」及其全部子项,并清理相关角色的菜单授予。`,
    detail: '相关账号的菜单权限在重新登录后生效。',
    confirmText: '删除菜单',
  });
  if (!ok) return;
  try {
    await api(`/api/v1/admin/menus/${row.id}`, { method: 'DELETE' });
    toast('菜单已删除,相关账号重新登录后生效', 'success');
    await loadMenus();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

/* ---------------- 表格 ---------------- */

const columns = [
  {
    label: '菜单',
    render: (row: any) =>
      h('div', { class: 'menu-node', style: { paddingLeft: `${(row._depth || 0) * 18}px` } }, [
        row._depth ? h('span', { class: 'menu-branch', 'aria-hidden': 'true' }) : null,
        h('div', { class: 'cell-stack menu-node-copy' }, [
          h('div', { class: 'cell-strong' }, row.menuName),
          h('div', { class: 'cell-sub cell-mono' }, row.menuCode),
        ]),
      ]),
  },
  {
    label: '类型',
    render: (row: any) =>
      row.menuType === 'BUTTON' ? badge('按钮', '', true) : badge('菜单', 'is-info'),
  },
  {
    label: '路由',
    render: (row: any) =>
      row.path
        ? h('span', { class: 'cell-mono truncate menu-path', title: row.path }, row.path)
        : muted('—'),
  },
  {
    label: '派生权限',
    render: (row: any) =>
      row.permissionCode
        ? badge(row.permissionCode, 'is-gold')
        : row.permissionId
          ? mono(`#${row.permissionId}`)
          : muted('—'),
  },
  {
    label: '排序',
    align: 'right' as const,
    render: (row: any) => muted(row.sortOrder ?? 0),
  },
  {
    label: '状态',
    render: (row: any) =>
      row.enabled === false ? dotBadge('已停用', 'is-danger') : dotBadge('启用中', 'is-success'),
  },
];

function rowActions(row: any) {
  return [
    ...(canCreate.value
      ? [{ label: '新建子项', onClick: () => openEditor(null, row) }]
      : []),
    ...(can('menu:update') ? [{ label: '编辑', onClick: () => openEditor(row) }] : []),
    ...(can('menu:delete')
      ? [{ label: '删除', danger: true, onClick: () => removeMenu(row) }]
      : []),
  ];
}

void loadApps();
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="应用菜单"
      desc="菜单按应用归属:授予菜单即派生菜单上挂载的权限。菜单只决定可见性,接口鉴权始终以权限码为准。"
    >
      <template #actions>
        <button
          v-if="canCreate"
          class="btn btn-primary"
          type="button"
          :disabled="!appId"
          @click="openEditor()"
        >
          <AppIcon name="plus" :size="15" />
          <span>新建菜单</span>
        </button>
      </template>
    </PageHeader>

    <section class="card menu-toolbar">
      <div class="menu-toolbar-main">
        <div class="filter menu-app-filter">
          <label class="filter-label" for="menu-app">所属应用</label>
          <select
            id="menu-app"
            v-model="appId"
            class="input select"
            :disabled="appsLoading || !apps.length"
            @change="changeApp"
          >
            <option value="">请选择应用</option>
            <option v-for="app in apps" :key="app.id" :value="String(app.id)">
              {{ app.appName }}({{ app.appCode }})
            </option>
          </select>
        </div>
        <div v-if="selectedApp" class="menu-app-meta">
          <span class="badge is-outline">{{ selectedApp.appType || 'WEB' }}</span>
          <span class="badge is-info">{{ selectedApp.ssoMode || 'OIDC' }}</span>
          <span class="menu-app-code">{{ selectedApp.appCode }}</span>
          <span v-if="selectedApp.enabled === false" class="badge is-danger">已停用</span>
        </div>
      </div>
      <button
        class="icon-btn"
        type="button"
        title="刷新菜单"
        aria-label="刷新菜单"
        :disabled="!appId || loading"
        @click="loadMenus"
      >
        <AppIcon name="refresh" :size="16" :class="{ spin: loading }" />
      </button>
    </section>

    <div v-if="appsError" class="alert is-danger menu-hint">
      <span>应用列表加载失败:{{ appsError }}</span>
      <button class="link-btn" type="button" @click="loadApps">重试</button>
    </div>

    <div v-else-if="appsLoading" class="alert is-info menu-hint">
      <span>正在加载应用列表…</span>
    </div>

    <div v-else-if="!apps.length" class="alert is-warn menu-hint">
      <span>当前域还没有应用服务。请先在「应用门户」创建应用,再维护菜单。</span>
    </div>

    <template v-else-if="!appId">
      <section class="card menu-blank">
        <span class="menu-blank-mark"><AppIcon name="menu" :size="24" /></span>
        <div class="menu-blank-title">先选择一个应用</div>
        <p class="menu-blank-hint">菜单必须归属一个应用服务;选择后即可维护该应用的菜单树与按钮权限。</p>
      </section>
    </template>

    <template v-else>
      <div class="alert menu-hint" :class="modeHint.tone">
        <span><strong>{{ modeHint.title }}</strong> · {{ modeHint.text }}</span>
      </div>

      <section class="card table-card">
        <div class="card-head">
          <div class="card-head-main">
            <div class="card-title">菜单树</div>
            <div class="card-sub">
              <span v-if="!loading && !loadError">共 {{ total }} 个节点 · 按父子层级展开</span>
              <span v-else-if="loading" class="card-sub-dim">正在同步菜单…</span>
              <span v-else class="card-sub-dim">数据暂不可用</span>
            </div>
          </div>
          <div class="card-actions">
            <span class="badge is-gold">{{ selectedApp?.menuMode || 'HYBRID' }}</span>
          </div>
        </div>

        <div v-if="loadError" class="load-error">
          <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
          <div class="load-error-copy">
            <strong>菜单加载失败</strong>
            <span>{{ loadError }}</span>
          </div>
          <button class="btn btn-sm" type="button" @click="loadMenus">重试</button>
        </div>

        <SkeletonTable v-else-if="loading" :rows="5" :columns="5" />
        <DataTable
          v-else
          :columns="columns"
          :rows="rows"
          :row-actions="rowActions"
          :row-click="can('menu:update') ? (row) => openEditor(row) : undefined"
          empty-text="该应用暂无菜单"
        />
      </section>
    </template>

    <BaseDrawer
      v-model:open="editorOpen"
      :title="isEdit ? `编辑菜单 · ${editorRow?.menuName || ''}` : (editorParent ? '新建子菜单' : '新建一级菜单')"
      :subtitle="isEdit ? '菜单编码与所属应用不可修改' : (editorParent ? `上级:${editorParent.menuName}` : '将创建在菜单树根部')"
      width="600px"
      :close-on-backdrop="false"
    >
      <SkeletonTable v-if="editorLoading" :rows="4" :columns="2" />
      <div v-else-if="editorError" class="alert is-danger">
        <span>{{ editorError }}</span>
      </div>
      <SchemaForm
        v-else
        :key="editorKey"
        ref="formRef"
        :fields="editorFields"
        :values="editorValues"
        :mode="isEdit ? 'edit' : 'create'"
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

<style scoped>
.menu-toolbar {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  padding: 15px 20px;
  background: linear-gradient(180deg, var(--surface), var(--surface-2));
}

.menu-toolbar-main {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
  min-width: 0;
}

.menu-app-filter {
  width: 320px;
}

.menu-app-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  min-width: 0;
}

.menu-app-code {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--muted);
}

.menu-toolbar > .icon-btn {
  margin-left: auto;
  margin-bottom: 4px;
}

.menu-hint {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}

.menu-blank {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 56px 24px;
  margin-top: 14px;
  background:
    radial-gradient(circle at 50% 0%, rgba(242, 181, 46, 0.1), transparent 55%),
    var(--surface);
}

.menu-blank-mark {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: 14px;
  border: 1px solid rgba(168, 115, 10, 0.24);
  border-radius: 16px;
  background: linear-gradient(150deg, var(--gold-soft), rgba(255, 253, 248, 0.7));
  color: var(--gold-deep);
}

.menu-blank-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.menu-blank-hint {
  max-width: 430px;
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.7;
}

.menu-node {
  display: flex;
  align-items: center;
  min-width: 0;
}

.menu-node-copy {
  min-width: 0;
}

.menu-branch {
  position: relative;
  width: 14px;
  height: 18px;
  margin-right: 4px;
  flex: none;
}

.menu-branch::before {
  content: '';
  position: absolute;
  left: 2px;
  top: -10px;
  bottom: 9px;
  border-left: 1px dashed var(--line-strong, rgba(16, 24, 40, 0.2));
}

.menu-branch::after {
  content: '';
  position: absolute;
  left: 2px;
  top: 9px;
  width: 9px;
  border-top: 1px dashed var(--line-strong, rgba(16, 24, 40, 0.2));
}

.menu-path {
  display: inline-block;
  max-width: 260px;
  vertical-align: bottom;
}

@media (max-width: 720px) {
  .menu-app-filter {
    width: 100%;
  }

  .menu-path {
    max-width: 140px;
  }
}
</style>
