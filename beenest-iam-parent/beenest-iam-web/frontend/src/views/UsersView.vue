<script setup lang="ts">
import { computed, h, reactive, ref } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import BaseModal from '@/components/BaseModal.vue';
import DetailList from '@/components/DetailList.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import EmptyState from '@/components/EmptyState.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import AppIcon from '@/components/AppIcon.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { PageResult, ResourceConfig } from '@/lib/types';
import { badge, dotBadge, muted, strongSub } from '@/lib/ui';
import { auth, can } from '@/stores/auth';

const STATUS: Record<number, { text: string; tone: 'is-success' | 'is-danger' | 'is-warn' | '' }> = {
  0: { text: '禁用', tone: 'is-danger' },
  1: { text: '正常', tone: 'is-success' },
  2: { text: '锁定', tone: 'is-warn' },
};

const USER_TYPE: Record<string, string> = {
  INTERNAL: '内部用户',
  EXTERNAL: '外部用户',
  SERVICE: '服务账号',
};

function statusMeta(status: number) {
  return STATUS[status] || { text: `未知(${status})`, tone: '' as const };
}

function userTypeLabel(type: string) {
  return USER_TYPE[type] || type || '—';
}

function fixed(value: unknown) {
  return h('span', { class: 'muted nowrap' }, fmtTime(value));
}

/* ---------------- 权限治理 ---------------- */

interface PermissionBrief {
  id: number;
  permissionCode: string;
  permissionName: string;
  appServiceId: number | null;
  appName: string | null;
}

interface EffectiveRoleGroup {
  id: number;
  groupCode: string;
  groupName: string;
}

interface EffectiveRolePosition {
  id: number;
  positionCode: string;
  positionName: string;
}

interface EffectiveRole {
  id: number;
  roleCode: string;
  roleName: string;
  containerType: string;
  direct: boolean;
  groups: EffectiveRoleGroup[];
  positions: EffectiveRolePosition[];
}

interface EffectivePermission {
  userId: number;
  appServiceId: number | null;
  appName: string | null;
  roles: EffectiveRole[];
  roleDirectPermissions: PermissionBrief[];
  menuDerivedPermissions: PermissionBrief[];
  userGrantedPermissions: PermissionBrief[];
  userRevokedPermissions: PermissionBrief[];
  effectivePermissions: PermissionBrief[];
  generatedTime: string;
}

interface PermissionOverride {
  id: number;
  userId: number;
  permissionId: number;
  permissionCode: string;
  permissionName: string;
  appServiceId: number | null;
  appName: string | null;
  grantType: 'GRANT' | 'REVOKE';
  reason: string;
  expiredTime: string | null;
  active: boolean;
  grantedBy: number | null;
  createdTime: string;
  updatedTime: string;
}

interface PermissionScope {
  id: number;
  appCode: string;
  appName: string;
  enabled: boolean;
}

interface PermissionCandidate {
  id: number;
  permissionCode: string;
  permissionName: string;
  appServiceId: number | null;
  appName: string | null;
  appCode: string | null;
  currentGrantType: 'GRANT' | 'REVOKE' | null;
  currentActive: boolean | null;
  currentExpiredTime: string | null;
}

const ROLE_CONTAINER: Record<string, string> = {
  REALM: '域角色',
  APP: '应用角色',
  CLIENT: '客户端角色',
};

const detailOpen = ref(false);
const detailRow = ref<any>(null);
const detailTab = ref<'profile' | 'access'>('profile');
const accessScope = ref('all');
const accessLoading = ref(false);
const accessError = ref('');
const effective = ref<EffectivePermission | null>(null);
/** 用户所属的用户组(权限来源之一,排障时最常问"这人属于哪些组") */
const userGroups = ref<any[]>([]);
const overrides = ref<PermissionOverride[]>([]);
const scopes = ref<PermissionScope[]>([]);
let accessRequestSeq = 0;

const isSelf = computed(() => Boolean(auth.me && detailRow.value && auth.me.userId === detailRow.value.id));
const canEditOverrides = computed(() => can('user:grant-permission') && !isSelf.value);
const visibleOverrides = computed(() => {
  if (accessScope.value === 'all') return overrides.value;
  return overrides.value.filter((item) => String(item.appServiceId) === accessScope.value);
});
const activeOverrideCount = computed(() => overrides.value.filter((item) => item.active).length);
const effectiveCount = computed(() => effective.value?.effectivePermissions.length || 0);

const scopeOptions = computed(() => [
  { value: 'all', label: '全部范围' },
  ...scopes.value.map((scope) => ({
    value: String(scope.id),
    label: `${scope.appName}(${scope.appCode})${scope.enabled ? '' : ' · 已停用'}`,
  })),
]);

const effectiveGroups = computed(() => {
  const groups = new Map<string, { key: string; label: string; items: PermissionBrief[] }>();
  for (const permission of effective.value?.effectivePermissions || []) {
    const key = permission.appServiceId == null ? 'platform' : String(permission.appServiceId);
    const label = permission.appServiceId == null ? '平台权限' : permission.appName || `应用 #${permission.appServiceId}`;
    const bucket = groups.get(key);
    if (bucket) bucket.items.push(permission);
    else groups.set(key, { key, label, items: [permission] });
  }
  return [...groups.values()].sort((left, right) => {
    if (left.key === 'platform') return -1;
    if (right.key === 'platform') return 1;
    return left.label.localeCompare(right.label, 'zh-CN');
  });
});

const grantedPermissionIds = computed(
  () => new Set((effective.value?.userGrantedPermissions || []).map((permission) => permission.id)),
);

const sourceGroups = computed(() => {
  if (!effective.value) return [];
  return [
    {
      key: 'direct',
      title: '角色直授',
      hint: '角色与权限的直接关联',
      tone: 'is-success',
      items: effective.value.roleDirectPermissions,
    },
    {
      key: 'menu',
      title: '菜单派生',
      hint: '角色授予菜单后派生出的权限',
      tone: 'is-info',
      items: effective.value.menuDerivedPermissions,
    },
    {
      key: 'grant',
      title: '特批授予',
      hint: '绕过角色直接补给该账号',
      tone: 'is-warn',
      items: effective.value.userGrantedPermissions,
    },
    {
      key: 'revoke',
      title: '特批撤销',
      hint: '从角色权限中扣除',
      tone: 'is-danger',
      items: effective.value.userRevokedPermissions,
    },
  ];
});

function openDetail(row: any, tab: 'profile' | 'access' = 'profile') {
  detailRow.value = row;
  detailTab.value = tab;
  detailOpen.value = true;
  accessScope.value = 'all';
  accessLoading.value = false;
  accessError.value = '';
  effective.value = null;
  overrides.value = [];
  scopes.value = [];
  userGroups.value = [];
  accessRequestSeq += 1;
  if (tab === 'access') void loadAccess();
}

function switchDetailTab(tab: 'profile' | 'access') {
  detailTab.value = tab;
  if (tab === 'access' && !effective.value && !accessLoading.value) {
    void loadAccess();
  }
}

