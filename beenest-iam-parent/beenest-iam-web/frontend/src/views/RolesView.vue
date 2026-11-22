<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import BaseModal from '@/components/BaseModal.vue';
import EmptyState from '@/components/EmptyState.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import AppIcon from '@/components/AppIcon.vue';
import { api, errorMessage } from '@/lib/api';
import { fmtTime } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { FilterOption, ResourceConfig } from '@/lib/types';
import { badge, dotBadge, muted, strongSub } from '@/lib/ui';

async function loadClientOptions(): Promise<FilterOption[]> {
  try {
    const page = await api<any>('/api/v1/admin/clients', {
      query: { pageNum: 1, pageSize: 200 },
      silent: true,
    });
    return (page?.records || []).map((client: any) => ({
      value: client.id,
      label: `${client.clientName}(${client.clientKey})`,
    }));
  } catch {
    return [];
  }
}

const apps = ref<any[]>([]);
const appFilterOptions = reactive<FilterOption[]>([{ value: '', label: '全部应用' }]);

function appName(appServiceId: unknown): string {
  if (appServiceId === null || appServiceId === undefined) return '';
  const app = apps.value.find((item) => String(item.id) === String(appServiceId));
  return app ? `${app.appName}(${app.appCode})` : `#${appServiceId}`;
}

async function loadApps() {
  try {
    const page = await api<any>('/api/v1/admin/application-services', {
      query: { pageNum: 1, pageSize: 300 },
      silent: true,
    });
    apps.value = page?.records || [];
  } catch {
    apps.value = [];
  }
  appFilterOptions.splice(
    1,
    appFilterOptions.length - 1,
    ...apps.value.map((app) => ({
      value: String(app.id),
      label: `${app.appName}(${app.appCode})`,
    })),
  );
}

function appOptions(): FilterOption[] {
  return apps.value.map((app) => ({
    value: String(app.id),
    label: `${app.appName}(${app.appCode})`,
  }));
}

/** APP 角色与已绑定应用的客户端角色只能在所属应用内授权。 */
function roleScopedAppId(row: any): number | null {
  if (!row) return null;
  if (row.containerType === 'APP') return row.appServiceId ?? null;
  if (row.containerType === 'CLIENT' && row.appServiceId != null) return row.appServiceId;
  return null;
}

function containerBadge(row: any) {
  if (row.containerType === 'APP') {
    return badge(`应用角色${row.appServiceId ? ` · ${appName(row.appServiceId)}` : ''}`, 'is-gold');
  }
  if (row.containerType === 'CLIENT') {
    const suffix = row.appServiceId
      ? ` · ${appName(row.appServiceId)}`
      : row.clientId
        ? ` · 客户端 #${row.clientId}`
        : '';
    return badge(`客户端角色${suffix}`, 'is-info');
  }
  return badge('域角色', '', true);
}

/* ---------------- 权限授予 ---------------- */

const permissionOpen = ref(false);
const permissionTarget = ref<any>(null);
const permissionLoading = ref(false);
const permissionSaving = ref(false);
const permissionError = ref('');
const permissionItems = ref<any[]>([]);
const permissionSelected = ref<Array<string | number>>([]);

const permissionScopedAppId = computed(() => roleScopedAppId(permissionTarget.value));
const permissionAlert = computed(() => {
  const scopedAppId = permissionScopedAppId.value;
  if (scopedAppId != null) {
    return `该角色归属应用「${appName(scopedAppId)}」,只能授予本应用的权限;平台权限与其他应用的权限不会出现在列表中。菜单授予在「菜单」中维护,与直接权限取并集。`;
  }
  return '此处只维护角色直接权限;域角色可授予平台与各应用权限,菜单派生的权限在「菜单」中维护,二者取并集。权限在账号下一次登录时生效。';
});

