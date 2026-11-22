<script setup lang="ts">
import { computed, h, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import AppBackfillDrawer from '@/components/AppBackfillDrawer.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import DataTable from '@/components/DataTable.vue';
import PageHeader from '@/components/PageHeader.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime } from '@/lib/format';
import type { IconName } from '@/lib/icons';
import { toast } from '@/lib/toast';
import type { ColumnDef, FieldDef, RowAction } from '@/lib/types';
import { badge, dotBadge, mono, muted, strongSub } from '@/lib/ui';
import { auth, can } from '@/stores/auth';

type TabKey = 'overview' | 'menus' | 'roles' | 'permissions' | 'mappings' | 'admins';

interface MappingSource {
  id: number;
  code: string;
  name: string;
}

const TABS: Array<{ key: TabKey; label: string; icon: IconName }> = [
  { key: 'overview', label: '概览', icon: 'grid' },
  { key: 'menus', label: '菜单', icon: 'menu' },
  { key: 'roles', label: '角色', icon: 'shield' },
  { key: 'permissions', label: '权限', icon: 'key' },
  { key: 'mappings', label: '角色映射', icon: 'mapping' },
  { key: 'admins', label: '管理员', icon: 'users' },
];

const MENU_TYPES = [
  { value: 'MENU', label: '菜单' },
  { value: 'BUTTON', label: '按钮' },
];

const MENU_MODE_LABEL: Record<string, string> = {
  IAM: 'IAM 全量托管',
  HYBRID: '仅一级模块托管',
  APP: '应用自管',
};

const AUTH_MODE_LABEL: Record<string, string> = {
  IAM_MANAGED: 'IAM 托管授权',
  MAPPED: '角色映射 · MAPPED',
  APP_MANAGED: '应用自管',
};

const route = useRoute();
const router = useRouter();

/* ---------------- 应用选择 ---------------- */

const managedApps = computed(() => auth.managedApps);

function normalizeTab(value: unknown): TabKey {
  return TABS.some((tab) => tab.key === value) ? (value as TabKey) : 'overview';
}

function initialAppId(): string {
  const desired = typeof route.query.app === 'string' ? route.query.app : '';
  if (desired && managedApps.value.some((app) => String(app.id) === desired)) return desired;
  return managedApps.value.length === 1 ? String(managedApps.value[0].id) : '';
}

const appId = ref(initialAppId());
const activeTab = ref<TabKey>(normalizeTab(route.query.tab));
const selectedApp = computed(
  () => managedApps.value.find((app) => String(app.id) === appId.value) || null,
);

function workspacePath(id = appId.value): string {
  return `/api/v1/admin/apps/${id}/workspace`;
}

function appPath(id = appId.value): string {
  return `/api/v1/admin/apps/${id}`;
}

watch([appId, activeTab], () => {
  const query = { ...route.query };
  if (appId.value) query.app = appId.value;
  else delete query.app;
  if (activeTab.value === 'overview') delete query.tab;
  else query.tab = activeTab.value;
  void router.replace({ query });
});

/* ---------------- 数据加载 ---------------- */

const overview = ref<any>(null);
const overviewLoading = ref(false);
const overviewError = ref('');

const menus = ref<any[]>([]);
const menusLoading = ref(false);
const menusError = ref('');

const roles = ref<any[]>([]);
const rolesLoading = ref(false);
const rolesError = ref('');

const permissions = ref<any[]>([]);
const permissionsLoading = ref(false);
const permissionsError = ref('');

const mappings = ref<any[]>([]);
const mappingsLoading = ref(false);
const mappingsError = ref('');

const admins = ref<any[]>([]);
const adminsLoading = ref(false);
const adminsError = ref('');

const mappingSources = ref<MappingSource[]>([]);
const mappingSourcesLoading = ref(false);
const mappingSourcesError = ref('');

const refreshing = computed(
  () =>
    overviewLoading.value ||
    menusLoading.value ||
    rolesLoading.value ||
    permissionsLoading.value ||
    mappingsLoading.value ||
    adminsLoading.value,
);

const tabCounts = computed<Record<string, number>>(() => ({
  menus: menus.value.length,
  roles: roles.value.length,
  permissions: permissions.value.length,
  mappings: mappings.value.length,
  admins: admins.value.length,
}));

const metrics = computed(() => [
  { tab: 'menus' as TabKey, label: '菜单节点', value: menus.value.length, icon: 'menu' as IconName },
  { tab: 'roles' as TabKey, label: '应用角色', value: roles.value.length, icon: 'shield' as IconName },
  { tab: 'permissions' as TabKey, label: '应用权限', value: permissions.value.length, icon: 'key' as IconName },
  { tab: 'mappings' as TabKey, label: '角色映射', value: mappings.value.length, icon: 'mapping' as IconName },
  { tab: 'admins' as TabKey, label: '应用管理员', value: admins.value.length, icon: 'users' as IconName },
]);

const menuById = computed(() => new Map(menus.value.map((row) => [Number(row.id), row])));

const menuTree = computed(() => orderTree(menus.value));

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

function menuParentName(row: any): string {
  const parent = row?.parentId ? menuById.value.get(Number(row.parentId)) : null;
  return parent?.menuName || (row?.parentId ? `#${row.parentId}` : '顶级菜单');
}

async function loadOverview() {
  const id = appId.value;
  if (!id) return;
  overviewLoading.value = true;
  overviewError.value = '';
  try {
    const data = await api<any>(workspacePath(id), { silent: true });
    if (id !== appId.value) return;
    overview.value = data;
  } catch (error) {
    if (id !== appId.value) return;
    overview.value = null;
    overviewError.value = errorMessage(error);
  } finally {
    if (id === appId.value) overviewLoading.value = false;
  }
}

async function loadMenus() {
  const id = appId.value;
  if (!id) return;
  menusLoading.value = true;
  menusError.value = '';
  try {
    const list = await api<any[]>(`${workspacePath(id)}/menus`, { silent: true });
    if (id !== appId.value) return;
    menus.value = Array.isArray(list) ? list : [];
  } catch (error) {
    if (id !== appId.value) return;
    menus.value = [];
    menusError.value = errorMessage(error);
  } finally {
    if (id === appId.value) menusLoading.value = false;
  }
}

async function loadRoles() {
  const id = appId.value;
  if (!id) return;
  rolesLoading.value = true;
  rolesError.value = '';
  try {
    const list = await api<any[]>(`${workspacePath(id)}/roles`, { silent: true });
    if (id !== appId.value) return;
    roles.value = Array.isArray(list) ? list : [];
  } catch (error) {
    if (id !== appId.value) return;
    roles.value = [];
    rolesError.value = errorMessage(error);
  } finally {
    if (id === appId.value) rolesLoading.value = false;
  }
}

async function loadPermissions() {
  const id = appId.value;
  if (!id) return;
  permissionsLoading.value = true;
  permissionsError.value = '';
  try {
    const list = await api<any[]>(`${workspacePath(id)}/permissions`, { silent: true });
    if (id !== appId.value) return;
    permissions.value = Array.isArray(list) ? list : [];
  } catch (error) {
    if (id !== appId.value) return;
    permissions.value = [];
    permissionsError.value = errorMessage(error);
  } finally {
    if (id === appId.value) permissionsLoading.value = false;
  }
}

async function loadMappings() {
  const id = appId.value;
  if (!id) return;
  mappingsLoading.value = true;
  mappingsError.value = '';
  try {
    const list = await api<any[]>(`${appPath(id)}/role-mappings`, { silent: true });
    if (id !== appId.value) return;
    mappings.value = Array.isArray(list) ? list : [];
  } catch (error) {
    if (id !== appId.value) return;
    mappings.value = [];
    mappingsError.value = errorMessage(error);
  } finally {
    if (id === appId.value) mappingsLoading.value = false;
  }
}

async function loadAdmins() {
  const id = appId.value;
  if (!id) return;
  adminsLoading.value = true;
  adminsError.value = '';
  try {
    const list = await api<any[]>(`${appPath(id)}/admins`, { silent: true });
    if (id !== appId.value) return;
    admins.value = Array.isArray(list) ? list : [];
  } catch (error) {
    if (id !== appId.value) return;
    admins.value = [];
    adminsError.value = errorMessage(error);
  } finally {
    if (id === appId.value) adminsLoading.value = false;
  }
}

async function loadMappingSources() {
  const id = appId.value;
  if (!id) return;
  const type = mappingSourceType.value;
  const keyword = mappingKeyword.value.trim();
  mappingSourcesLoading.value = true;
  mappingSourcesError.value = '';
  try {
    const list = await api<MappingSource[]>(`${workspacePath(id)}/mapping-sources`, {
      query: { sourceType: type, keyword: keyword || undefined },
      silent: true,
    });
    if (id !== appId.value || type !== mappingSourceType.value || keyword !== mappingKeyword.value.trim()) {
      return;
    }
    mappingSources.value = Array.isArray(list) ? list : [];
    if (mappingSourceId.value && !mappingSources.value.some((item) => String(item.id) === mappingSourceId.value)) {
      mappingSourceId.value = '';
    }
  } catch (error) {
    if (id !== appId.value) return;
    mappingSources.value = [];
    mappingSourcesError.value = errorMessage(error);
  } finally {
    if (id === appId.value) mappingSourcesLoading.value = false;
  }
}

async function loadAll() {
  if (!appId.value) return;
  await Promise.allSettled([
    loadOverview(),
    loadMenus(),
    loadRoles(),
    loadPermissions(),
    loadMappings(),
    loadAdmins(),
    loadMappingSources(),
  ]);
}

/* ---------------- 概览 ---------------- */

const overviewFacts = computed(() => {
  const data = overview.value?.app;
  if (!data) return [];
  return [
    { label: '应用编码', value: data.appCode || '—', mono: true },
    { label: '应用类型', value: data.appType || 'WEB' },
    { label: '单点模式', value: data.ssoMode || 'OIDC' },
    { label: '菜单托管', value: MENU_MODE_LABEL[data.menuMode] || data.menuMode || '—' },
    { label: '授权模式', value: AUTH_MODE_LABEL[data.authorizationMode] || data.authorizationMode || '—' },
    {
      label: '令牌档位',
      value:
        data.tokenProfile === 'SCOPED'
          ? 'SCOPED · 仅本应用权限'
          : data.tokenProfile === 'LEGACY_GLOBAL'
            ? 'LEGACY_GLOBAL · 全域权限'
            : data.tokenProfile || '—',
    },
    { label: '可见性', value: data.visibility || '—' },
    { label: '入口地址', value: data.entryUrl || '—', mono: true },
  ];
});

/* ---------------- 菜单 ---------------- */

const menuOpen = ref(false);
const menuEditing = ref<any>(null);
const menuParent = ref<any>(null);
const menuFields = ref<FieldDef[]>([]);
const menuValues = ref<Record<string, any>>({});
const menuFormKey = ref(0);
const menuFormRef = ref<InstanceType<typeof SchemaForm> | null>(null);
const menuSaving = ref(false);

const menuDrawerTitle = computed(() =>
  menuEditing.value
    ? `编辑菜单 · ${menuEditing.value.menuName}`
    : menuParent.value
      ? '新建子菜单'
      : '新建一级菜单',
);

const menuDrawerSubtitle = computed(() =>
  menuEditing.value
    ? '菜单编码与所属应用不可修改'
    : menuParent.value
      ? `上级:${menuParent.value.menuName}`
      : '将创建在菜单树根部',
);

function menuPermissionOptions(label: string) {
  return [
    { value: '', label },
    ...permissions.value.map((permission) => ({
      value: String(permission.id),
      label: `${permission.permissionCode} · ${permission.permissionName}`,
    })),
  ];
}

function openMenuEditor(row?: any, parent?: any) {
  const editing = Boolean(row);
  menuEditing.value = row || null;
  menuParent.value = parent || null;
  const options = menuPermissionOptions(editing ? '保持不变' : '不挂载权限');
  const parentLabel = editing ? menuParentName(row) : parent?.menuName || '顶级菜单';
  menuFields.value = editing
    ? [
        { name: 'parentLabel', label: '上级菜单', type: 'static', full: true },
        { name: 'codeLabel', label: '菜单编码', type: 'static', full: true },
        { name: 'menuName', label: '菜单名称', required: true, placeholder: '如 我的报销' },
        { name: 'menuType', label: '类型', type: 'select', options: MENU_TYPES },
        { name: 'path', label: '前端路由', placeholder: '/expense/list', mono: true },
        { name: 'icon', label: '图标', placeholder: 'wallet' },
        {
          name: 'permissionId',
          label: '挂载权限',
          type: 'select',
          options,
          full: true,
          help: '选择「保持不变」不会解绑现有权限;解绑需删除菜单后重建',
        },
        { name: 'sortOrder', label: '排序值', type: 'number', placeholder: '数值越小越靠前' },
        { name: 'enabled', label: '启用菜单', type: 'switch', onText: '启用中' },
      ]
    : [
        { name: 'parentLabel', label: '上级菜单', type: 'static', full: true },
        {
          name: 'menuCode',
          label: '菜单编码',
          required: true,
          mono: true,
          placeholder: '如 expense.list',
          help: '应用内唯一;创建后不可修改',
        },
        { name: 'menuName', label: '菜单名称', required: true, placeholder: '如 我的报销' },
        { name: 'menuType', label: '类型', type: 'select', options: MENU_TYPES },
        { name: 'path', label: '前端路由', placeholder: '/expense/list', mono: true },
        { name: 'icon', label: '图标', placeholder: 'wallet' },
        {
          name: 'permissionId',
          label: '挂载权限',
          type: 'select',
          options,
          full: true,
          help: '挂载后,授予该菜单的角色成员即获得此权限',
        },
        { name: 'sortOrder', label: '排序值', type: 'number', placeholder: '数值越小越靠前' },
        { name: 'enabled', label: '启用菜单', type: 'switch', onText: '启用中' },
      ];
  menuValues.value = editing
    ? {
        parentLabel,
        codeLabel: row.menuCode,
        menuName: row.menuName,
        menuType: row.menuType || 'MENU',
        path: row.path || '',
        icon: row.icon || '',
        permissionId: row.permissionId ? String(row.permissionId) : '',
        sortOrder: row.sortOrder ?? 0,
        enabled: row.enabled !== false,
      }
    : {
        parentLabel,
        menuCode: '',
        menuName: '',
        menuType: 'MENU',
        path: '',
        icon: '',
        permissionId: '',
        sortOrder: 0,
        enabled: true,
      };
  menuFormKey.value += 1;
  menuOpen.value = true;
}

async function saveMenu() {
  const form = menuFormRef.value;
  const id = appId.value;
  if (!form || !id) return;
  const check = await form.validate();
  if (!check.ok) return;
  const values = form.read();
  if (!menuEditing.value && !/^[A-Za-z0-9_.:-]{2,100}$/.test(String(values.menuCode || ''))) {
    form.setError('菜单编码只能包含字母、数字、下划线、点、冒号、中划线,长度 2-100');
    return;
  }
  const permissionId = values.permissionId ? Number(values.permissionId) : null;
  menuSaving.value = true;
  try {
    if (menuEditing.value) {
      await api(`${workspacePath(id)}/menus/${menuEditing.value.id}`, {
        method: 'PUT',
        silent: true,
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
      await api(`${workspacePath(id)}/menus`, {
        method: 'POST',
        silent: true,
        body: {
          appServiceId: Number(id),
          parentId: menuParent.value?.id ?? null,
          menuCode: String(values.menuCode || '').trim(),
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
    menuOpen.value = false;
    await Promise.all([loadMenus(), loadOverview()]);
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    menuSaving.value = false;
  }
}

async function removeMenu(row: any) {
  const ok = await confirmDialog({
    title: '删除菜单',
    message: `将删除「${row.menuName}」及其全部子项,并清理相关角色的菜单授予。`,
    detail: '相关账号重新登录后生效;操作会写入审计日志。',
    confirmText: '删除菜单',
  });
  if (!ok) return;
  try {
    await api(`${workspacePath()}/menus/${row.id}`, { method: 'DELETE' });
    toast('菜单已删除', 'success');
    await Promise.all([loadMenus(), loadOverview()]);
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

const menuColumns: ColumnDef<any>[] = [
  {
    label: '菜单',
    render: (row) =>
      h('div', { class: 'ws-tree-cell', style: { paddingLeft: `${(row._depth || 0) * 18}px` } }, [
        h('span', { class: 'ws-tree-branch' }, row._depth ? '└' : ''),
        h('div', { class: 'cell-stack' }, [
          h('div', { class: 'cell-strong' }, row.menuName),
          h('div', { class: 'cell-sub cell-mono' }, row.menuCode),
        ]),
      ]),
  },
  {
    label: '类型',
    render: (row) =>
      row.menuType === 'BUTTON' ? badge('按钮', '', true) : badge('菜单', 'is-info'),
  },
  {
    label: '路由',
    render: (row) =>
      row.path ? h('span', { class: 'cell-mono truncate' }, row.path) : muted('—'),
  },
  {
    label: '派生权限',
    render: (row) =>
      row.permissionCode
        ? badge(row.permissionCode, 'is-gold')
        : row.permissionId
          ? mono(`#${row.permissionId}`)
          : muted('—'),
  },
  {
    label: '排序',
    align: 'right' as const,
    render: (row) => muted(row.sortOrder ?? 0),
  },
  {
    label: '状态',
    render: (row) =>
      row.enabled === false ? dotBadge('已停用', 'is-danger') : dotBadge('启用中', 'is-success'),
  },
];

function menuActions(row: any): RowAction<any>[] {
  return [
    { label: '新建子项', onClick: () => openMenuEditor(undefined, row) },
    { label: '编辑', onClick: () => openMenuEditor(row) },
    { label: '删除', danger: true, onClick: () => removeMenu(row) },
  ];
}

/* ---------------- 角色 ---------------- */

const roleOpen = ref(false);
const roleEditing = ref<any>(null);
const roleFields = ref<FieldDef[]>([]);
const roleValues = ref<Record<string, any>>({});
const roleFormKey = ref(0);
const roleFormRef = ref<InstanceType<typeof SchemaForm> | null>(null);
const roleSaving = ref(false);

const roleDrawerTitle = computed(() =>
  roleEditing.value ? `编辑角色 · ${roleEditing.value.roleName}` : '新建应用角色',
);

function openRoleEditor(row?: any) {
  roleEditing.value = row || null;
  roleFields.value = row
    ? [
        { name: 'roleName', label: '角色名称', required: true, full: true },
        { name: 'isActive', label: '启用角色', type: 'switch', onText: '启用中' },
      ]
    : [
        {
          name: 'roleCode',
          label: '角色编码',
          required: true,
          mono: true,
          placeholder: '如 expense-auditor',
          help: '应用内唯一;创建后不可修改',
        },
        { name: 'roleName', label: '角色名称', required: true, placeholder: '如 报销审核员' },
      ];
  roleValues.value = row
    ? { roleName: row.roleName, isActive: row.isActive !== false }
    : { roleCode: '', roleName: '' };
  roleFormKey.value += 1;
  roleOpen.value = true;
}

async function saveRole() {
  const form = roleFormRef.value;
  const id = appId.value;
  if (!form || !id) return;
  const check = await form.validate();
  if (!check.ok) return;
  const values = form.read();
  if (!roleEditing.value && !/^[A-Za-z0-9_-]{2,100}$/.test(String(values.roleCode || ''))) {
    form.setError('角色编码只能包含字母、数字、下划线、中划线,长度 2-100');
    return;
  }
  roleSaving.value = true;
  try {
    if (roleEditing.value) {
      await api(`${workspacePath(id)}/roles/${roleEditing.value.id}`, {
        method: 'PUT',
        silent: true,
        body: { roleName: values.roleName, isActive: Boolean(values.isActive) },
      });
      toast('角色已保存', 'success');
    } else {
      await api(`${workspacePath(id)}/roles`, {
        method: 'POST',
        silent: true,
        body: {
          roleCode: String(values.roleCode || '').trim(),
          roleName: values.roleName,
          containerType: 'APP',
          appServiceId: Number(id),
        },
      });
      toast('应用角色已创建', 'success');
    }
    roleOpen.value = false;
    await Promise.all([loadRoles(), loadOverview()]);
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    roleSaving.value = false;
  }
}

async function removeRole(row: any) {
  const ok = await confirmDialog({
    title: '删除角色',
    message: `将删除角色「${row.roleName}」及其权限、菜单授予。`,
    detail: '持有该角色的账号重新登录后失去对应权限;操作会写入审计日志。',
    confirmText: '删除角色',
  });
  if (!ok) return;
  try {
    await api(`${workspacePath()}/roles/${row.id}`, { method: 'DELETE' });
    toast('角色已删除', 'success');
    await Promise.all([loadRoles(), loadOverview()]);
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

const roleColumns: ColumnDef<any>[] = [
  { label: '角色', render: (row) => strongSub(row.roleName, row.roleCode) },
  {
    label: '归属',
    render: (row) =>
      row.containerType === 'APP'
        ? badge('应用角色', 'is-gold')
        : row.containerType === 'CLIENT'
          ? badge('客户端角色', 'is-info')
          : badge('域角色', '', true),
  },
  {
    label: '状态',
    render: (row) =>
      row.isActive === false ? dotBadge('已停用', 'is-danger') : dotBadge('启用中', 'is-success'),
  },
  { label: '来源', render: (row) => (row.isSystem ? badge('系统内置', 'is-gold') : badge('自定义')) },
  { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
];

function roleActions(row: any): RowAction<any>[] {
  return [
    { label: '权限', onClick: () => openRolePermissions(row) },
    { label: '菜单', onClick: () => openRoleMenus(row) },
    { label: '编辑', onClick: () => openRoleEditor(row) },
    {
      label: '删除',
      danger: true,
      hidden: (item) => Boolean(item.isSystem),
      onClick: () => removeRole(row),
    },
  ];
}

const rolePermissionOpen = ref(false);
const rolePermissionTarget = ref<any>(null);
const rolePermissionLoading = ref(false);
const rolePermissionSaving = ref(false);
const rolePermissionError = ref('');
const rolePermissionSelected = ref<Array<string | number>>([]);

const rolePermissionItems = computed(() =>
  permissions.value.map((permission) => ({
    id: permission.id,
    label: permission.permissionName,
    hint: permission.permissionCode,
    group: permission.resource || '其他资源',
  })),
);

async function openRolePermissions(row: any) {
  const id = appId.value;
  if (!id) return;
  rolePermissionTarget.value = row;
  rolePermissionOpen.value = true;
  rolePermissionLoading.value = true;
  rolePermissionError.value = '';
  rolePermissionSelected.value = [];
  try {
    const granted = await api<any[]>(`${workspacePath(id)}/roles/${row.id}/permissions`, {
      silent: true,
    });
    rolePermissionSelected.value = (granted || []).map((permission) => permission.id);
  } catch (error) {
    rolePermissionError.value = errorMessage(error);
  } finally {
    rolePermissionLoading.value = false;
  }
}

async function saveRolePermissions(ids: Array<string | number>) {
  const id = appId.value;
  if (!id || !rolePermissionTarget.value) return;
  rolePermissionSaving.value = true;
  rolePermissionError.value = '';
  try {
    await api(`${workspacePath(id)}/roles/${rolePermissionTarget.value.id}/permissions`, {
      method: 'PUT',
      body: { permissionIds: ids },
      silent: true,
    });
    toast('角色权限已更新,相关账号重新登录后生效', 'success');
    rolePermissionOpen.value = false;
  } catch (error) {
    rolePermissionError.value = errorMessage(error);
  } finally {
    rolePermissionSaving.value = false;
  }
}

const roleMenuOpen = ref(false);
const roleMenuTarget = ref<any>(null);
const roleMenuLoading = ref(false);
const roleMenuSaving = ref(false);
const roleMenuError = ref('');
const roleMenuSelected = ref<Array<string | number>>([]);

const roleMenuItems = computed(() =>
  menuTree.value.map((row) => ({
    id: row.id,
    label: row.menuName,
    hint: `${row.menuCode}${row.menuType === 'BUTTON' ? ' · 按钮' : ''}${row.enabled === false ? ' · 已停用' : ''}`,
    group: row.parentId ? `上级:${menuParentName(row)}` : '一级菜单',
  })),
);

async function openRoleMenus(row: any) {
  const id = appId.value;
  if (!id) return;
  roleMenuTarget.value = row;
  roleMenuOpen.value = true;
  roleMenuLoading.value = true;
  roleMenuError.value = '';
  roleMenuSelected.value = [];
  try {
    const granted = await api<any[]>(`${workspacePath(id)}/roles/${row.id}/menus`, {
      silent: true,
    });
    roleMenuSelected.value = (granted || []).map((menu) => menu.id);
  } catch (error) {
    roleMenuError.value = errorMessage(error);
  } finally {
    roleMenuLoading.value = false;
  }
}

async function saveRoleMenus(ids: Array<string | number>) {
  const id = appId.value;
  if (!id || !roleMenuTarget.value) return;
  roleMenuSaving.value = true;
  roleMenuError.value = '';
  try {
    await api(`${workspacePath(id)}/roles/${roleMenuTarget.value.id}/menus`, {
      method: 'PUT',
      body: { menuIds: ids },
      silent: true,
    });
    toast('角色菜单已更新,相关账号重新登录后生效', 'success');
    roleMenuOpen.value = false;
  } catch (error) {
    roleMenuError.value = errorMessage(error);
  } finally {
    roleMenuSaving.value = false;
  }
}

/* ---------------- 权限 ---------------- */

const permissionOpen = ref(false);
const permissionEditing = ref<any>(null);
const permissionFields = ref<FieldDef[]>([]);
const permissionValues = ref<Record<string, any>>({});
const permissionFormKey = ref(0);
const permissionFormRef = ref<InstanceType<typeof SchemaForm> | null>(null);
const permissionSaving = ref(false);

const permissionDrawerTitle = computed(() =>
  permissionEditing.value
    ? `编辑权限 · ${permissionEditing.value.permissionCode}`
    : '新建应用权限',
);

function openPermissionEditor(row?: any) {
  permissionEditing.value = row || null;
  permissionFields.value = row
    ? [
        { name: 'codeLabel', label: '权限编码', type: 'static', full: true },
        { name: 'permissionName', label: '权限名称', required: true, full: true },
        { name: 'description', label: '说明', type: 'textarea', full: true },
      ]
    : [
        {
          name: 'permissionCode',
          label: '权限编码',
          required: true,
          mono: true,
          placeholder: '如 order:export',
          help: '格式 resource:action;创建后不可修改',
        },
        { name: 'permissionName', label: '权限名称', required: true, placeholder: '如 导出订单' },
        { name: 'resource', label: '资源', placeholder: '留空时取冒号前部分' },
        { name: 'action', label: '动作', placeholder: '留空时取冒号后部分' },
        { name: 'description', label: '说明', type: 'textarea', full: true },
      ];
  permissionValues.value = row
    ? {
        codeLabel: row.permissionCode,
        permissionName: row.permissionName,
        description: row.description || '',
      }
    : { permissionCode: '', permissionName: '', resource: '', action: '', description: '' };
  permissionFormKey.value += 1;
  permissionOpen.value = true;
}

async function savePermission() {
  const form = permissionFormRef.value;
  const id = appId.value;
  if (!form || !id) return;
  const check = await form.validate();
  if (!check.ok) return;
  const values = form.read();
  if (!permissionEditing.value) {
    const code = String(values.permissionCode || '').trim();
    if (!/^[A-Za-z0-9:_-]{2,150}$/.test(code)) {
      form.setError('权限编码只能包含字母、数字、下划线、中划线、冒号,长度 2-150');
      return;
    }
    const separator = code.indexOf(':');
    if (separator <= 0 || separator === code.length - 1) {
      form.setError('权限编码格式必须为 resource:action');
      return;
    }
  }
  permissionSaving.value = true;
  try {
    if (permissionEditing.value) {
      await api(`${workspacePath(id)}/permissions/${permissionEditing.value.id}`, {
        method: 'PUT',
        silent: true,
        body: {
          permissionName: values.permissionName,
          description: values.description || '',
        },
      });
      toast('权限已保存', 'success');
    } else {
      await api(`${workspacePath(id)}/permissions`, {
        method: 'POST',
        silent: true,
        body: {
          appServiceId: Number(id),
          permissionCode: String(values.permissionCode || '').trim(),
          permissionName: values.permissionName,
          resource: values.resource || undefined,
          action: values.action || undefined,
          description: values.description || '',
        },
      });
      toast('应用权限已创建', 'success');
    }
    permissionOpen.value = false;
    await Promise.all([loadPermissions(), loadOverview()]);
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    permissionSaving.value = false;
  }
}

async function removePermission(row: any) {
  const ok = await confirmDialog({
    title: '删除权限',
    message: `将删除权限「${row.permissionName}」(${row.permissionCode})。`,
    detail: '若仍被角色、菜单或用户特批引用,服务端会拒绝删除;请先解除引用。',
    confirmText: '删除权限',
  });
  if (!ok) return;
  try {
    await api(`${workspacePath()}/permissions/${row.id}`, { method: 'DELETE' });
    toast('权限已删除', 'success');
    await Promise.all([loadPermissions(), loadMenus(), loadOverview()]);
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

const permissionColumns: ColumnDef<any>[] = [
  { label: '权限编码', render: (row) => mono(row.permissionCode) },
  { label: '权限名称', render: (row) => strongSub(row.permissionName) },
  { label: '资源', render: (row) => badge(row.resource || '—', '', true) },
  { label: '动作', render: (row) => badge(row.action || '—') },
  { label: '说明', render: (row) => muted(row.description || '—') },
  { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
];

function permissionActions(row: any): RowAction<any>[] {
  return [
    { label: '编辑', onClick: () => openPermissionEditor(row) },
    { label: '删除', danger: true, onClick: () => removePermission(row) },
  ];
}

/* ---------------- 角色映射 ---------------- */

const mappingSourceType = ref<'GROUP' | 'REALM_ROLE'>('GROUP');
const mappingKeyword = ref('');
const mappingSourceId = ref('');
const mappingTargetRoleCode = ref('');
const mappingSaving = ref(false);
const mappingFormError = ref('');

function changeMappingSourceType() {
  mappingSourceId.value = '';
  mappingSources.value = [];
  mappingFormError.value = '';
  void loadMappingSources();
}

async function addMapping() {
  const id = appId.value;
  if (!id) return;
  const targetRoleCode = mappingTargetRoleCode.value.trim();
  if (!mappingSourceId.value) {
    mappingFormError.value = '请选择映射来源';
    return;
  }
  if (!/^[A-Za-z0-9_.:-]+$/.test(targetRoleCode)) {
    mappingFormError.value = '目标角色码只能包含字母、数字、下划线、点、冒号、中划线';
    return;
  }
  mappingSaving.value = true;
  mappingFormError.value = '';
  try {
    await api(`${appPath(id)}/role-mappings`, {
      method: 'POST',
      silent: true,
      body: {
        sourceType: mappingSourceType.value,
        sourceId: Number(mappingSourceId.value),
        targetRoleCode,
        enabled: true,
      },
    });
    toast('角色映射已新增,相关账号下次认证时生效', 'success');
    mappingTargetRoleCode.value = '';
    mappingSourceId.value = '';
    await Promise.all([loadMappings(), loadOverview()]);
  } catch (error) {
    mappingFormError.value = errorMessage(error);
  } finally {
    mappingSaving.value = false;
  }
}

async function removeMapping(row: any) {
  const ok = await confirmDialog({
    title: '删除角色映射',
    message: `将删除「${row.sourceName}」→「${row.targetRoleCode}」的映射。`,
    detail: '相关账号下次认证时不再获得该应用角色。',
    confirmText: '删除映射',
  });
  if (!ok) return;
  try {
    await api(`${appPath()}/role-mappings/${row.id}`, { method: 'DELETE' });
    toast('角色映射已删除', 'success');
    await Promise.all([loadMappings(), loadOverview()]);
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

const mappingColumns: ColumnDef<any>[] = [
  { label: '映射来源', render: (row) => strongSub(row.sourceName, row.sourceCode) },
  {
    label: '来源类型',
    render: (row) =>
      row.sourceType === 'GROUP' ? badge('用户组', 'is-info') : badge('域角色', 'is-gold'),
  },
  { label: '目标角色码', render: (row) => mono(row.targetRoleCode) },
  {
    label: '状态',
    render: (row) =>
      row.enabled === false ? dotBadge('已停用', 'is-danger') : dotBadge('启用中', 'is-success'),
  },
  { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
];

function mappingActions(row: any): RowAction<any>[] {
  return [{ label: '删除', danger: true, onClick: () => removeMapping(row) }];
}

/* ---------------- 管理员 ---------------- */

const adminColumns: ColumnDef<any>[] = [
  { label: '主体', render: (row) => strongSub(row.subjectName, row.subjectCode) },
  {
    label: '类型',
    render: (row) => (row.subjectType === 'GROUP' ? badge('用户组', 'is-info') : badge('用户', '')),
  },
  { label: '授权人', render: (row) => (row.grantedBy ? mono(`#${row.grantedBy}`) : muted('—')) },
  { label: '授权时间', render: (row) => muted(fmtTime(row.createdTime)) },
];

/* ---------------- 权限回填 ---------------- */

const backfillOpen = ref(false);

/* ---------------- 初始化与切换 ---------------- */

function resetData() {
  overview.value = null;
  overviewError.value = '';
  overviewLoading.value = false;
  menus.value = [];
  menusError.value = '';
  menusLoading.value = false;
  roles.value = [];
  rolesError.value = '';
  rolesLoading.value = false;
  permissions.value = [];
  permissionsError.value = '';
  permissionsLoading.value = false;
  mappings.value = [];
  mappingsError.value = '';
  mappingsLoading.value = false;
  admins.value = [];
  adminsError.value = '';
  adminsLoading.value = false;
  mappingSources.value = [];
  mappingSourcesError.value = '';
  mappingSourcesLoading.value = false;
  mappingKeyword.value = '';
  mappingSourceId.value = '';
  mappingTargetRoleCode.value = '';
  mappingFormError.value = '';
  menuOpen.value = false;
  roleOpen.value = false;
  permissionOpen.value = false;
  rolePermissionOpen.value = false;
  roleMenuOpen.value = false;
}

watch(
  appId,
  () => {
    resetData();
    if (appId.value) void loadAll();
  },
  { immediate: true },
);
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="应用管理"
      desc="以应用为边界维护菜单、角色、权限与角色映射;应用管理员只能看到并操作被授权的应用。"
    >
      <template #actions>
        <button
          v-if="appId"
          class="btn"
          type="button"
          :disabled="refreshing"
          @click="loadAll"
        >
          <AppIcon name="refresh" :size="15" :class="{ spin: refreshing }" />
          <span>刷新数据</span>
        </button>
      </template>
    </PageHeader>

    <section v-if="managedApps.length" class="card ws-toolbar">
      <div class="ws-app-picker">
        <label class="filter-label" for="ws-app">当前应用</label>
        <select id="ws-app" v-model="appId" class="input select">
          <option v-if="managedApps.length > 1" value="">请选择应用</option>
          <option v-for="app in managedApps" :key="app.id" :value="String(app.id)">
            {{ app.appName }}({{ app.appCode }})
          </option>
        </select>
      </div>
      <div v-if="selectedApp" class="ws-app-meta">
        <span class="ws-app-code">{{ selectedApp.appCode }}</span>
        <span class="badge is-info">
          {{ AUTH_MODE_LABEL[selectedApp.authorizationMode] || selectedApp.authorizationMode || 'IAM_MANAGED' }}
        </span>
        <span class="badge" :class="selectedApp.tokenProfile === 'SCOPED' ? 'is-gold' : 'is-outline'">
          {{ selectedApp.tokenProfile || 'SCOPED' }}
        </span>
        <span v-if="selectedApp.enabled === false" class="badge is-danger">已停用</span>
        <span v-if="overview" class="badge" :class="overview.globalManage ? 'is-gold' : 'is-outline'">
          {{ overview.globalManage ? '全局管理员' : '应用管理员' }}
        </span>
      </div>
    </section>

    <section v-if="!managedApps.length" class="card ws-empty">
      <span class="ws-empty-mark"><AppIcon name="apps" :size="24" /></span>
      <h2>当前账号暂无可管理的应用</h2>
      <p>
        应用工作台仅对全局应用管理员或应用管理员开放。如需维护某个应用,请联系全局管理员在「应用门户」中
        将你或你所在的用户组登记为应用管理员。
      </p>
    </section>

    <section v-else-if="!appId" class="card ws-empty">
      <span class="ws-empty-mark"><AppIcon name="grid" :size="24" /></span>
      <h2>先选择一个应用</h2>
      <p>你管理着多个应用。选择后即可维护该应用的菜单、角色、权限与角色映射,数据严格隔离。</p>
    </section>

    <template v-else>
      <nav class="ws-tabs" aria-label="应用工作台">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="ws-tab"
          :class="{ 'is-active': activeTab === tab.key }"
          type="button"
          @click="activeTab = tab.key"
        >
          <AppIcon :name="tab.icon" :size="15" />
          <span>{{ tab.label }}</span>
          <span v-if="tab.key !== 'overview'" class="ws-tab-count">{{ tabCounts[tab.key] }}</span>
        </button>
      </nav>

      <!-- 概览 -->
      <template v-if="activeTab === 'overview'">
        <div v-if="overviewError" class="load-error card">
          <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
          <div class="load-error-copy">
            <strong>工作台加载失败</strong>
            <span>{{ overviewError }}</span>
          </div>
          <button class="btn btn-sm" type="button" @click="loadOverview">重试</button>
        </div>
        <SkeletonTable v-else-if="overviewLoading" :rows="5" :columns="3" />
        <template v-else-if="overview">
          <div class="ws-metrics">
            <button
              v-for="metric in metrics"
              :key="metric.tab"
              class="ws-metric"
              type="button"
              @click="activeTab = metric.tab"
            >
              <span class="ws-metric-icon"><AppIcon :name="metric.icon" :size="18" /></span>
              <span class="ws-metric-copy">
                <span class="ws-metric-value">{{ metric.value }}</span>
                <span class="ws-metric-label">{{ metric.label }}</span>
              </span>
              <AppIcon class="ws-metric-arrow" name="arrowRight" :size="14" />
            </button>
          </div>

          <div class="ws-grid">
            <section class="card ws-card">
              <div class="card-head">
                <div class="card-head-main">
                  <div class="card-title">应用档案</div>
                  <div class="card-sub">{{ overview.app?.appName }} · 接入与授权参数由全局管理员维护</div>
                </div>
                <div class="card-actions">
                  <button class="btn btn-sm" type="button" @click="backfillOpen = true">
                    <AppIcon name="key" :size="14" />
                    <span>权限回填报告</span>
                  </button>
                </div>
              </div>
              <dl class="ws-facts">
                <div v-for="fact in overviewFacts" :key="fact.label" class="ws-fact">
                  <dt>{{ fact.label }}</dt>
                  <dd :class="{ 'cell-mono': fact.mono }">{{ fact.value }}</dd>
                </div>
              </dl>
              <p v-if="overview.app?.description" class="ws-desc">{{ overview.app.description }}</p>
            </section>

            <section class="card ws-card">
              <div class="card-head">
                <div class="card-head-main">
                  <div class="card-title">管理边界</div>
                  <div class="card-sub">工作台只能触达当前应用,跨应用主键一律按不存在处理</div>
                </div>
              </div>
              <div class="alert" :class="overview.globalManage ? 'is-info' : 'is-warn'">
                <AppIcon name="info" :size="15" />
                <span v-if="overview.globalManage">
                  你持有全局应用管理权限,可进入任意应用工作台。应用接入参数、可见性与管理员名单仍在「应用门户」维护。
                </span>
                <span v-else>
                  你通过直接授权或用户组继承成为本应用管理员,只能维护本应用的菜单、角色、权限与角色映射;
                  接入参数、可见性与管理员名单由全局管理员维护。
                </span>
              </div>
              <ul class="ws-scope-list">
                <li><AppIcon name="check" :size="14" /><span>菜单树与按钮节点:决定应用可见性,授予菜单即派生挂载权限</span></li>
                <li><AppIcon name="check" :size="14" /><span>应用角色与已绑定该应用的客户端角色:维护权限与菜单授予</span></li>
                <li><AppIcon name="check" :size="14" /><span>应用权限命名空间:权限码只在本应用内唯一</span></li>
                <li><AppIcon name="check" :size="14" /><span>角色映射:把用户组或域角色转换为应用自有角色码</span></li>
              </ul>
              <div class="ws-note">
                角色映射与权限变更多在下次认证时生效;需要立即收敛时,请由全局管理员对相关账号执行强制下线。
              </div>
            </section>
          </div>
        </template>
      </template>

      <!-- 菜单 -->
      <section v-else-if="activeTab === 'menus'" class="card table-card">
        <div class="card-head">
          <div class="card-head-main">
            <div class="card-title">菜单树</div>
            <div class="card-sub">
              <span v-if="!menusLoading && !menusError">共 {{ menus.length }} 个节点 · 菜单只决定可见性,接口鉴权以权限码为准</span>
              <span v-else-if="menusLoading" class="card-sub-dim">正在同步菜单…</span>
              <span v-else class="card-sub-dim">数据暂不可用</span>
            </div>
          </div>
          <div class="card-actions">
            <button class="icon-btn" type="button" title="刷新菜单" aria-label="刷新菜单" :disabled="menusLoading" @click="loadMenus">
              <AppIcon name="refresh" :size="16" :class="{ spin: menusLoading }" />
            </button>
            <button class="btn btn-primary btn-sm" type="button" @click="openMenuEditor()">
              <AppIcon name="plus" :size="14" />
              <span>新建一级菜单</span>
            </button>
          </div>
        </div>
        <div v-if="menusError" class="load-error">
          <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
          <div class="load-error-copy">
            <strong>菜单加载失败</strong>
            <span>{{ menusError }}</span>
          </div>
          <button class="btn btn-sm" type="button" @click="loadMenus">重试</button>
        </div>
        <DataTable
          v-else
          :columns="menuColumns"
          :rows="menuTree"
          :row-actions="menuActions"
          :row-click="(row) => openMenuEditor(row)"
          :loading="menusLoading"
          empty-text="本应用暂无菜单,先创建一级菜单"
        />
      </section>

      <!-- 角色 -->
      <section v-else-if="activeTab === 'roles'" class="card table-card">
        <div class="card-head">
          <div class="card-head-main">
            <div class="card-title">应用角色</div>
            <div class="card-sub">
              <span v-if="!rolesLoading && !rolesError">共 {{ roles.length }} 个角色 · 含已绑定该应用的客户端角色</span>
              <span v-else-if="rolesLoading" class="card-sub-dim">正在同步角色…</span>
              <span v-else class="card-sub-dim">数据暂不可用</span>
            </div>
          </div>
          <div class="card-actions">
            <button class="icon-btn" type="button" title="刷新角色" aria-label="刷新角色" :disabled="rolesLoading" @click="loadRoles">
              <AppIcon name="refresh" :size="16" :class="{ spin: rolesLoading }" />
            </button>
            <button class="btn btn-primary btn-sm" type="button" @click="openRoleEditor()">
              <AppIcon name="plus" :size="14" />
              <span>新建应用角色</span>
            </button>
          </div>
        </div>
        <div v-if="rolesError" class="load-error">
          <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
          <div class="load-error-copy">
            <strong>角色加载失败</strong>
            <span>{{ rolesError }}</span>
          </div>
          <button class="btn btn-sm" type="button" @click="loadRoles">重试</button>
        </div>
        <DataTable
          v-else
          :columns="roleColumns"
          :rows="roles"
          :row-actions="roleActions"
          :loading="rolesLoading"
          empty-text="本应用暂无角色"
        />
      </section>

      <!-- 权限 -->
      <section v-else-if="activeTab === 'permissions'" class="card table-card">
        <div class="card-head">
          <div class="card-head-main">
            <div class="card-title">应用权限</div>
            <div class="card-sub">
              <span v-if="!permissionsLoading && !permissionsError">共 {{ permissions.length }} 个权限 · 只在本应用命名空间内唯一</span>
              <span v-else-if="permissionsLoading" class="card-sub-dim">正在同步权限…</span>
              <span v-else class="card-sub-dim">数据暂不可用</span>
            </div>
          </div>
          <div class="card-actions">
            <button class="icon-btn" type="button" title="刷新权限" aria-label="刷新权限" :disabled="permissionsLoading" @click="loadPermissions">
              <AppIcon name="refresh" :size="16" :class="{ spin: permissionsLoading }" />
            </button>
            <button class="btn btn-primary btn-sm" type="button" @click="openPermissionEditor()">
              <AppIcon name="plus" :size="14" />
              <span>新建权限</span>
            </button>
          </div>
        </div>
        <div v-if="permissionsError" class="load-error">
          <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
          <div class="load-error-copy">
            <strong>权限加载失败</strong>
            <span>{{ permissionsError }}</span>
          </div>
          <button class="btn btn-sm" type="button" @click="loadPermissions">重试</button>
        </div>
        <DataTable
          v-else
          :columns="permissionColumns"
          :rows="permissions"
          :row-actions="permissionActions"
          :loading="permissionsLoading"
          empty-text="本应用暂无权限,先创建权限码"
        />
      </section>

      <!-- 角色映射 -->
      <template v-else-if="activeTab === 'mappings'">
        <section class="card ws-mapping-card">
          <div class="card-head">
            <div class="card-head-main">
              <div class="card-title">新增角色映射</div>
              <div class="card-sub">把用户组或域角色映射为应用自有角色码;直接授予用户的本应用角色按角色码原样下发,无需映射</div>
            </div>
          </div>
          <div class="ws-mapping-form">
            <label class="field">
              <span class="filter-label">来源类型</span>
              <select v-model="mappingSourceType" class="input select" @change="changeMappingSourceType">
                <option value="GROUP">用户组</option>
                <option value="REALM_ROLE">域角色</option>
              </select>
            </label>
            <label class="field">
              <span class="filter-label">来源检索</span>
              <input
                v-model="mappingKeyword"
                class="input"
                type="search"
                placeholder="编码或名称,回车检索"
                @keydown.enter.prevent="loadMappingSources"
              />
            </label>
            <label class="field">
              <span class="filter-label">映射来源</span>
              <select v-model="mappingSourceId" class="input select" :disabled="mappingSourcesLoading">
                <option value="">{{ mappingSourcesLoading ? '加载中…' : '请选择来源' }}</option>
                <option v-for="source in mappingSources" :key="source.id" :value="String(source.id)">
                  {{ source.name }}({{ source.code }})
                </option>
              </select>
            </label>
            <label class="field">
              <span class="filter-label">目标角色码</span>
              <input
                v-model="mappingTargetRoleCode"
                class="input is-mono"
                type="text"
                placeholder="如 expense-auditor"
                @keydown.enter.prevent="addMapping"
              />
            </label>
            <div class="ws-mapping-actions">
              <button class="btn" type="button" :disabled="mappingSourcesLoading" @click="loadMappingSources">
                <AppIcon name="search" :size="14" />
                <span>检索</span>
              </button>
              <button
                class="btn btn-primary"
                type="button"
                :disabled="mappingSaving || !mappingSourceId || !mappingTargetRoleCode.trim()"
                @click="addMapping"
              >
                <span v-if="mappingSaving" class="btn-spinner" />
                <span>新增映射</span>
              </button>
            </div>
          </div>
          <div v-if="mappingSourcesError" class="alert is-danger">
            <AppIcon name="alert" :size="15" />
            <span>{{ mappingSourcesError }}</span>
          </div>
          <div v-if="mappingFormError" class="alert is-danger">
            <AppIcon name="alert" :size="15" />
            <span>{{ mappingFormError }}</span>
          </div>
        </section>

        <section class="card table-card">
          <div class="card-head">
            <div class="card-head-main">
              <div class="card-title">映射清单</div>
              <div class="card-sub">
                <span v-if="!mappingsLoading && !mappingsError">共 {{ mappings.length }} 条映射 · 变更在下次认证时生效</span>
                <span v-else-if="mappingsLoading" class="card-sub-dim">正在同步映射…</span>
                <span v-else class="card-sub-dim">数据暂不可用</span>
              </div>
            </div>
            <div class="card-actions">
              <button class="icon-btn" type="button" title="刷新映射" aria-label="刷新映射" :disabled="mappingsLoading" @click="loadMappings">
                <AppIcon name="refresh" :size="16" :class="{ spin: mappingsLoading }" />
              </button>
            </div>
          </div>
          <div v-if="mappingsError" class="load-error">
            <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
            <div class="load-error-copy">
              <strong>映射加载失败</strong>
              <span>{{ mappingsError }}</span>
            </div>
            <button class="btn btn-sm" type="button" @click="loadMappings">重试</button>
          </div>
          <DataTable
            v-else
            :columns="mappingColumns"
            :rows="mappings"
            :row-actions="mappingActions"
            :loading="mappingsLoading"
            empty-text="本应用暂无角色映射"
          />
        </section>
      </template>

      <!-- 管理员 -->
      <section v-else class="card table-card">
        <div class="card-head">
          <div class="card-head-main">
            <div class="card-title">应用管理员</div>
            <div class="card-sub">
              <span v-if="!adminsLoading && !adminsError">共 {{ admins.length }} 个授权主体 · 用户直授与用户组授权等价</span>
              <span v-else-if="adminsLoading" class="card-sub-dim">正在同步管理员…</span>
              <span v-else class="card-sub-dim">数据暂不可用</span>
            </div>
          </div>
          <div class="card-actions">
            <button class="icon-btn" type="button" title="刷新管理员" aria-label="刷新管理员" :disabled="adminsLoading" @click="loadAdmins">
              <AppIcon name="refresh" :size="16" :class="{ spin: adminsLoading }" />
            </button>
          </div>
        </div>
        <div class="alert is-info ws-admins-note">
          <AppIcon name="info" :size="15" />
          <span>管理员名单由全局管理员在「应用门户」维护;应用管理员只能查看,不能自行扩权。</span>
          <button v-if="can('app-service:view')" class="link-btn" type="button" @click="router.push('/application-services')">
            前往应用门户
          </button>
        </div>
        <div v-if="adminsError" class="load-error">
          <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
          <div class="load-error-copy">
            <strong>管理员加载失败</strong>
            <span>{{ adminsError }}</span>
          </div>
          <button class="btn btn-sm" type="button" @click="loadAdmins">重试</button>
        </div>
        <DataTable
          v-else
          :columns="adminColumns"
          :rows="admins"
          :loading="adminsLoading"
          empty-text="本应用尚未登记管理员"
        />
      </section>
    </template>

    <!-- 菜单编辑器 -->
    <BaseDrawer
      v-model:open="menuOpen"
      :title="menuDrawerTitle"
      :subtitle="menuDrawerSubtitle"
      width="620px"
      :close-on-backdrop="false"
    >
      <SchemaForm
        :key="menuFormKey"
        ref="menuFormRef"
        :fields="menuFields"
        :values="menuValues"
        :mode="menuEditing ? 'edit' : 'create'"
      />
      <template #footer>
        <button class="btn btn-ghost" type="button" :disabled="menuSaving" @click="menuOpen = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="menuSaving" @click="saveMenu">
          <span v-if="menuSaving" class="btn-spinner" />
          <span>{{ menuEditing ? '保存更改' : '创建' }}</span>
        </button>
      </template>
    </BaseDrawer>

    <!-- 角色编辑器 -->
    <BaseDrawer
      v-model:open="roleOpen"
      :title="roleDrawerTitle"
      :subtitle="roleEditing ? '角色编码与归属不可修改' : '仅创建本应用的应用角色'"
      width="560px"
      :close-on-backdrop="false"
    >
      <SchemaForm
        :key="roleFormKey"
        ref="roleFormRef"
        :fields="roleFields"
        :values="roleValues"
        :mode="roleEditing ? 'edit' : 'create'"
      />
      <template #footer>
        <button class="btn btn-ghost" type="button" :disabled="roleSaving" @click="roleOpen = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="roleSaving" @click="saveRole">
          <span v-if="roleSaving" class="btn-spinner" />
          <span>{{ roleEditing ? '保存更改' : '创建' }}</span>
        </button>
      </template>
    </BaseDrawer>

    <!-- 权限编辑器 -->
    <BaseDrawer
      v-model:open="permissionOpen"
      :title="permissionDrawerTitle"
      :subtitle="permissionEditing ? '权限码与所属应用不可修改' : '权限只在本应用命名空间内唯一'"
      width="580px"
      :close-on-backdrop="false"
    >
      <SchemaForm
        :key="permissionFormKey"
        ref="permissionFormRef"
        :fields="permissionFields"
        :values="permissionValues"
        :mode="permissionEditing ? 'edit' : 'create'"
      />
      <template #footer>
        <button class="btn btn-ghost" type="button" :disabled="permissionSaving" @click="permissionOpen = false">取消</button>
        <button class="btn btn-primary" type="button" :disabled="permissionSaving" @click="savePermission">
          <span v-if="permissionSaving" class="btn-spinner" />
          <span>{{ permissionEditing ? '保存更改' : '创建' }}</span>
        </button>
      </template>
    </BaseDrawer>

    <AssignmentModal
      v-model:open="rolePermissionOpen"
      :title="`角色权限 · ${rolePermissionTarget?.roleName || ''}`"
      subtitle="只维护当前应用的权限命名空间"
      :items="rolePermissionItems"
      :model-value="rolePermissionSelected"
      :loading="rolePermissionLoading"
      :saving="rolePermissionSaving"
      :error="rolePermissionError"
      alert="此处只维护角色直接权限;菜单派生的权限另计,二者取并集。保存后相关账号重新登录或刷新令牌后生效。"
      search-placeholder="按权限名称或权限码过滤…"
      save-label="保存授权"
      @save="saveRolePermissions"
    />

    <AssignmentModal
      v-model:open="roleMenuOpen"
      :title="`角色菜单 · ${roleMenuTarget?.roleName || ''}`"
      subtitle="授予菜单会同时派生菜单上挂载的权限"
      :items="roleMenuItems"
      :model-value="roleMenuSelected"
      :loading="roleMenuLoading"
      :saving="roleMenuSaving"
      :error="roleMenuError"
      alert="菜单只决定应用内可见性;接口鉴权仍以权限码为准。保存后相关账号重新登录或刷新令牌后生效。"
      search-placeholder="按菜单名称或编码过滤…"
      save-label="保存菜单"
      @save="saveRoleMenus"
    />

    <AppBackfillDrawer v-model:open="backfillOpen" :app="overview?.app || null" />
  </div>
</template>

<style scoped>
.ws-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 15px 20px;
  margin-bottom: 14px;
  background: linear-gradient(180deg, var(--surface), var(--surface-2));
}

.ws-app-picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 340px;
  max-width: 100%;
}

.ws-app-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 8px;
  min-width: 0;
}

.ws-app-code {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--muted);
}

.ws-tabs {
  display: flex;
  gap: 4px;
  padding: 5px;
  margin-bottom: 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--surface-2);
  overflow-x: auto;
}

.ws-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 14px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
  font-size: 12.5px;
  font-weight: 600;
  white-space: nowrap;
  transition:
    background 0.18s var(--ease-out),
    color 0.18s var(--ease-out),
    box-shadow 0.18s var(--ease-out);
}

.ws-tab:hover {
  background: var(--surface);
  color: var(--ink);
}

.ws-tab.is-active {
  background: linear-gradient(145deg, var(--gold-soft), var(--surface));
  color: var(--gold-deep);
  box-shadow:
    inset 0 0 0 1px rgba(168, 115, 10, 0.22),
    0 8px 18px -14px rgba(18, 26, 43, 0.35);
}

.ws-tab-count {
  min-width: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--surface-3);
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 10.5px;
  text-align: center;
}

.ws-tab.is-active .ws-tab-count {
  background: rgba(168, 115, 10, 0.14);
  color: var(--gold-deep);
}

.ws-metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.ws-metric {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 15px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  text-align: left;
  box-shadow: var(--shadow-card);
  transition:
    transform 0.18s var(--ease-out),
    box-shadow 0.18s var(--ease-out),
    border-color 0.18s var(--ease-out);
}

.ws-metric:hover {
  transform: translateY(-2px);
  border-color: rgba(168, 115, 10, 0.3);
  box-shadow: 0 16px 30px -24px rgba(18, 26, 43, 0.5);
}

.ws-metric-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 11px;
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.ws-metric-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.ws-metric-value {
  font-family: var(--font-mono);
  font-size: 19px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink);
}

.ws-metric-label {
  font-size: 11.5px;
  color: var(--muted);
}

.ws-metric-arrow {
  margin-left: auto;
  color: var(--faint);
}

.ws-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 14px;
}

.ws-card {
  padding-bottom: 18px;
}

.ws-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
  margin: 4px 20px 0;
  padding: 0;
}

.ws-fact {
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
  min-width: 0;
}

.ws-fact dt {
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: var(--faint);
}

.ws-fact dd {
  margin: 2px 0 0;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  overflow-wrap: anywhere;
}

.ws-desc {
  margin: 14px 20px 0;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

.ws-card > .alert {
  margin: 0 20px;
}

.ws-scope-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin: 14px 20px 0;
  padding: 0;
}

.ws-scope-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-2);
}

.ws-scope-list svg {
  flex: none;
  margin-top: 2px;
  color: var(--success);
}

.ws-note {
  margin: 14px 20px 0;
  padding: 9px 11px;
  border: 1px dashed var(--line);
  border-radius: 9px;
  background: var(--surface-2);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--muted);
}

.ws-tree-cell {
  display: flex;
  align-items: center;
  min-width: 0;
}

.ws-tree-branch {
  width: 14px;
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--faint);
}

.ws-mapping-card {
  margin-bottom: 14px;
  padding-bottom: 16px;
}

.ws-mapping-form {
  display: grid;
  grid-template-columns: 170px minmax(0, 1fr) minmax(0, 1.2fr) minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
  padding: 0 20px;
}

.ws-mapping-form .field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.ws-mapping-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 1px;
}

.ws-mapping-card > .alert {
  margin: 12px 20px 0;
}

.ws-admins-note {
  margin: 16px 20px;
}

.ws-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 56px 24px;
  background:
    radial-gradient(circle at 50% 0%, rgba(242, 181, 46, 0.1), transparent 55%),
    var(--surface);
}

.ws-empty-mark {
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

.ws-empty h2 {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.ws-empty p {
  max-width: 460px;
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--muted);
}

@media (max-width: 1180px) {
  .ws-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .ws-mapping-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ws-mapping-actions {
    grid-column: 1 / -1;
  }
}

@media (max-width: 900px) {
  .ws-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 720px) {
  .ws-app-picker {
    width: 100%;
  }

  .ws-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ws-facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .ws-mapping-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