async function loadAccess() {
  if (!detailRow.value) return;
  const targetId = detailRow.value.id;
  const seq = ++accessRequestSeq;
  accessLoading.value = true;
  accessError.value = '';
  try {
    const scope = accessScope.value === 'all' ? undefined : Number(accessScope.value);
    const [inspection, overrideList, scopeList, groupList] = await Promise.all([
      api<EffectivePermission>(`/api/v1/admin/users/${targetId}/effective-permissions`, {
        query: { appServiceId: scope },
        silent: true,
      }),
      api<PermissionOverride[]>(`/api/v1/admin/users/${targetId}/permission-overrides`, { silent: true }),
      api<PermissionScope[]>(`/api/v1/admin/users/${targetId}/permission-scopes`, { silent: true }),
      api<any[]>(`/api/v1/admin/users/${targetId}/organizations`, { silent: true }),
    ]);
    if (seq !== accessRequestSeq) return;
    effective.value = inspection;
    overrides.value = overrideList || [];
    scopes.value = scopeList || [];
    userGroups.value = groupList || [];
  } catch (error) {
    if (seq === accessRequestSeq) accessError.value = errorMessage(error);
  } finally {
    if (seq === accessRequestSeq) accessLoading.value = false;
  }
}

function permissionScopeLabel(appServiceId: number | null, appName: string | null) {
  return appServiceId == null ? '平台权限' : appName || `应用 #${appServiceId}`;
}

function overrideExpiry(item: PermissionOverride) {
  if (!item.active) return '已过期';
  return item.expiredTime ? `至 ${fmtTime(item.expiredTime)}` : '长期有效';
}

function toLocalInput(value: string | null | undefined) {
  if (!value) return '';
  return String(value).replace(' ', 'T').slice(0, 16);
}

/**
 * 角色来源说明。
 * <p>组与岗位都要写:只写组的话,"为什么有这个角色"会缺一条链路,
 * 管理员找不到原因就只能靠猜着改。</p>
 */
function roleSourceLabel(role: EffectiveRole) {
  const groupNames = role.groups.map((group) => group.groupName).join('、');
  const positionNames = (role.positions || []).map((item) => item.positionName).join('、');
  const inherited = [groupNames ? `组 ${groupNames}` : '', positionNames ? `岗位 ${positionNames}` : '']
    .filter(Boolean)
    .join(' + ');
  if (role.direct && inherited) return `直授 + ${inherited}`;
  if (role.direct) return '直接授予';
  return inherited ? `继承 · ${inherited}` : '继承';
}

function candidateState(item: PermissionCandidate) {
  if (!item.currentGrantType) return { text: '未特批', tone: '' };
  if (!item.currentActive) return { text: '特批已过期', tone: 'is-warn' };
  return item.currentGrantType === 'GRANT'
    ? { text: '已特批授予', tone: 'is-warn' }
    : { text: '已特批撤销', tone: 'is-danger' };
}

function selectCandidate(item: PermissionCandidate) {
  overrideForm.permissionId = String(item.id);
}

/* ---------------- 权限特批 ---------------- */

const overrideOpen = ref(false);
const overrideEdit = ref<PermissionOverride | null>(null);
const overrideSaving = ref(false);
const overrideError = ref('');
const candidates = ref<PermissionCandidate[]>([]);
const candidateTotal = ref(0);
const candidateLoading = ref(false);
const candidateError = ref('');
const candidateScope = ref('all');
const candidateKeyword = ref('');
let candidateRequestSeq = 0;
const overrideForm = reactive({
  permissionId: '',
  grantType: 'GRANT' as 'GRANT' | 'REVOKE',
  reason: '',
  expiredAt: '',
});

const candidateGroups = computed(() => {
  const groups = new Map<string, { label: string; items: PermissionCandidate[] }>();
  for (const item of candidates.value) {
    const label = permissionScopeLabel(item.appServiceId, item.appName);
    const bucket = groups.get(label);
    if (bucket) bucket.items.push(item);
    else groups.set(label, { label, items: [item] });
  }
  return [...groups.values()];
});

async function openOverride(row?: PermissionOverride) {
  if (!canEditOverrides.value || !detailRow.value) return;
  overrideEdit.value = row || null;
  overrideError.value = '';
  overrideSaving.value = false;
  overrideForm.permissionId = row ? String(row.permissionId) : '';
  overrideForm.grantType = row?.grantType === 'REVOKE' ? 'REVOKE' : 'GRANT';
  overrideForm.reason = row?.reason || '';
  overrideForm.expiredAt = toLocalInput(row?.expiredTime);
  candidateKeyword.value = '';
  candidateError.value = '';
  candidateTotal.value = 0;
  candidates.value = [];
  candidateScope.value = row?.appServiceId
    ? String(row.appServiceId)
    : accessScope.value === 'all'
      ? 'all'
      : accessScope.value;
  overrideOpen.value = true;
  if (!row) {
    await loadCandidates();
  }
}

async function loadCandidates() {
  if (!detailRow.value || overrideEdit.value) return;
  const seq = ++candidateRequestSeq;
  candidateLoading.value = true;
  candidateError.value = '';
  try {
    const page = await api<PageResult<PermissionCandidate>>(
      `/api/v1/admin/users/${detailRow.value.id}/permission-overrides/candidates`,
      {
        query: {
          keyword: candidateKeyword.value.trim() || undefined,
          appServiceId: candidateScope.value === 'all' ? undefined : Number(candidateScope.value),
          pageNum: 1,
          pageSize: 100,
        },
        silent: true,
      },
    );
    if (seq !== candidateRequestSeq) return;
    const records = page?.records || [];
    candidates.value = records;
    candidateTotal.value = page?.total || 0;
    if (overrideForm.permissionId && !records.some((item) => String(item.id) === overrideForm.permissionId)) {
      overrideForm.permissionId = '';
    }
  } catch (error) {
    if (seq === candidateRequestSeq) candidateError.value = errorMessage(error);
  } finally {
    if (seq === candidateRequestSeq) candidateLoading.value = false;
  }
}