async function openPermissions(row: any) {
  permissionTarget.value = row;
  permissionOpen.value = true;
  permissionLoading.value = true;
  permissionError.value = '';
  permissionItems.value = [];
  permissionSelected.value = [];
  try {
    if (!apps.value.length) await loadApps();
    const scopedAppId = roleScopedAppId(row);
    const query: Record<string, unknown> = { pageNum: 1, pageSize: 500 };
    if (scopedAppId != null) query.appServiceId = scopedAppId;
    const [page, granted] = await Promise.all([
      api<any>('/api/v1/admin/permissions', { query, silent: true }),
      api<any[]>(`/api/v1/admin/roles/${row.id}/permissions`, { silent: true }),
    ]);
    permissionItems.value = (page?.records || []).map((permission: any) => ({
      id: permission.id,
      label: permission.permissionName,
      hint: permission.permissionCode,
      group: `${permission.appServiceId ? appName(permission.appServiceId) : '平台权限'} · ${permission.resource || '其他资源'}`,
      tags: permission.action ? [{ text: permission.action }] : [],
    }));
    permissionSelected.value = (granted || []).map((permission: any) => permission.id);
  } catch (error) {
    permissionError.value = errorMessage(error);
  } finally {
    permissionLoading.value = false;
  }
}

async function savePermissions(ids: Array<string | number>) {
  if (!permissionTarget.value) return;
  permissionSaving.value = true;
  permissionError.value = '';
  try {
    await api(`/api/v1/admin/roles/${permissionTarget.value.id}/permissions`, {
      method: 'PUT',
      body: { permissionIds: ids },
      silent: true,
    });
    toast('角色权限已更新,相关账号重新登录后生效', 'success');
    permissionOpen.value = false;
  } catch (error) {
    permissionError.value = errorMessage(error);
  } finally {
    permissionSaving.value = false;
  }
}

/* ---------------- 菜单授予 ---------------- */

const menuOpen = ref(false);
const menuTarget = ref<any>(null);
const menuInitLoading = ref(false);
const menuInitError = ref('');
const menuApps = ref<any[]>([]);
const menuGranted = ref<Set<number>>(new Set());
const menuAppId = ref('');
const menuRows = ref<any[]>([]);
const menuRowsLoading = ref(false);
const menuError = ref('');
const menuSaveError = ref('');
const menuSaving = ref(false);
const menuKeyword = ref('');
const menuSelection = ref<Set<number>>(new Set());
const menuScopedAppId = ref<number | null>(null);
let menuDrafts = new Map<string, Set<number>>();
let menuAppMenus = new Map<string, any[]>();

const menuCurrentApp = computed(() => menuApps.value.find((app) => String(app.id) === menuAppId.value));

const menuOrdered = computed(() => {
  const byParent = new Map<string, any[]>();
  for (const row of menuRows.value) {
    const key = row.parentId == null ? '0' : String(row.parentId);
    const bucket = byParent.get(key);
    if (bucket) bucket.push(row);
    else byParent.set(key, [row]);
  }
  const result: any[] = [];
  const walk = (parentKey: string, depth: number) => {
    for (const row of byParent.get(parentKey) || []) {
      if (result.some((item) => item.id === row.id)) continue;
      result.push({ ...row, _depth: depth });
      walk(String(row.id), depth + 1);
    }
  };
  walk('0', 0);
  for (const row of menuRows.value) {
    if (!result.some((item) => item.id === row.id)) result.push({ ...row, _depth: 0 });
  }
  return result;
});

const menuVisible = computed(() => {
  const keyword = menuKeyword.value.trim().toLowerCase();
  if (!keyword) return menuOrdered.value;
  return menuOrdered.value.filter((row) =>
    `${row.menuName} ${row.menuCode} ${row.permissionCode || ''}`.toLowerCase().includes(keyword),
  );
});

const menuSelectedCount = computed(() => menuSelection.value.size);
const menuAllVisibleSelected = computed(
  () => menuVisible.value.length > 0 && menuVisible.value.every((row) => menuSelection.value.has(Number(row.id))),
);

function rememberMenuDraft() {
  if (menuAppId.value) menuDrafts.set(menuAppId.value, new Set(menuSelection.value));
}