function setExpiry(days: number) {
  if (days <= 0) {
    overrideForm.expiredAt = '';
    return;
  }
  const date = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  const pad = (value: number) => String(value).padStart(2, '0');
  overrideForm.expiredAt =
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

async function saveOverride() {
  if (!detailRow.value) return;
  if (!overrideForm.permissionId) {
    overrideError.value = '请选择要特批的权限';
    return;
  }
  if (!overrideForm.reason.trim()) {
    overrideError.value = '请填写操作原因,便于审计追溯';
    return;
  }
  if (overrideForm.expiredAt) {
    const expires = new Date(`${overrideForm.expiredAt}:00`);
    if (Number.isNaN(expires.getTime()) || expires.getTime() <= Date.now()) {
      overrideError.value = '过期时间必须晚于当前时间;长期有效请留空';
      return;
    }
  }
  overrideSaving.value = true;
  overrideError.value = '';
  try {
    await api(`/api/v1/admin/users/${detailRow.value.id}/permission-overrides/${overrideForm.permissionId}`, {
      method: 'PUT',
      body: {
        grantType: overrideForm.grantType,
        reason: overrideForm.reason.trim(),
        expiredTime: overrideForm.expiredAt ? `${overrideForm.expiredAt}:00` : null,
      },
      silent: true,
    });
    toast(overrideEdit.value ? '权限特批已更新' : '权限特批已设置', 'success');
    overrideOpen.value = false;
    await loadAccess();
  } catch (error) {
    overrideError.value = errorMessage(error);
  } finally {
    overrideSaving.value = false;
  }
}

async function removeOverride(row: PermissionOverride) {
  if (!detailRow.value || !canEditOverrides.value) return;
  const ok = await confirmDialog({
    title: '删除权限特批',
    message: `确认删除「${row.permissionCode}」的${row.grantType === 'GRANT' ? '授予' : '撤销'}记录吗?删除后该账号回归角色计算结果。`,
    danger: true,
    confirmText: '删除特批',
  });
  if (!ok) return;
  try {
    await api(`/api/v1/admin/users/${detailRow.value.id}/permission-overrides/${row.permissionId}`, {
      method: 'DELETE',
    });
    toast('权限特批已删除', 'success');
    await loadAccess();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

async function removeEditingOverride() {
  if (!overrideEdit.value) return;
  const row = overrideEdit.value;
  overrideOpen.value = false;
  await removeOverride(row);
}

/* ---------------- 角色分配 ---------------- */

const roleOpen = ref(false);
const roleTarget = ref<any>(null);
const roleLoading = ref(false);
const roleSaving = ref(false);
const roleError = ref('');
const roleItems = ref<any[]>([]);
const roleSelected = ref<Array<string | number>>([]);
const roleApps = ref<any[]>([]);

function roleAppName(appServiceId: unknown): string {
  if (appServiceId === null || appServiceId === undefined) return '';
  const app = roleApps.value.find((item) => String(item.id) === String(appServiceId));
  return app ? `${app.appName}(${app.appCode})` : `#${appServiceId}`;
}

async function loadRoleApps() {
  try {
    const page = await api<any>('/api/v1/admin/application-services', {
      query: { pageNum: 1, pageSize: 300 },
      silent: true,
    });
    roleApps.value = page?.records || [];
  } catch {
    roleApps.value = [];
  }
}

/** 按角色容器归类:应用角色只在所属应用内生效,展示时必须带上应用名。 */
function roleGroupLabel(role: any): string {
  if (role.containerType === 'APP') {
    return `应用角色 · ${roleAppName(role.appServiceId) || '未绑定应用'}`;
  }
  if (role.containerType === 'CLIENT') {
    return role.appServiceId ? `客户端角色 · ${roleAppName(role.appServiceId)}` : '客户端角色';
  }
  return '域角色';
}

async function openRoles(row: any) {
  roleTarget.value = row;
  roleOpen.value = true;
  roleLoading.value = true;
  roleError.value = '';
  roleItems.value = [];
  roleSelected.value = [];
  try {
    if (!roleApps.value.length) await loadRoleApps();
    const [page, assigned] = await Promise.all([
      api<any>('/api/v1/admin/roles', { query: { pageNum: 1, pageSize: 500 }, silent: true }),
      api<any[]>(`/api/v1/admin/users/${row.id}/roles`, { silent: true }),
    ]);
    roleItems.value = (page?.records || []).map((role: any) => ({
      id: role.id,
      label: role.roleName,
      hint: role.roleCode,
      group: roleGroupLabel(role),
      tags: role.isActive === false ? [{ text: '已停用', tone: 'is-danger' }] : [],
    }));
    roleSelected.value = (assigned || []).map((role: any) => role.id);
  } catch (error) {
    roleError.value = errorMessage(error);
  } finally {
    roleLoading.value = false;
  }
}

async function saveRoles(ids: Array<string | number>) {
  if (!roleTarget.value) return;
  roleSaving.value = true;
  roleError.value = '';
  try {
    await api(`/api/v1/admin/users/${roleTarget.value.id}/roles`, {
      method: 'PUT',
      body: { roleIds: ids },
      silent: true,
    });
    toast('角色已更新,相关账号重新登录后生效', 'success');
    roleOpen.value = false;
  } catch (error) {
    roleError.value = errorMessage(error);
  } finally {
    roleSaving.value = false;
  }
}

/* ---------------- 组织归属 ---------------- */

const orgOpen = ref(false);
const orgTarget = ref<any>(null);
const orgLoading = ref(false);
const orgError = ref('');
const orgRows = ref<any[]>([]);

async function openOrg(row: any) {
  orgTarget.value = row;
  orgOpen.value = true;
  orgLoading.value = true;
  orgError.value = '';
  orgRows.value = [];
  try {
    orgRows.value = (await api<any[]>(`/api/v1/admin/users/${row.id}/organizations`, { silent: true })) || [];
  } catch (error) {
    orgError.value = errorMessage(error);
  } finally {
    orgLoading.value = false;
  }
}

/* ---------------- 重置口令 ---------------- */

const resetOpen = ref(false);
const resetTarget = ref<any>(null);
const resetKey = ref(0);
const resetBusy = ref(false);
const resetForm = ref<InstanceType<typeof SchemaForm> | null>(null);

const resetFields = [
  {
    name: 'newPassword',
    label: '新口令',
    type: 'password' as const,
    required: true,
    full: true,
    autocomplete: 'new-password',
    help: '长度 8-64 位,须满足当前身份域的口令策略;重置不会自动结束该账号现有会话。',
  },
];

function openReset(row: any) {
  resetTarget.value = row;
  resetOpen.value = true;
  resetKey.value += 1;
  resetBusy.value = false;
}

async function submitReset() {
  const form = resetForm.value;
  if (!form || !resetTarget.value) return;
  const check = await form.validate();
  if (!check.ok) return;
  resetBusy.value = true;
  try {
    await api(`/api/v1/admin/users/${resetTarget.value.id}/password`, {
      method: 'POST',
      body: { newPassword: form.read().newPassword },
      silent: true,
    });
    toast('口令已重置', 'success');
    resetOpen.value = false;
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    resetBusy.value = false;
  }
}

/* ---------------- 状态操作 ---------------- */

async function toggleLock(row: any, reload: () => Promise<void>) {
  const locking = row.status !== 2;
  const ok = await confirmDialog({
    title: locking ? '锁定账号' : '解锁账号',
    message: locking
      ? `确认锁定「${row.username}」吗?锁定期间该账号无法登录,已签发的令牌不受影响。`
      : `确认解锁「${row.username}」吗?解锁后账号恢复为正常状态。`,
    danger: locking,
    confirmText: locking ? '锁定' : '解锁',
  });
  if (!ok) return;
  try {
    await api(`/api/v1/admin/users/${row.id}/${locking ? 'lock' : 'unlock'}`, { method: 'POST' });
    toast(locking ? '账号已锁定' : '账号已解锁', 'success');
    await reload();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

async function forceLogout(row: any, reload: () => Promise<void>) {
  const ok = await confirmDialog({
    title: '强制下线',
    message: `确认让「${row.username}」全端下线吗?将递增令牌版本使全部已签发令牌失效,并结束其所有活跃会话。`,
    confirmText: '立即下线',
  });
  if (!ok) return;
  try {
    const ended = await api<number>(`/api/v1/admin/users/${row.id}/logout`, { method: 'POST' });
    toast(`已结束 ${ended ?? 0} 个会话`, 'success');
    await reload();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

async function batchStatus(ids: number[], status: number) {
  const ok = await confirmDialog({
    title: status === 1 ? '批量启用' : '批量禁用',
    message: `将对选中的 ${ids.length} 个账号执行${status === 1 ? '启用' : '禁用'},是否继续?`,
    danger: status !== 1,
    confirmText: '继续',
  });
  if (!ok) return;
  try {
    const changed = await api<number>('/api/v1/admin/users/batch-status', {
      method: 'POST',
      body: { ids, status },
    });
    toast(`已更新 ${changed ?? ids.length} 个账号`, 'success');
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '用户',
  desc: '账号是身份的载体:开户、资料维护、角色授予、口令重置与状态管控都在这里完成。',
  endpoint: '/api/v1/admin/users',
  permCreate: 'user:create',
  createLabel: '新建用户',
  deleteName: '用户',
  rowName: (row) => row.username,
  cardTitle: '账号清单',
  filters: [
    { name: 'username', label: '登录名', placeholder: '支持模糊匹配' },
    {
      name: 'status',
      label: '账号状态',
      type: 'select',
      options: [
        { value: '', label: '全部状态' },
        { value: '1', label: '正常' },
        { value: '0', label: '禁用' },
        { value: '2', label: '锁定' },
      ],
    },
  ],
  columns: [
    {
      label: '账号',
      render: (row) => strongSub(row.username, row.nickname || '未设置昵称'),
    },
    {
      label: '联系方式',
      render: (row) =>
        h('div', { class: 'cell-stack' }, [
          h('div', null, row.email || '—'),
          h('div', { class: 'cell-sub' }, [
            row.phone || '—',
            row.phone && row.phoneVerified ? badge('已验证', 'is-success') : null,
          ]),
        ]),
    },
    { label: '状态', render: (row) => dotBadge(statusMeta(row.status).text, statusMeta(row.status).tone) },
    { label: '类型', render: (row) => badge(userTypeLabel(row.userType), '', true) },
    { label: '最近更新', render: (row) => fixed(row.updatedTime || row.createdTime) },
  ],
  rowActions: (row, ctx) => [
    { label: '详情', onClick: () => openDetail(row) },
    { label: '权限治理', perm: 'user:view', onClick: () => openDetail(row, 'access') },
    { label: '编辑', perm: 'user:update', onClick: () => ctx?.openEditor(row) },
    { label: '角色', perm: 'user:assign-role', onClick: () => openRoles(row) },
    { label: '组织', perm: 'user:view', onClick: () => openOrg(row) },
    { label: '重置口令', perm: 'user:reset-password', onClick: () => openReset(row) },
    {
      label: row.status === 2 ? '解锁' : '锁定',
      perm: 'user:lock',
      onClick: () => toggleLock(row, () => ctx?.reload() ?? Promise.resolve()),
    },
    {
      label: '强制下线',
      perm: 'user:logout',
      onClick: () => forceLogout(row, () => ctx?.reload() ?? Promise.resolve()),
    },
    { label: '删除', perm: 'user:delete', danger: true, onClick: () => ctx?.remove(row) },
  ],
  batchActions: [
    {
      label: '批量启用',
      perm: 'user:batch-status',
      onClick: (ids) => batchStatus(ids, 1),
    },
    {
      label: '批量禁用',
      perm: 'user:batch-status',
      onClick: (ids) => batchStatus(ids, 0),
    },
  ],
  rowClick: (row) => openDetail(row),
  form: {
    title: '用户',
    subtitle: '登录名与用户类型创建后不可修改',
    fields: [
      {
        name: 'username',
        label: '登录名',
        required: true,
        mono: true,
        placeholder: '3-100 位,字母/数字/下划线/点/@/中划线',
      },
      { name: 'nickname', label: '昵称', placeholder: '用于界面展示' },
      { name: 'email', label: '邮箱', type: 'email', placeholder: 'name@example.com' },
      { name: 'phone', label: '手机号', placeholder: '中国大陆 11 位手机号' },
      {
        name: 'phoneVerified',
        label: '手机号已验证',
        type: 'switch',
        onText: '已验证',
        help: '仅已验证手机号可用于短信验证码登录;请确认号码归属后再开启',
      },
      {
        name: 'userType',
        label: '用户类型',
        type: 'select',
        options: [
          { value: 'INTERNAL', label: '内部用户' },
          { value: 'EXTERNAL', label: '外部用户' },
          { value: 'SERVICE', label: '服务账号' },
        ],
      },
      {
        name: 'password',
        label: '初始口令',
        type: 'password',
        required: true,
        autocomplete: 'new-password',
        help: '8-64 位,须满足当前身份域的口令策略;交付后建议要求用户首次登录改密',
      },
      {
        name: 'attributes',
        label: '扩展属性',
        type: 'kv',
        full: true,
        help: '按身份域的属性定义填写,例如 department=研发中心',
      },
    ],
    editFields: [
      { name: 'nickname', label: '昵称' },
      { name: 'email', label: '邮箱', type: 'email' },
      { name: 'phone', label: '手机号' },
      {
        name: 'status',
        label: '账号状态',
        type: 'select',
        options: [
          { value: 1, label: '正常' },
          { value: 0, label: '禁用' },
          { value: 2, label: '锁定' },
        ],
      },
      { name: 'emailVerified', label: '邮箱已验证', type: 'switch', onText: '已验证' },
      {
        name: 'phoneVerified',
        label: '手机号已验证',
        type: 'switch',
        onText: '已验证',
        help: '开启后该手机号即成为短信登录定位键,同域内不可与他人重复',
      },
      { name: 'attributes', label: '扩展属性', type: 'kv', full: true },
    ],
    defaults: { userType: 'INTERNAL' },
    fromRow: (row) => ({
      nickname: row.nickname,
      email: row.email,
      phone: row.phone,
      status: row.status,
      emailVerified: Boolean(row.emailVerified),
      phoneVerified: Boolean(row.phoneVerified),
      attributes: row.attributes || {},
    }),
    toUpdate: (values) => ({ ...values, status: values.status === '' ? undefined : Number(values.status) }),
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseDrawer
    v-model:open="orgOpen"
    :title="`组织归属 · ${orgTarget?.username || ''}`"
    subtitle="所属部门与任职岗位;主部门在最前"
  >
    <div v-if="orgLoading" class="muted">加载中…</div>
    <div v-else-if="orgError" class="alert is-danger">{{ orgError }}</div>
    <div v-else-if="!orgRows.length" class="muted">该账号尚未归属任何组织。</div>
    <div v-else class="org-list">
      <div v-for="item in orgRows" :key="`${item.groupId}-${item.positionId ?? 0}`" class="org-row">
        <div class="org-dept">
          <strong>{{ item.groupName }}</strong>
          <span class="cell-mono">{{ item.groupCode }}</span>
        </div>
        <span>{{ item.positionName ? badge(item.positionName, 'is-gold', true) : muted('未任职岗位') }}</span>
        <span>{{ item.primary ? badge('主部门', 'is-gold', true) : badge('兼职', '', true) }}</span>
      </div>
    </div>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="orgOpen = false">关闭</button>
    </template>
  </BaseDrawer>

  <BaseDrawer
    v-model:open="detailOpen"
    :title="detailRow?.username || '用户详情'"
    :subtitle="detailTab === 'profile' ? '账号画像与扩展属性' : '有效权限诊断与特批治理'"
    width="640px"
  >
    <template v-if="detailRow">
      <div class="detail-tabs" role="tablist" aria-label="用户详情视图">
        <button
          class="detail-tab"
          :class="{ 'is-active': detailTab === 'profile' }"
          type="button"
          role="tab"
          :aria-selected="detailTab === 'profile'"
          @click="switchDetailTab('profile')"
        >
          <AppIcon name="user" :size="14" />
          <span>账号概览</span>
        </button>
        <button
          class="detail-tab"
          :class="{ 'is-active': detailTab === 'access' }"
          type="button"
          role="tab"
          :aria-selected="detailTab === 'access'"
          @click="switchDetailTab('access')"
        >
          <AppIcon name="shield" :size="14" />
          <span>权限治理</span>
          <span v-if="activeOverrideCount" class="detail-tab-count">{{ activeOverrideCount }}</span>
        </button>
      </div>

      <template v-if="detailTab === 'profile'">
        <div class="identity-head">
          <span class="avatar">{{ String(detailRow.username || '?').slice(0, 1).toUpperCase() }}</span>
          <div class="identity-copy">
            <div class="identity-name">{{ detailRow.nickname || detailRow.username }}</div>
            <div class="identity-meta">
              <span class="cell-mono">{{ detailRow.username }}</span>
              <span class="identity-dot">·</span>
              <span>{{ userTypeLabel(detailRow.userType) }}</span>
            </div>
          </div>
          <span class="dot-badge" :class="statusMeta(detailRow.status).tone">
            <i class="dot" />{{ statusMeta(detailRow.status).text }}
          </span>
        </div>

        <DetailList
          :rows="[
            { label: '用户主键', value: detailRow.id, mono: true },
            { label: '邮箱', value: detailRow.email || '—' },
            { label: '手机号', value: detailRow.phone || '—' },
            { label: '邮箱已验证', value: Boolean(detailRow.emailVerified) },
            { label: '令牌版本', value: detailRow.tokenVersion, mono: true },
            { label: '创建时间', value: fmtTime(detailRow.createdTime) },
            { label: '更新时间', value: fmtTime(detailRow.updatedTime) },
          ]"
        />

        <div class="form-section-title" style="margin: 20px 0 10px">扩展属性</div>
        <DetailList
          v-if="detailRow.attributes && Object.keys(detailRow.attributes).length"
          :rows="Object.entries(detailRow.attributes).map(([key, value]) => ({ label: key, value, mono: true }))"
        />
        <p v-else class="muted">未设置扩展属性</p>

        <div class="alert is-info" style="margin-top: 18px">
          <AppIcon name="info" :size="15" />
          <span>令牌版本随强制下线递增;版本变化会使该账号此前签发的全部令牌失效。</span>
        </div>
      </template>

      <template v-else>
        <div class="gov-toolbar">
          <label class="gov-scope">
            <span>权限范围</span>
            <select v-model="accessScope" class="input select" @change="loadAccess">
              <option v-for="option in scopeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <button class="btn btn-ghost btn-sm" type="button" :disabled="accessLoading" @click="loadAccess">
            <AppIcon name="refresh" :size="14" :class="{ spin: accessLoading }" />
            <span>刷新</span>
          </button>
        </div>

        <div v-if="!canEditOverrides" class="alert is-warn" style="margin-bottom: 14px">
          <AppIcon name="lock" :size="15" />
          <span v-if="isSelf">出于审批隔离,不能修改自己的权限特批;当前页面仅可查看。</span>
          <span v-else>当前账号仅可查看权限诊断;特批操作需要 <code class="cell-mono">user:grant-permission</code> 权限。</span>
        </div>

        <SkeletonTable v-if="accessLoading && !effective" :rows="5" :columns="2" />
        <div v-else-if="accessError" class="alert is-danger">
          <AppIcon name="alert" :size="15" />
          <span>{{ accessError }}</span>
          <button class="link-btn" type="button" @click="loadAccess">重试</button>
        </div>

        <template v-else-if="effective">
          <div class="gov-summary">
            <div class="gov-stat">
              <span class="gov-stat-label">生效权限</span>
              <strong class="gov-stat-value">{{ effectiveCount }}</strong>
              <span class="gov-stat-hint">{{ effective.appServiceId ? effective.appName : '全范围' }} · 已去重</span>
            </div>
            <div class="gov-stat">
              <span class="gov-stat-label">生效角色</span>
              <strong class="gov-stat-value">{{ effective.roles.length }}</strong>
              <span class="gov-stat-hint">直授与组继承合并</span>
            </div>
            <div class="gov-stat">
              <span class="gov-stat-label">活跃特批</span>
              <strong class="gov-stat-value">{{ activeOverrideCount }}</strong>
              <span class="gov-stat-hint">共 {{ overrides.length }} 条记录</span>
            </div>
            <div class="gov-stat">
              <span class="gov-stat-label">计算时间</span>
              <strong class="gov-stat-time">{{ fmtTime(effective.generatedTime) }}</strong>
              <span class="gov-stat-hint">令牌签发前实时计算</span>
            </div>
          </div>

          <div class="gov-section">
            <div class="gov-section-head">
              <div>
                <div class="form-section-title">生效角色</div>
                <div class="form-section-hint">停用角色与跨域角色不计入;组继承与直授等价。</div>
              </div>
            </div>
            <div v-if="effective.roles.length" class="gov-roles">
              <div v-for="role in effective.roles" :key="role.id" class="gov-role">
                <div class="gov-role-main">
                  <span class="gov-role-name">{{ role.roleName }}</span>
                  <span class="cell-mono">{{ role.roleCode }}</span>
                </div>
                <span class="badge is-outline">{{ ROLE_CONTAINER[role.containerType] || role.containerType }}</span>
                <span class="gov-role-source">
                  <AppIcon :name="role.direct ? 'user' : 'users'" :size="12" />
                  {{ roleSourceLabel(role) }}
                </span>
              </div>
            </div>
            <p v-else class="gov-empty-line">未分配任何生效角色</p>
          </div>

          <div class="gov-section">
            <div class="gov-section-head">
              <div>
                <div class="form-section-title">所属用户组</div>
                <div class="form-section-hint">
                  组是权限来源之一:成员经组继承组上的角色。层级只表达归属,不参与继承。
                </div>
              </div>
            </div>
            <div v-if="userGroups.length" class="gov-roles">
              <div v-for="group in userGroups" :key="group.groupId" class="gov-role">
                <div class="gov-role-main">
                  <span class="gov-role-name">{{ group.groupName }}</span>
                  <span class="cell-mono">{{ group.groupCode }}</span>
                </div>
                <span v-if="group.primary" class="badge is-gold is-outline">主部门</span>
                <span v-if="group.positionName" class="badge is-outline">{{ group.positionName }}</span>
              </div>
            </div>
            <p v-else class="gov-empty-line">尚未归属任何组织</p>
          </div>

          <div class="gov-section">
            <div class="gov-section-head">
              <div>
                <div class="form-section-title">权限来源</div>
                <div class="form-section-hint">最终权限 = 角色直授 ∪ 菜单派生 ∪ 特批授予 − 特批撤销。</div>
              </div>
            </div>
            <div class="gov-sources">
              <section v-for="source in sourceGroups" :key="source.key" class="gov-source" :class="source.tone">
                <header class="gov-source-head">
                  <span class="gov-source-title">{{ source.title }}</span>
                  <span class="badge">{{ source.items.length }}</span>
                </header>
                <p class="gov-source-hint">{{ source.hint }}</p>
                <div v-if="source.items.length" class="gov-chips">
                  <span
                    v-for="item in source.items.slice(0, 10)"
                    :key="item.id"
                    class="perm-chip"
                    :title="`${item.permissionName} · ${permissionScopeLabel(item.appServiceId, item.appName)}`"
                  >{{ item.permissionCode }}</span>
                  <span v-if="source.items.length > 10" class="perm-chip is-more">+{{ source.items.length - 10 }}</span>
                </div>
                <p v-else class="gov-source-empty">无</p>
              </section>
            </div>
          </div>

          <div class="gov-section">
            <div class="gov-section-head">
              <div>
                <div class="form-section-title">生效权限</div>
                <div class="form-section-hint">与令牌 permissions 声明保持一致,按权限范围分组。</div>
              </div>
            </div>
            <div v-if="effectiveGroups.length" class="gov-perm-groups">
              <section v-for="group in effectiveGroups" :key="group.key" class="gov-perm-group">
                <div class="gov-perm-head">
                  <span class="gov-perm-scope">{{ group.label }}</span>
                  <span class="gov-perm-count">{{ group.items.length }}</span>
                </div>
                <div class="gov-chips">
                  <span v-for="item in group.items" :key="item.id" class="perm-chip is-effective" :title="item.permissionName">{{ item.permissionCode }}</span>
                </div>
              </section>
            </div>
            <EmptyState v-else title="没有生效权限" hint="角色尚未授予权限,或当前范围内没有可结算的权限" icon="shield" />
          </div>

          <div class="gov-section">
            <div class="gov-section-head">
              <div>
                <div class="form-section-title">权限特批</div>
                <div class="form-section-hint">绕过角色直接授予或撤销;已过期记录保留用于审计。</div>
              </div>
              <button v-if="canEditOverrides" class="btn btn-ghost btn-sm" type="button" @click="openOverride()">
                <AppIcon name="plus" :size="14" />
                <span>新增特批</span>
              </button>
            </div>
            <div v-if="visibleOverrides.length" class="gov-overrides">
              <article v-for="item in visibleOverrides" :key="item.id" class="gov-override" :class="{ 'is-expired': !item.active }">
                <div class="gov-override-main">
                  <div class="gov-override-title">
                    <span class="badge" :class="item.grantType === 'GRANT' ? 'is-warn' : 'is-danger'">
                      {{ item.grantType === 'GRANT' ? '授予' : '撤销' }}
                    </span>
                    <span class="cell-mono">{{ item.permissionCode }}</span>
                    <span class="gov-override-name">{{ item.permissionName }}</span>
                  </div>
                  <div class="gov-override-meta">
                    <span>{{ permissionScopeLabel(item.appServiceId, item.appName) }}</span>
                    <span class="gov-dot">·</span>
                    <span>{{ overrideExpiry(item) }}</span>
                    <span class="gov-dot">·</span>
                    <span>更新于 {{ fmtTime(item.updatedTime || item.createdTime) }}</span>
                  </div>
                  <div class="gov-override-reason">理由:{{ item.reason }}</div>
                </div>
                <div v-if="canEditOverrides" class="gov-override-actions">
                  <button class="icon-btn" type="button" title="调整特批" aria-label="调整特批" @click="openOverride(item)">
                    <AppIcon name="edit" :size="14" />
                  </button>
                  <button class="icon-btn is-danger" type="button" title="删除特批" aria-label="删除特批" @click="removeOverride(item)">
                    <AppIcon name="trash" :size="14" />
                  </button>
                </div>
              </article>
            </div>
            <p v-else class="gov-empty-line">当前范围暂无权限特批</p>
          </div>
        </template>

        <EmptyState v-else title="暂无权限数据" hint="切换权限范围或刷新后重试" icon="shield" />
      </template>
    </template>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="detailOpen = false">关闭</button>
    </template>
  </BaseDrawer>

  <AssignmentModal
    v-model:open="roleOpen"
    :title="`分配角色 · ${roleTarget?.username || ''}`"
    subtitle="域角色跨应用生效;应用角色只会进入所属应用的令牌 app_roles"
    :items="roleItems"
    :model-value="roleSelected"
    :loading="roleLoading"
    :saving="roleSaving"
    :error="roleError"
    alert="停用中的角色不参与权限计算;应用角色按所属应用分组,仅对该应用生效。保存后相关账号重新登录生效。"
    search-placeholder="按角色名称或编码过滤…"
    save-label="保存角色"
    @save="saveRoles"
  />

  <BaseModal
    v-model:open="overrideOpen"
    :title="overrideEdit ? '调整权限特批' : '新增权限特批'"
    :subtitle="`目标账号 · ${detailRow?.username || ''}`"
    size="lg"
    :close-on-backdrop="false"
  >
    <div class="override-form">
      <div v-if="overrideEdit" class="override-fixed">
        <div class="override-fixed-copy">
          <span class="cell-mono">{{ overrideEdit.permissionCode }}</span>
          <span class="override-fixed-name">{{ overrideEdit.permissionName }}</span>
        </div>
        <span class="badge is-outline">{{ permissionScopeLabel(overrideEdit.appServiceId, overrideEdit.appName) }}</span>
      </div>
      <template v-else>
        <div class="override-search">
          <select v-model="candidateScope" class="input select" @change="loadCandidates">
            <option v-for="option in scopeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <input
            v-model="candidateKeyword"
            class="input"
            type="search"
            placeholder="搜索权限编码或名称,回车查询"
            @keydown.enter.prevent="loadCandidates"
          />
          <button class="btn btn-ghost" type="button" :disabled="candidateLoading" @click="loadCandidates">
            <AppIcon name="search" :size="14" />
            <span>搜索</span>
          </button>
        </div>
        <div class="candidate-list">
          <SkeletonTable v-if="candidateLoading" :rows="4" :columns="2" />
          <div v-else-if="candidateError" class="alert is-danger">
            <AppIcon name="alert" :size="15" />
            <span>{{ candidateError }}</span>
          </div>
          <template v-else-if="candidates.length">
            <section v-for="group in candidateGroups" :key="group.label" class="candidate-group">
              <div class="candidate-group-title">{{ group.label }}<span>{{ group.items.length }}</span></div>
              <button
                v-for="item in group.items"
                :key="item.id"
                class="candidate-item"
                :class="{ 'is-selected': String(item.id) === overrideForm.permissionId }"
                type="button"
                @click="selectCandidate(item)"
              >
                <span class="candidate-check">
                  <AppIcon v-if="String(item.id) === overrideForm.permissionId" name="check" :size="11" />
                </span>
                <span class="candidate-code">{{ item.permissionCode }}</span>
                <span class="candidate-name">{{ item.permissionName }}</span>
                <span v-if="item.currentGrantType" class="badge" :class="candidateState(item).tone">{{ candidateState(item).text }}</span>
              </button>
            </section>
            <p v-if="candidateTotal > candidates.length" class="candidate-more">
              仅展示前 {{ candidates.length }} 条(共 {{ candidateTotal }} 条),请使用关键词缩小范围。
            </p>
          </template>
          <EmptyState v-else title="没有匹配的权限" hint="调整权限范围或关键词后重试" icon="search" />
        </div>
      </template>

      <div class="override-type">
        <button
          class="override-type-item"
          :class="{ 'is-active': overrideForm.grantType === 'GRANT' }"
          type="button"
          @click="overrideForm.grantType = 'GRANT'"
        >
          <span class="override-type-title">特批授予</span>
          <span class="override-type-hint">在角色结果之外额外补齐该权限</span>
        </button>
        <button
          class="override-type-item is-revoke"
          :class="{ 'is-active': overrideForm.grantType === 'REVOKE' }"
          type="button"
          @click="overrideForm.grantType = 'REVOKE'"
        >
          <span class="override-type-title">特批撤销</span>
          <span class="override-type-hint">从角色与菜单派生结果中扣除该权限</span>
        </button>
      </div>

      <label class="field is-full override-field">
        <span class="field-label">操作原因 <em>*</em></span>
        <textarea
          v-model="overrideForm.reason"
          class="input textarea"
          maxlength="500"
          rows="3"
          placeholder="说明业务背景与审批依据,将随审计日志留存"
        />
      </label>

      <div class="field is-full override-field">
        <span class="field-label">失效时间</span>
        <div class="override-expiry">
          <input v-model="overrideForm.expiredAt" class="input" type="datetime-local" />
          <div class="override-expiry-presets">
            <button class="link-btn" type="button" @click="setExpiry(7)">7 天</button>
            <button class="link-btn" type="button" @click="setExpiry(30)">30 天</button>
            <button class="link-btn" type="button" @click="setExpiry(90)">90 天</button>
            <button class="link-btn" type="button" @click="setExpiry(0)">长期</button>
          </div>
        </div>
        <span class="field-help">留空表示长期有效;到期后记录保留但不再参与权限计算。</span>
      </div>

      <div v-if="overrideError" class="alert is-danger is-full">
        <AppIcon name="alert" :size="15" />
        <span>{{ overrideError }}</span>
      </div>
    </div>
    <template #footer>
      <button v-if="overrideEdit" class="link-btn is-danger override-delete" type="button" :disabled="overrideSaving" @click="removeEditingOverride">
        删除特批
      </button>
      <button class="btn btn-ghost" type="button" :disabled="overrideSaving" @click="overrideOpen = false">取消</button>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="overrideSaving || (!overrideEdit && !overrideForm.permissionId)"
        @click="saveOverride"
      >
        <span v-if="overrideSaving" class="btn-spinner" />
        <span>{{ overrideEdit ? '保存调整' : '保存特批' }}</span>
      </button>
    </template>
  </BaseModal>

  <BaseModal v-model:open="resetOpen" :title="`重置口令 · ${resetTarget?.username || ''}`" size="sm" :close-on-backdrop="false">
    <div class="alert is-warn" style="margin-bottom: 14px">
      <AppIcon name="lock" :size="15" />
      <span>重置后请通过安全渠道交付新口令;该账号已有登录态不会自动失效。</span>
    </div>
    <SchemaForm
      :key="resetKey"
      ref="resetForm"
      :fields="resetFields"
      mode="create"
      :values="{ newPassword: '' }"
    />
    <template #footer>
      <button class="btn btn-ghost" type="button" :disabled="resetBusy" @click="resetOpen = false">取消</button>
      <button class="btn btn-primary" type="button" :disabled="resetBusy" @click="submitReset">
        <span v-if="resetBusy" class="btn-spinner" />
        <span>重置口令</span>
      </button>
    </template>
  </BaseModal>
</template>

<style scoped>
.identity-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.08));
}

.identity-head .avatar {
  width: 42px;
  height: 42px;
  font-size: 17px;
}

.identity-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.identity-name {
  font-size: 15px;
  font-weight: 680;
  color: var(--ink);
}

.identity-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--muted);
}

.identity-dot {
  color: var(--faint);
}

.identity-head .dot-badge {
  margin-left: auto;
}

/* ---------------- 详情标签页 ---------------- */

.detail-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface-2);
}

.detail-tab {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  transition: background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
}

.detail-tab:hover {
  color: var(--ink);
}

.detail-tab.is-active {
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 2px 8px -4px rgba(16, 24, 40, 0.25), inset 0 0 0 1px var(--line-soft);
}

.detail-tab.is-active svg {
  color: var(--gold-deep);
}

.detail-tab-count {
  display: inline-grid;
  place-items: center;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 700;
}

/* ---------------- 权限治理 ---------------- */

.gov-toolbar {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  margin-bottom: 13px;
}

.gov-scope {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.gov-scope > span {
  font-size: 10px;
  font-weight: 650;
  letter-spacing: 0.04em;
  color: var(--faint);
}

.gov-scope .input {
  height: 34px;
  font-size: 12px;
}

.gov-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 16px;
}

.gov-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: linear-gradient(160deg, var(--surface), var(--surface-2));
}

.gov-stat-label {
  font-size: 10px;
  font-weight: 650;
  letter-spacing: 0.04em;
  color: var(--faint);
}

.gov-stat-value {
  font-family: var(--font-mono);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--ink);
}

.gov-stat-time {
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--ink-2);
  overflow-wrap: anywhere;
}

.gov-stat-hint {
  font-size: 9.5px;
  color: var(--faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gov-section {
  margin-bottom: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--line);
}

.gov-section:last-child {
  margin-bottom: 0;
  padding-bottom: 4px;
}

.gov-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 11px;
}

.gov-section-head > div {
  min-width: 0;
}

.gov-section .form-section-title {
  margin-bottom: 3px;
  font-size: 11.5px;
}

.gov-section .form-section-hint {
  line-height: 1.5;
}

.gov-roles {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.gov-role {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
  padding: 9px 11px;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  background: var(--surface-2);
}

.gov-role-main {
  display: flex;
  align-items: baseline;
  gap: 7px;
  min-width: 0;
}

.gov-role-name {
  font-size: 12.5px;
  font-weight: 620;
  color: var(--ink);
}

.gov-role-main .cell-mono {
  font-size: 10.5px;
  color: var(--faint);
}

.gov-role-source {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.gov-sources {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.gov-source {
  --tone: var(--muted);
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
  padding: 10px 11px;
  border: 1px solid var(--line-soft);
  border-radius: 11px;
  background: var(--surface-2);
  box-shadow: inset 3px 0 0 var(--tone);
}

.gov-source.is-success {
  --tone: #3f9a72;
}

.gov-source.is-info {
  --tone: #5d8fbd;
}

.gov-source.is-warn {
  --tone: #d8a02c;
}

.gov-source.is-danger {
  --tone: #c4545c;
}

.gov-source-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.gov-source-title {
  font-size: 11.5px;
  font-weight: 680;
  color: var(--ink);
}

.gov-source-hint {
  margin: 0;
  font-size: 9.5px;
  line-height: 1.5;
  color: var(--faint);
}

.gov-source-empty {
  margin: 0;
  font-size: 10.5px;
  color: var(--faint);
}

.gov-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}

.perm-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  height: 21px;
  padding: 0 7px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--surface);
  color: var(--ink-2);
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.perm-chip.is-effective {
  border-color: #c4e3d4;
  background: var(--success-soft);
  color: #25704f;
}

.perm-chip.is-more {
  border-style: dashed;
  color: var(--faint);
}

.gov-perm-groups {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.gov-perm-group {
  padding: 10px 11px;
  border: 1px solid var(--line-soft);
  border-radius: 11px;
  background: linear-gradient(160deg, var(--surface), var(--surface-2));
}

.gov-perm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 7px;
}

.gov-perm-scope {
  font-size: 11.5px;
  font-weight: 650;
  color: var(--ink);
}

.gov-perm-count {
  display: inline-grid;
  place-items: center;
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--surface-3);
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 700;
}