function toggleMenu(row: any) {
  const id = Number(row.id);
  const next = new Set(menuSelection.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  menuSelection.value = next;
  rememberMenuDraft();
}

function toggleAllVisibleMenus() {
  const next = new Set(menuSelection.value);
  const all = menuAllVisibleSelected.value;
  for (const row of menuVisible.value) {
    const id = Number(row.id);
    if (all) next.delete(id);
    else next.add(id);
  }
  menuSelection.value = next;
  rememberMenuDraft();
}

async function loadAppMenus(appId: string) {
  if (!appId) {
    menuRows.value = [];
    menuSelection.value = new Set();
    return;
  }
  menuRowsLoading.value = true;
  menuError.value = '';
  try {
    const rows = await api<any[]>('/api/v1/admin/menus', {
      query: { appServiceId: appId },
      silent: true,
    });
    menuAppMenus.set(appId, rows || []);
    menuRows.value = rows || [];
    const draft = menuDrafts.get(appId);
    if (draft) {
      menuSelection.value = new Set(draft);
    } else {
      const initial = new Set<number>(
        menuRows.value
          .filter((row) => menuGranted.value.has(Number(row.id)))
          .map((row) => Number(row.id)),
      );
      menuDrafts.set(appId, initial);
      menuSelection.value = new Set(initial);
    }
  } catch (error) {
    menuRows.value = [];
    menuError.value = errorMessage(error);
  } finally {
    menuRowsLoading.value = false;
  }
}

async function onMenuAppChange(event: Event) {
  const next = (event.target as HTMLSelectElement).value;
  if (next === menuAppId.value) return;
  rememberMenuDraft();
  menuAppId.value = next;
  menuKeyword.value = '';
  await loadAppMenus(next);
}

async function openMenus(row: any) {
  menuTarget.value = row;
  menuOpen.value = true;
  menuInitLoading.value = true;
  menuInitError.value = '';
  menuApps.value = [];
  menuGranted.value = new Set();
  menuRows.value = [];
  menuSelection.value = new Set();
  menuKeyword.value = '';
  menuAppId.value = '';
  menuScopedAppId.value = roleScopedAppId(row);
  menuSaveError.value = '';
  menuDrafts = new Map();
  menuAppMenus = new Map();
  try {
    const [appPage, granted] = await Promise.all([
      api<any>('/api/v1/admin/application-services', { query: { pageNum: 1, pageSize: 300 }, silent: true }),
      api<any[]>(`/api/v1/admin/roles/${row.id}/menus`, { silent: true }),
    ]);
    const scopedAppId = menuScopedAppId.value;
    menuApps.value = (appPage?.records || []).filter(
      (app: any) => scopedAppId == null || Number(app.id) === scopedAppId,
    );
    menuGranted.value = new Set((granted || []).map((menu: any) => Number(menu.id)));
    if (scopedAppId != null && !menuApps.value.length) {
      throw new Error('角色所属应用不存在或已删除,无法维护菜单');
    }
    menuInitLoading.value = false;
    if (menuApps.value.length) {
      menuAppId.value = String(menuApps.value[0].id);
      await loadAppMenus(menuAppId.value);
    }
  } catch (error) {
    menuInitError.value = errorMessage(error);
    menuInitLoading.value = false;
  }
}

async function saveMenus() {
  if (!menuTarget.value) return;
  rememberMenuDraft();
  // 应用角色的菜单作用域固定:只提交所属应用的菜单,不再保留其他应用的授予
  const result = menuScopedAppId.value != null ? new Set<number>() : new Set(menuGranted.value);
  if (menuScopedAppId.value == null) {
    for (const rows of menuAppMenus.values()) {
      for (const row of rows) result.delete(Number(row.id));
    }
  }
  for (const selected of menuDrafts.values()) {
    for (const id of selected) result.add(id);
  }
  menuSaving.value = true;
  menuSaveError.value = '';
  try {
    await api(`/api/v1/admin/roles/${menuTarget.value.id}/menus`, {
      method: 'PUT',
      body: { menuIds: [...result] },
      silent: true,
    });
    menuGranted.value = new Set(result);
    toast('角色菜单已更新,相关账号重新登录后生效', 'success');
    menuOpen.value = false;
  } catch (error) {
    menuSaveError.value = errorMessage(error);
  } finally {
    menuSaving.value = false;
  }
}

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '角色',
  desc: '角色是权限的集合:域角色面向整个身份域,应用角色与已绑定应用的客户端角色只在所属应用内生效。',
  endpoint: '/api/v1/admin/roles',
  permCreate: 'role:create',
  createLabel: '新建角色',
  deleteName: '角色',
  rowName: (row) => row.roleName,
  cardTitle: '角色矩阵',
  filters: [
    { name: 'roleCode', label: '角色编码', placeholder: '支持模糊匹配' },
    { name: 'roleName', label: '角色名称', placeholder: '支持模糊匹配' },
    {
      name: 'containerType',
      label: '角色类型',
      type: 'select',
      options: [
        { value: '', label: '全部类型' },
        { value: 'REALM', label: '域角色' },
        { value: 'APP', label: '应用角色' },
        { value: 'CLIENT', label: '客户端角色' },
      ],
    },
    { name: 'appServiceId', label: '所属应用', type: 'select', options: appFilterOptions },
  ],
  columns: [
    { label: '角色', render: (row) => strongSub(row.roleName, row.roleCode) },
    { label: '归属', render: (row) => containerBadge(row) },
    {
      label: '状态',
      render: (row) =>
        row.isActive === false
          ? dotBadge('已停用', 'is-danger')
          : dotBadge('启用中', 'is-success'),
    },
    {
      label: '来源',
      render: (row) => (row.isSystem ? badge('系统内置', 'is-gold') : badge('自定义')),
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '权限', perm: 'role:assign-permission', onClick: () => openPermissions(row) },
    { label: '菜单', perm: 'role:assign-menu', onClick: () => openMenus(row) },
    { label: '编辑', perm: 'role:update', onClick: () => ctx?.openEditor(row) },
    {
      label: '删除',
      perm: 'role:delete',
      danger: true,
      hidden: (item) => Boolean(item.isSystem),
      onClick: () => ctx?.remove(row),
    },
  ],
  form: {
    title: '角色',
    subtitle: '角色编码创建后不可修改;归属只在角色尚未被分配或授权时可改;停用角色会立即退出权限计算',
    fields: async () => [
      { name: 'roleCode', label: '角色编码', required: true, mono: true, placeholder: '如 iam-admin' },
      { name: 'roleName', label: '角色名称', required: true, placeholder: '如 身份管理员' },
      {
        name: 'containerType',
        label: '角色类型',
        type: 'select',
        options: [
          { value: 'REALM', label: '域角色' },
          { value: 'APP', label: '应用角色' },
          { value: 'CLIENT', label: '客户端角色(兼容存量)' },
        ],
        help: '域角色可跨应用授权;应用角色只能在所属应用内授权',
      },
      {
        name: 'appServiceId',
        label: '所属应用',
        type: 'select',
        options: appOptions(),
        placeholder: '不关联应用',
        help: '应用角色必填;客户端角色可由绑定的客户端自动推导',
      },
      {
        name: 'clientId',
        label: '关联客户端',
        type: 'select',
        options: await loadClientOptions(),
        placeholder: '不关联客户端',
        help: '仅客户端角色需要选择;已绑定应用的客户端会收窄为应用作用域',
      },
    ],
    editFields: async () => {
      if (!apps.value.length) await loadApps();
      return [
        { name: 'roleName', label: '角色名称', required: true, full: true },
        { name: 'isActive', label: '启用角色', type: 'switch', onText: '启用中' },
        {
          name: 'containerType',
          label: '角色类型',
          type: 'select',
          options: [
            { value: 'REALM', label: '域角色' },
            { value: 'APP', label: '应用角色' },
            { value: 'CLIENT', label: '客户端角色(兼容存量)' },
          ],
          help: '归属决定角色的授权面;只有尚未被分配、未授予权限的角色可以变更',
        },
        {
          name: 'appServiceId',
          label: '所属应用',
          type: 'select',
          options: appOptions(),
          placeholder: '不关联应用',
          help: '应用角色必填;客户端角色由绑定的客户端自动推导',
        },
        {
          name: 'clientId',
          label: '关联客户端',
          type: 'select',
          options: await loadClientOptions(),
          placeholder: '不关联客户端',
          help: '仅客户端角色需要选择;已绑定应用的客户端会收窄为应用作用域',
        },
      ];
    },
    defaults: { containerType: 'REALM', appServiceId: '', clientId: '' },
    fromRow: (row) => ({
      roleName: row.roleName,
      isActive: row.isActive !== false,
      containerType: row.containerType || 'REALM',
      appServiceId: row.appServiceId == null ? '' : String(row.appServiceId),
      clientId: row.clientId == null ? '' : String(row.clientId),
    }),
    toUpdate: (values) => ({
      roleName: values.roleName,
      isActive: values.isActive,
      containerType: values.containerType,
      appServiceId:
        values.containerType === 'APP' && values.appServiceId ? Number(values.appServiceId) : null,
      clientId:
        values.containerType === 'CLIENT' && values.clientId ? Number(values.clientId) : null,
    }),
    validate: (values) => {
      if (values.containerType === 'APP' && !values.appServiceId) {
        return '应用角色必须指定所属应用';
      }
      if (values.containerType === 'CLIENT' && !values.clientId) {
        return '客户端角色必须指定所属客户端';
      }
    },
    toCreate: (values) => ({
      roleCode: values.roleCode,
      roleName: values.roleName,
      containerType: values.containerType,
      appServiceId:
        values.containerType === 'APP' && values.appServiceId ? Number(values.appServiceId) : null,
      clientId:
        values.containerType === 'CLIENT' && values.clientId ? Number(values.clientId) : null,
    }),
  },
};