.gov-overrides {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.gov-override {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 11px;
  border: 1px solid var(--line-soft);
  border-radius: 11px;
  background: var(--surface-2);
}

.gov-override.is-expired {
  opacity: 0.62;
}

.gov-override-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.gov-override-title {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}

.gov-override-title .cell-mono {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}

.gov-override-name {
  font-size: 11px;
  color: var(--muted);
}

.gov-override-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  font-size: 10px;
  color: var(--faint);
}

.gov-dot {
  color: var(--line-strong);
}

.gov-override-reason {
  font-size: 10.5px;
  line-height: 1.55;
  color: var(--muted);
  overflow-wrap: anywhere;
}

.gov-override-actions {
  display: flex;
  gap: 2px;
  flex: none;
}

.gov-override-actions .icon-btn {
  width: 28px;
  height: 28px;
}

.gov-empty-line {
  margin: 0;
  padding: 14px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  color: var(--faint);
  font-size: 11.5px;
  text-align: center;
}

.gov-scope code {
  font-size: 10.5px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.55);
}

/* ---------------- 权限特批弹窗 ---------------- */

.override-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.override-search {
  display: flex;
  gap: 8px;
  align-items: center;
}

.override-search .select {
  width: 190px;
  flex: none;
}

.override-search .input {
  flex: 1;
  min-width: 0;
}

.override-fixed {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 13px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.08));
}

.override-fixed-copy {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
  flex-wrap: wrap;
}

.override-fixed-copy .cell-mono {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink);
}

.override-fixed-name {
  font-size: 11.5px;
  color: var(--muted);
}

.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 31vh;
  min-height: 96px;
  overflow-y: auto;
  padding: 3px;
  border: 1px solid var(--line-soft);
  border-radius: 11px;
  background: var(--surface-2);
}

.candidate-list :deep(.empty) {
  padding: 30px 16px;
}

.candidate-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.candidate-group-title {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 7px;
  background: var(--surface-2);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.03em;
  color: var(--faint);
}

.candidate-group-title span {
  font-family: var(--font-mono);
  font-weight: 600;
}

.candidate-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 9px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  transition: background 0.13s ease, border-color 0.13s ease;
}

.candidate-item:hover {
  background: var(--surface);
  border-color: var(--line-soft);
}

.candidate-item.is-selected {
  border-color: rgba(168, 115, 10, 0.38);
  background: rgba(242, 181, 46, 0.1);
}

.candidate-check {
  flex: none;
  width: 15px;
  height: 15px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line-strong);
  border-radius: 50%;
  background: var(--surface);
  color: #fffdf5;
}

.candidate-item.is-selected .candidate-check {
  border-color: var(--gold-deep);
  background: var(--gold-deep);
}