void loadApps();
</script>

<template>
  <ResourcePage :config="config" />

  <AssignmentModal
    v-model:open="permissionOpen"
    :title="`角色权限 · ${permissionTarget?.roleName || ''}`"
    subtitle="按应用与资源分组维护角色的直接权限"
    :items="permissionItems"
    :model-value="permissionSelected"
    :loading="permissionLoading"
    :saving="permissionSaving"
    :error="permissionError"
    :alert="permissionAlert"
    search-placeholder="按权限名称或权限码过滤…"
    save-label="保存授权"
    @save="savePermissions"
  />

  <BaseModal
    v-model:open="menuOpen"
    :title="`角色菜单 · ${menuTarget?.roleName || ''}`"
    subtitle="菜单授予派生菜单上挂载的权限,按应用分批维护"
    size="lg"
    :close-on-backdrop="!menuSaving"
  >
    <div class="menu-assign">
      <div class="alert is-warn">
        <AppIcon name="info" :size="15" />
        <span v-if="menuScopedAppId != null">
          当前为应用角色,只能维护「{{ appName(menuScopedAppId) }}」的菜单;保存以该应用的选择为准,菜单权限与直接权限求并集。
        </span>
        <span v-else>保存时只覆盖当前浏览过的应用,其他应用的既有授予保持不变;菜单权限与直接权限求并集。</span>
      </div>

      <div v-if="menuSaveError" class="alert is-danger">
        <AppIcon name="alert" :size="15" />
        <span>{{ menuSaveError }}</span>
      </div>

      <SkeletonTable v-if="menuInitLoading" :rows="4" :columns="2" />
      <div v-else-if="menuInitError" class="alert is-danger">
        <AppIcon name="alert" :size="15" />
        <span>{{ menuInitError }}</span>
      </div>
      <template v-else>
        <div class="menu-toolbar">
          <label class="menu-app">
            <span class="filter-label">所属应用</span>
            <select class="input select" :value="menuAppId" :disabled="menuScopedAppId != null" @change="onMenuAppChange">
              <option v-if="!menuApps.length" value="">暂无应用</option>
              <option v-for="app in menuApps" :key="app.id" :value="String(app.id)">
                {{ app.appName }}({{ app.appCode }})
              </option>
            </select>
          </label>
          <div class="menu-search">
            <AppIcon name="search" :size="15" />
            <input v-model="menuKeyword" class="input" type="search" placeholder="按菜单名称或编码过滤…" />
          </div>
          <span class="menu-count">已选 <strong>{{ menuSelectedCount }}</strong> 项</span>
        </div>

        <div v-if="menuCurrentApp?.menuMode" class="menu-mode">
          <span class="badge is-gold">{{ menuCurrentApp.menuMode }}</span>
          <span>
            {{
              menuCurrentApp.menuMode === 'IAM'
                ? '该应用菜单全量由 IAM 托管,应用直接渲染菜单树。'
                : menuCurrentApp.menuMode === 'APP'
                  ? '该应用菜单自管,IAM 不下发菜单,应用改用权限码渲染本地菜单。'
                  : 'IAM 只托管一级模块,深层页面由应用自管,双方通过权限码对齐。'
            }}
          </span>
        </div>

        <SkeletonTable v-if="menuRowsLoading" :rows="5" :columns="2" />
        <div v-else-if="menuError" class="alert is-danger">
          <AppIcon name="alert" :size="15" />
          <span>{{ menuError }}</span>
        </div>
        <EmptyState
          v-else-if="!menuVisible.length"
          :title="menuRows.length ? '没有匹配的菜单' : '该应用暂无菜单'"
          :hint="menuRows.length ? '调整搜索条件后重试' : '请先在「应用菜单」页创建菜单与按钮'"
          icon="menu"
        />
        <template v-else>
          <div class="menu-tree-head">
            <span>菜单项</span>
            <button class="link-btn" type="button" @click="toggleAllVisibleMenus">
              {{ menuAllVisibleSelected ? '取消当前列表' : '全选当前列表' }}
            </button>
          </div>
          <div class="menu-tree">
            <label
              v-for="row in menuVisible"
              :key="row.id"
              class="menu-row"
              :class="{ 'is-checked': menuSelection.has(Number(row.id)) }"
            >
              <input
                class="checkbox"
                type="checkbox"
                :checked="menuSelection.has(Number(row.id))"
                @change="toggleMenu(row)"
              />
              <span class="menu-row-copy" :style="{ paddingLeft: `${row._depth * 16}px` }">
                <span class="menu-row-title">
                  <span v-if="row._depth" class="menu-branch" aria-hidden="true">↳</span>
                  {{ row.menuName }}
                  <span v-if="row.enabled === false" class="badge is-danger">已停用</span>
                  <span v-if="row.menuType === 'BUTTON'" class="badge is-outline">按钮</span>
                </span>
                <span class="cell-mono menu-row-code">{{ row.menuCode }}</span>
              </span>
              <span v-if="row.permissionCode" class="badge is-gold">{{ row.permissionCode }}</span>
              <span v-else class="muted menu-row-none">不派生权限</span>
            </label>
          </div>
        </template>
      </template>
    </div>

    <template #footer>
      <button class="btn btn-ghost" type="button" :disabled="menuSaving" @click="menuOpen = false">取消</button>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="menuInitLoading || menuRowsLoading || !menuApps.length || Boolean(menuInitError) || menuSaving"
        @click="saveMenus"
      >
        <span v-if="menuSaving" class="btn-spinner" />
        <span>保存菜单</span>
      </button>
    </template>
  </BaseModal>