.candidate-code {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
}

.candidate-name {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-more {
  margin: 2px 4px 6px;
  font-size: 10px;
  color: var(--faint);
}

.override-type {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.override-type-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface-2);
  text-align: left;
  transition: border-color 0.14s ease, background 0.14s ease, box-shadow 0.14s ease;
}

.override-type-item:hover {
  border-color: var(--line-strong);
}

.override-type-item.is-active {
  border-color: rgba(168, 115, 10, 0.45);
  background: rgba(242, 181, 46, 0.1);
  box-shadow: inset 0 0 0 1px rgba(242, 181, 46, 0.16);
}

.override-type-item.is-revoke.is-active {
  border-color: #e0a9ad;
  background: var(--danger-soft);
  box-shadow: inset 0 0 0 1px rgba(192, 74, 82, 0.1);
}

.override-type-title {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink);
}

.override-type-hint {
  font-size: 10px;
  line-height: 1.5;
  color: var(--faint);
}

.override-field {
  gap: 5px;
}

.override-field .field-label em {
  color: var(--danger);
  font-style: normal;
}

.override-field .textarea {
  min-height: 76px;
}

.override-expiry {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.override-expiry .input {
  flex: 1;
  min-width: 180px;
}

.override-expiry-presets {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}

.override-expiry-presets .link-btn {
  font-size: 11px;
}

.override-delete {
  margin-right: auto;
  align-self: center;
}

@media (max-width: 640px) {
  .gov-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .gov-sources {
    grid-template-columns: minmax(0, 1fr);
  }

  .override-search {
    flex-wrap: wrap;
  }

  .override-search .select {
    width: 100%;
  }

  .override-type {
    grid-template-columns: minmax(0, 1fr);
  }
}

.org-list {
  display: flex;
  flex-direction: column;
}

.org-row {
  display: grid;
  grid-template-columns: minmax(140px, 1.4fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px solid var(--line);
}

.org-dept {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
</style>