</template>

<style scoped>
.menu-assign {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.menu-toolbar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.menu-app {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 240px;
}

.menu-search {
  position: relative;
  flex: 1;
  min-width: 200px;
  display: flex;
  align-items: center;
}

.menu-search > svg {
  position: absolute;
  left: 11px;
  color: var(--faint);
  pointer-events: none;
}

.menu-search .input {
  padding-left: 33px;
}

.menu-count {
  font-size: 11.5px;
  color: var(--muted);
  white-space: nowrap;
  padding-bottom: 9px;
}

.menu-count strong {
  font-family: var(--font-mono);
  color: var(--ink);
}

.menu-mode {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 11px;
  border-radius: 9px;
  border: 1px dashed var(--line);
  background: var(--surface-2);
  font-size: 11.5px;
  color: var(--muted);
}

.menu-tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--faint);
  padding-bottom: 6px;
  border-bottom: 1px dashed var(--line);
}

.menu-tree {
  display: flex;
  flex-direction: column;
  max-height: 44vh;
  overflow-y: auto;
  padding-right: 3px;
}

.menu-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 9px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
}

.menu-row:hover {
  background: var(--surface-2);
}

.menu-row.is-checked {
  background: rgba(242, 181, 46, 0.09);
  border-color: rgba(168, 115, 10, 0.22);
}

.menu-row-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.menu-row-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
}

.menu-branch {
  color: var(--faint);
  font-family: var(--font-mono);
}

.menu-row-code {
  font-size: 10.5px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-row-none {
  font-size: 10.5px;
  white-space: nowrap;
}

@media (max-width: 560px) {
  .menu-row-copy {
    flex-direction: column;
    gap: 2px;
  }

  .menu-row {
    align-items: flex-start;
  }
}
</style>
