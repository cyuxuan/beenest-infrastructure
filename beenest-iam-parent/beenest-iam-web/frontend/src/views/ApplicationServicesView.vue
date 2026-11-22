<script setup lang="ts">
import { computed, h, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import AppBackfillDrawer from '@/components/AppBackfillDrawer.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import DetailList from '@/components/DetailList.vue';
import ResourcePage from '@/components/ResourcePage.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime, safeHttpUrl } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { FilterOption, ResourceConfig } from '@/lib/types';
import { badge, dotBadge, externalLink, muted, strongSub } from '@/lib/ui';
import { can } from '@/stores/auth';

const router = useRouter();

const MENU_MODES = [
  { value: 'IAM', label: 'IAM 全量托管' },
  { value: 'HYBRID', label: '仅一级模块(推荐)' },
  { value: 'APP', label: '应用自管' },
];

const SSO_MODES = [
  { value: 'OIDC', label: 'OIDC 授权码' },
  { value: 'CAS', label: 'CAS' },
  { value: 'SAML', label: 'SAML 2.0' },
  { value: 'BEARER', label: 'Bearer 直连' },
];

const APP_TYPES = [
  { value: 'WEB', label: 'WEB 浏览器应用' },
  { value: 'APP', label: 'APP 移动应用' },
  { value: 'MINI_PROGRAM', label: '小程序' },
  { value: 'API', label: 'API 服务' },
];

const VISIBILITY = [
  { value: 'PUBLIC', label: '全员可见' },
  { value: 'ROLE', label: '按角色可见' },
  { value: 'PERMISSION', label: '按权限码可见' },
];

const AUTHORIZATION_MODES = [
  { value: 'IAM_MANAGED', label: 'IAM 托管' },
  { value: 'MAPPED', label: '角色映射' },
  { value: 'APP_MANAGED', label: '应用自管' },
];

const TOKEN_PROFILES = [
  { value: 'SCOPED', label: 'SCOPED · 按应用收敛' },
  { value: 'LEGACY_GLOBAL', label: 'LEGACY_GLOBAL · 全域兼容' },
];

const AUTH_MODE_LABEL: Record<string, string> = Object.fromEntries(
  AUTHORIZATION_MODES.map((item) => [item.value, item.label]),
);
const TOKEN_PROFILE_LABEL: Record<string, string> = Object.fromEntries(
  TOKEN_PROFILES.map((item) => [item.value, item.label]),
);

const ADMIN_SUBJECT_TYPES = [
  { value: 'USER', label: '指定用户' },
  { value: 'GROUP', label: '用户组' },
];

const MAPPING_SOURCE_TYPES = [
  { value: 'GROUP', label: '用户组' },
  { value: 'REALM_ROLE', label: '域角色' },
];

const VISIBILITY_LABEL: Record<string, string> = Object.fromEntries(
  VISIBILITY.map((item) => [item.value, item.label]),
);
const MENU_MODE_LABEL: Record<string, string> = Object.fromEntries(
  MENU_MODES.map((item) => [item.value, item.label]),
);

const USER_STATUS: Record<number, { text: string; tone: 'is-success' | 'is-danger' | 'is-warn' }> = {
  0: { text: '禁用', tone: 'is-danger' },
  1: { text: '正常', tone: 'is-success' },
  2: { text: '锁定', tone: 'is-warn' },
};

const USER_TYPE: Record<string, string> = {
  INTERNAL: '内部用户',
  EXTERNAL: '外部用户',
  SERVICE: '服务账号',
};

/**
 * 客户端下拉选项。
 * 门户不参与发起授权流程,应用绑哪个客户端都不影响启动方式,这里只取名称与标识。
 */
async function clientOptions(): Promise<FilterOption[]> {
  try {
    const page = await api<any>('/api/v1/admin/clients', {
      query: { pageNum: 1, pageSize: 300 },
      silent: true,
    });
    const records = page?.records || [];
    return records.map((client: any) => ({
      value: client.id,
      label: `${client.clientName}(${client.clientKey})`,
    }));
  } catch {
    return [];
  }
}

/* ---------------- 详情 ---------------- */

const detailOpen = ref(false);
const detailRow = ref<any>(null);

function openDetail(row: any) {
  detailRow.value = row;
  detailOpen.value = true;
}

const detailEntry = computed(() => safeHttpUrl(detailRow.value?.entryUrl));
const detailIcon = computed(() => safeHttpUrl(detailRow.value?.iconUrl));

function visibilityCell(row: any) {
  const tone = row.visibility === 'PUBLIC' ? '' : row.visibility === 'ROLE' ? 'is-gold' : 'is-info';
  const sub =
    row.visibility === 'ROLE'
      ? '按角色授权名单过滤'
      : row.visibility === 'PERMISSION'
        ? row.requiredPermissionCode || '未设置权限码'
        : '无需授权';
  return h('div', { class: 'cell-stack' }, [
    badge(VISIBILITY_LABEL[row.visibility] || row.visibility || '—', tone),
    h('div', { class: 'cell-sub' }, sub),
  ]);
}

/* ---------------- 授权名单 ---------------- */

const grantOpen = ref(false);
const grantTarget = ref<any>(null);
const grantSubjectType = ref<'ROLE' | 'USER'>('ROLE');
const grantLoading = ref(false);
const grantSaving = ref(false);
const grantError = ref('');
const grantItems = ref<any[]>([]);
const grantSelected = ref<Array<string | number>>([]);

const grantTitle = computed(() => {
  const label = grantSubjectType.value === 'ROLE' ? '角色授权' : '指定到人';
  return `${label} · ${grantTarget.value?.appName || ''}`;
});

const grantAlert = computed(() => {
  if (grantTarget.value?.visibility === 'ROLE') {
    return '当前应用可见性为「按角色可见」,本名单决定谁能在启动台看到并启动该应用。';
  }
  return `当前应用可见性是「${VISIBILITY_LABEL[grantTarget.value?.visibility] || grantTarget.value?.visibility || '未知'}」,本名单暂不参与门户过滤;切换为按角色可见后立即生效。`;
});

function roleItem(role: any, group: string) {
  return {
    id: role.id,
    label: role.roleName,
    hint: role.roleCode,
    group,
    tags: role.isActive === false ? [{ text: '已停用', tone: 'is-danger' as const }] : [],
  };
}

async function openGrants(row: any, subjectType: 'ROLE' | 'USER') {
  grantTarget.value = row;
  grantSubjectType.value = subjectType;
  grantOpen.value = true;
  grantLoading.value = true;
  grantSaving.value = false;
  grantError.value = '';
  grantItems.value = [];
  grantSelected.value = [];
  try {
    const grants = await api<any[]>(`/api/v1/admin/application-services/${row.id}/grants`, {
      query: { subjectType },
      silent: true,
    });
    if (subjectType === 'ROLE') {
      // 域角色可用于全部应用,应用角色仅限所属应用,分两组呈现;
      // 其他应用的应用角色不进入候选,授权后会被作用域校验拒绝
      const [realmPage, appPage] = await Promise.all([
        api<any>('/api/v1/admin/roles', {
          query: { containerType: 'REALM', pageNum: 1, pageSize: 300 },
          silent: true,
        }),
        api<any>('/api/v1/admin/roles', {
          query: { appServiceId: row.id, pageNum: 1, pageSize: 300 },
          silent: true,
        }),
      ]);
      const seen = new Set<number>();
      grantItems.value = [
        ...(realmPage?.records || []).map((role: any) => roleItem(role, '域角色 · 全部应用可用')),
        ...(appPage?.records || []).map((role: any) => roleItem(role, '本应用角色 · 仅本应用')),
      ].filter((item) => (seen.has(item.id) ? false : (seen.add(item.id), true)));
    } else {
      const page = await api<any>('/api/v1/admin/users', {
        query: { pageNum: 1, pageSize: 300 },
        silent: true,
      });
      grantItems.value = (page?.records || []).map((user: any) => {
        const status = USER_STATUS[user.status] || { text: `状态 ${user.status}`, tone: '' as const };
        return {
          id: user.id,
          label: user.nickname || user.username,
          hint: user.username,
          group: USER_TYPE[user.userType] || '其他账号',
          tags: [{ text: status.text, tone: status.tone }],
        };
      });
    }
    grantSelected.value = (grants || []).map((grant: any) => grant.subjectId);
  } catch (error) {
    grantError.value = errorMessage(error);
  } finally {
    grantLoading.value = false;
  }
}

async function saveGrants(ids: Array<string | number>) {
  if (!grantTarget.value) return;
  grantSaving.value = true;
  grantError.value = '';
  try {
    await api(`/api/v1/admin/application-services/${grantTarget.value.id}/grants`, {
      query: { subjectType: grantSubjectType.value },
      method: 'PUT',
      body: { subjectIds: ids },
      silent: true,
    });
    toast('应用授权已更新', 'success');
    grantOpen.value = false;
  } catch (error) {
    grantError.value = errorMessage(error);
  } finally {
    grantSaving.value = false;
  }
}

/* ---------------- 应用管理员 ---------------- */

const adminOpen = ref(false);
const adminTarget = ref<any>(null);
const adminLoading = ref(false);
const adminSaving = ref(false);
const adminError = ref('');
const adminItems = ref<any[]>([]);
const adminOptions = ref<FilterOption[]>([]);
const adminOptionsLoading = ref(false);
const adminOptionsError = ref('');
const adminForm = reactive<{ subjectType: 'USER' | 'GROUP'; subjectId: string }>({
  subjectType: 'USER',
  subjectId: '',
});

async function loadAdminSubjects(type: 'USER' | 'GROUP') {
  adminOptionsLoading.value = true;
  adminOptionsError.value = '';
  adminOptions.value = [];
  try {
    const page = await api<any>(type === 'USER' ? '/api/v1/admin/users' : '/api/v1/admin/groups', {
      query: { pageNum: 1, pageSize: 500 },
      silent: true,
    });
    adminOptions.value = (page?.records || []).map((item: any) =>
      type === 'USER'
        ? {
            value: item.id,
            label: item.nickname ? `${item.nickname}(${item.username})` : item.username,
          }
        : {
            value: item.id,
            label: `${item.groupName}(${item.groupCode})`,
          },
    );
  } catch (error) {
    adminOptionsError.value = errorMessage(error);
  } finally {
    adminOptionsLoading.value = false;
  }
}

async function loadAdmins() {
  if (!adminTarget.value) return;
  adminLoading.value = true;
  adminError.value = '';
  try {
    adminItems.value = await api<any[]>(`/api/v1/admin/apps/${adminTarget.value.id}/admins`, {
      silent: true,
    });
  } catch (error) {
    adminError.value = errorMessage(error);
  } finally {
    adminLoading.value = false;
  }
}

function openAdmins(row: any) {
  adminTarget.value = row;
  adminOpen.value = true;
  adminForm.subjectType = 'USER';
  adminForm.subjectId = '';
  adminItems.value = [];
  loadAdminSubjects('USER');
  loadAdmins();
}

function onAdminSubjectTypeChange() {
  adminForm.subjectId = '';
  loadAdminSubjects(adminForm.subjectType);
}

async function addAdmin() {
  if (!adminTarget.value || !adminForm.subjectId) return;
  adminSaving.value = true;
  adminError.value = '';
  try {
    await api(`/api/v1/admin/apps/${adminTarget.value.id}/admins`, {
      method: 'POST',
      body: { subjectType: adminForm.subjectType, subjectId: Number(adminForm.subjectId) },
      silent: true,
    });
    toast('应用管理员已添加', 'success');
    adminForm.subjectId = '';
    await loadAdmins();
  } catch (error) {
    adminError.value = errorMessage(error);
  } finally {
    adminSaving.value = false;
  }
}

async function removeAdmin(item: any) {
  if (!adminTarget.value) return;
  const confirmed = await confirmDialog({
    title: '移除应用管理员',
    message: `确认移除「${item.subjectName || `#${item.subjectId}`}」的管理员身份?`,
    confirmText: '移除',
  });
  if (!confirmed) return;
  adminError.value = '';
  try {
    await api(`/api/v1/admin/apps/${adminTarget.value.id}/admins/${item.id}`, {
      method: 'DELETE',
      silent: true,
    });
    toast('应用管理员已移除', 'success');
    await loadAdmins();
  } catch (error) {
    adminError.value = errorMessage(error);
  }
}

/* ---------------- 角色映射 ---------------- */

const mappingOpen = ref(false);
const mappingTarget = ref<any>(null);
const mappingLoading = ref(false);
const mappingSaving = ref(false);
const mappingError = ref('');
const mappingItems = ref<any[]>([]);
const mappingOptions = ref<FilterOption[]>([]);
const mappingOptionsLoading = ref(false);
const mappingOptionsError = ref('');
const mappingForm = reactive<{
  sourceType: 'GROUP' | 'REALM_ROLE';
  sourceId: string;
  targetRoleCode: string;
}>({ sourceType: 'GROUP', sourceId: '', targetRoleCode: '' });

async function loadMappingSources(type: 'GROUP' | 'REALM_ROLE') {
  mappingOptionsLoading.value = true;
  mappingOptionsError.value = '';
  mappingOptions.value = [];
  try {
    const page = await api<any>(type === 'GROUP' ? '/api/v1/admin/groups' : '/api/v1/admin/roles', {
      query:
        type === 'GROUP'
          ? { pageNum: 1, pageSize: 500 }
          : { containerType: 'REALM', pageNum: 1, pageSize: 500 },
      silent: true,
    });
    mappingOptions.value = (page?.records || [])
      .filter((item: any) => type === 'GROUP' || (item.containerType || 'REALM') === 'REALM')
      .map((item: any) =>
        type === 'GROUP'
          ? { value: item.id, label: `${item.groupName}(${item.groupCode})` }
          : { value: item.id, label: `${item.roleName}(${item.roleCode})` },
      );
  } catch (error) {
    mappingOptionsError.value = errorMessage(error);
  } finally {
    mappingOptionsLoading.value = false;
  }
}

async function loadMappings() {
  if (!mappingTarget.value) return;
  mappingLoading.value = true;
  mappingError.value = '';
  try {
    mappingItems.value = await api<any[]>(
      `/api/v1/admin/apps/${mappingTarget.value.id}/role-mappings`,
      { silent: true },
    );
  } catch (error) {
    mappingError.value = errorMessage(error);
  } finally {
    mappingLoading.value = false;
  }
}

function openMappings(row: any) {
  mappingTarget.value = row;
  mappingOpen.value = true;
  mappingForm.sourceType = 'GROUP';
  mappingForm.sourceId = '';
  mappingForm.targetRoleCode = '';
  mappingItems.value = [];
  loadMappingSources('GROUP');
  loadMappings();
}

function onMappingSourceTypeChange() {
  mappingForm.sourceId = '';
  loadMappingSources(mappingForm.sourceType);
}

async function addMapping() {
  if (!mappingTarget.value) return;
  const targetRoleCode = mappingForm.targetRoleCode.trim();
  if (!mappingForm.sourceId) {
    mappingError.value = '请选择映射来源';
    return;
  }
  if (!targetRoleCode) {
    mappingError.value = '请填写目标角色码';
    return;
  }
  mappingSaving.value = true;
  mappingError.value = '';
  try {
    await api(`/api/v1/admin/apps/${mappingTarget.value.id}/role-mappings`, {
      method: 'POST',
      body: {
        sourceType: mappingForm.sourceType,
        sourceId: Number(mappingForm.sourceId),
        targetRoleCode,
        enabled: true,
      },
      silent: true,
    });
    toast('角色映射已添加', 'success');
    mappingForm.targetRoleCode = '';
    await loadMappings();
  } catch (error) {
    mappingError.value = errorMessage(error);
  } finally {
    mappingSaving.value = false;
  }
}

async function removeMapping(item: any) {
  if (!mappingTarget.value) return;
  const confirmed = await confirmDialog({
    title: '删除角色映射',
    message: `确认删除「${item.sourceName || `#${item.sourceId}`} → ${item.targetRoleCode}」的映射?`,
    confirmText: '删除',
  });
  if (!confirmed) return;
  mappingError.value = '';
  try {
    await api(`/api/v1/admin/apps/${mappingTarget.value.id}/role-mappings/${item.id}`, {
      method: 'DELETE',
      silent: true,
    });
    toast('角色映射已删除', 'success');
    await loadMappings();
  } catch (error) {
    mappingError.value = errorMessage(error);
  }
}

/* ---------------- 权限回填 ---------------- */

const backfillOpen = ref(false);
const backfillTarget = ref<any>(null);

function openBackfill(row: any) {
  backfillTarget.value = row;
  backfillOpen.value = true;
}

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '应用门户',
  desc: '应用条目决定用户在启动台看到什么、点击后如何单点进入;可见性由角色或权限码控制。',
  endpoint: '/api/v1/admin/application-services',
  permCreate: 'app-service:create',
  createLabel: '新增应用',
  deleteName: '应用',
  rowName: (row) => row.appName,
  filters: [
    { name: 'appCode', label: '应用编码', placeholder: '支持模糊匹配' },
    { name: 'appName', label: '应用名称', placeholder: '支持模糊匹配' },
    { name: 'category', label: '分类', placeholder: '如 办公协同' },
    {
      name: 'visibility',
      label: '可见性',
      type: 'select',
      options: [{ value: '', label: '全部可见性' }, ...VISIBILITY],
    },
    {
      name: 'authorizationMode',
      label: '授权模式',
      type: 'select',
      options: [{ value: '', label: '全部授权模式' }, ...AUTHORIZATION_MODES],
    },
    {
      name: 'enabled',
      label: '状态',
      type: 'select',
      options: [
        { value: '', label: '全部状态' },
        { value: 'true', label: '启用中' },
        { value: 'false', label: '已停用' },
      ],
    },
  ],
  columns: [
    { label: '应用', render: (row) => strongSub(row.appName, row.appCode) },
    {
      label: '接入方式',
      render: (row) =>
        h('span', { class: 'tag-row' }, [
          badge(row.ssoMode || 'OIDC', 'is-info'),
          badge(row.appType || 'WEB', '', true),
        ]),
    },
    {
      label: '入口',
      render: (row) =>
        row.entryUrl
          ? externalLink(row.entryUrl, row.entryUrl, '220px')
          : muted('—'),
    },
    { label: '可见性', render: (row) => visibilityCell(row) },
    {
      label: '菜单托管',
      render: (row) => badge(MENU_MODE_LABEL[row.menuMode || 'HYBRID'] || row.menuMode || 'HYBRID', 'is-gold', true),
    },
    {
      label: '授权模式',
      render: (row) =>
        h('div', { class: 'cell-stack' }, [
          badge(
            AUTH_MODE_LABEL[row.authorizationMode || 'IAM_MANAGED'] || row.authorizationMode || 'IAM_MANAGED',
            row.authorizationMode === 'APP_MANAGED' ? 'is-warn' : row.authorizationMode === 'MAPPED' ? 'is-info' : 'is-gold',
            true,
          ),
          h(
            'div',
            { class: 'cell-sub' },
            TOKEN_PROFILE_LABEL[row.tokenProfile || 'LEGACY_GLOBAL'] || row.tokenProfile || 'LEGACY_GLOBAL',
          ),
        ]),
    },
    {
      label: '状态',
      render: (row) =>
        row.enabled === false ? dotBadge('已停用', 'is-danger') : dotBadge('启用中', 'is-success'),
    },
    { label: '更新时间', render: (row) => muted(fmtTime(row.updatedTime || row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'app-service:update', onClick: () => ctx.openEditor(row) },
    { label: '授权', perm: 'app-service:assign', onClick: () => openGrants(row, 'ROLE') },
    { label: '指定到人', perm: 'app-service:assign', onClick: () => openGrants(row, 'USER') },
    { label: '管理员', perm: 'app-service:assign', onClick: () => openAdmins(row) },
    { label: '角色映射', perm: 'app-service:update', onClick: () => openMappings(row) },
    { label: '权限回填', perm: 'app-service:update', onClick: () => openBackfill(row) },
    {
      label: '菜单',
      perm: 'menu:view',
      onClick: () => router.push({ path: '/menus', query: { appServiceId: String(row.id) } }),
    },
    { label: '删除', perm: 'app-service:delete', danger: true, onClick: () => ctx.remove(row) },
  ],
  rowClick: (row) => openDetail(row),
  form: {
    title: '应用',
    subtitle: '应用编码创建后不可修改',
    fields: async () => [
      { name: 'appCode', label: '应用编码', required: true, mono: true, placeholder: '如 oa-portal', help: '字母、数字、下划线、中划线,长度 2-100' },
      { name: 'appName', label: '应用名称', required: true, placeholder: '如 协同办公' },
      {
        name: 'audiences',
        label: '令牌受众',
        type: 'tags',
        full: true,
        placeholder: 'urn:iam:app:oa-portal',
        help: '谁要接收这张令牌就填谁:输入一个值后按回车(别用逗号连写),有多个就逐个输入并回车。留空则自动用 urn:iam:app:{应用编码}',
      },
      { name: 'appType', label: '应用类型', type: 'select', options: APP_TYPES },
      { name: 'ssoMode', label: '接入方式', type: 'select', options: SSO_MODES },
      { name: 'ssoClientPk', label: '绑定客户端', type: 'select', placeholder: '请选择客户端', options: await clientOptions(), help: 'OIDC 接入必须绑定本域客户端' },
      { name: 'category', label: '分类', placeholder: '如 办公协同' },
      { name: 'menuMode', label: '菜单托管', type: 'select', options: MENU_MODES, help: '仅一级模块:IAM 只管模块与权限,深层菜单由应用自管' },
      {
        name: 'authorizationMode',
        label: '授权模式',
        type: 'select',
        options: AUTHORIZATION_MODES,
        help: 'IAM 托管:角色与权限全在 IAM 维护;角色映射:应用保留自有角色;应用自管:IAM 只认证身份',
      },
      {
        name: 'tokenProfile',
        label: '令牌档',
        type: 'select',
        options: TOKEN_PROFILES,
        help: 'SCOPED 只输出本应用权限、当前客户端角色与 app_roles;LEGACY_GLOBAL 保留全域扁平权限,仅用于存量兼容',
      },
      { name: 'entryUrl', label: '入口地址', full: true, placeholder: 'https://oa.example.com', mono: true, help: '门户点击卡片后跳这里。登录态由应用在入口页自己发起:门户不拼授权地址,state/nonce/PKCE 都归应用生成与校验' },
      { name: 'iconUrl', label: '图标地址', full: true, placeholder: 'https://cdn.example.com/oa.svg', mono: true },
      { name: 'visibility', label: '可见性', type: 'select', options: VISIBILITY },
      { name: 'requiredPermissionCode', label: '所需权限码', placeholder: '如 oa:access', mono: true, help: '仅按权限码可见时生效' },
      { name: 'sortOrder', label: '排序值', type: 'number', placeholder: '数值越小越靠前' },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    editFields: async () => [
      { name: 'appName', label: '应用名称', required: true },
      {
        name: 'audiences',
        label: '令牌受众',
        type: 'tags',
        full: true,
        placeholder: 'urn:iam:app:oa-portal',
        help: '输入一个值后按回车添加,多个值逐个添加;清空后新签发的令牌回落到 urn:iam:app:{应用编码}',
      },
      { name: 'appType', label: '应用类型', type: 'select', options: APP_TYPES },
      { name: 'ssoMode', label: '接入方式', type: 'select', options: SSO_MODES },
      { name: 'ssoClientPk', label: '绑定客户端', type: 'select', placeholder: '留空表示保持现有绑定', options: await clientOptions(), help: 'OIDC 接入必须绑定本域客户端' },
      { name: 'category', label: '分类' },
      { name: 'menuMode', label: '菜单托管', type: 'select', options: MENU_MODES },
      { name: 'authorizationMode', label: '授权模式', type: 'select', options: AUTHORIZATION_MODES },
      {
        name: 'tokenProfile',
        label: '令牌档',
        type: 'select',
        options: TOKEN_PROFILES,
        help: '切换为 SCOPED 前确认应用依赖的权限已归属本应用;切换后新签发的令牌立即生效',
      },
      { name: 'entryUrl', label: '入口地址', full: true, mono: true },
      { name: 'iconUrl', label: '图标地址', full: true, mono: true },
      { name: 'visibility', label: '可见性', type: 'select', options: VISIBILITY },
      { name: 'requiredPermissionCode', label: '所需权限码', mono: true },
      { name: 'sortOrder', label: '排序值', type: 'number' },
      { name: 'enabled', label: '启用应用', type: 'switch', onText: '启用中' },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    defaults: { appType: 'WEB', ssoMode: 'OIDC', visibility: 'PERMISSION', sortOrder: 0, menuMode: 'HYBRID', authorizationMode: 'IAM_MANAGED', tokenProfile: 'SCOPED' },
    fromRow: (row) => ({
      appName: row.appName,
      audiences: [...(row.audiences || [])],
      appType: row.appType || 'WEB',
      ssoMode: row.ssoMode || 'OIDC',
      ssoClientPk: row.ssoClientPk ?? '',
      category: row.category,
      menuMode: row.menuMode || 'HYBRID',
      authorizationMode: row.authorizationMode || 'IAM_MANAGED',
      tokenProfile: row.tokenProfile || 'SCOPED',
      entryUrl: row.entryUrl,
      iconUrl: row.iconUrl,
      visibility: row.visibility || 'PERMISSION',
      requiredPermissionCode: row.requiredPermissionCode,
      sortOrder: row.sortOrder,
      enabled: row.enabled !== false,
      description: row.description,
    }),
    validate: (values) => {
      // 受众写错是静默故障:令牌照签,但每次调用都被资源服务器拒掉,
      // 所以在保存前把"逗号连写/带空格/超量"这三类常见错法挡在门外
      const audiences = Array.isArray(values.audiences) ? values.audiences : [];
      if (audiences.length > 5) {
        return '令牌受众最多 5 个';
      }
      for (const raw of audiences) {
        const text = String(raw).trim();
        if (!text) return '令牌受众不能有空值';
        if (/[,，、;；]/.test(text)) {
          return `「${text}」里含有逗号:请按回车把它拆成两个受众,而不是连在一个里`;
        }
        if (/\s/.test(text)) {
          return `「${text}」里含有空格:受众值不能带空格`;
        }
        if (text.length > 200) {
          return `「${text}」超过 200 字符`;
        }
      }
      if (values.ssoMode === 'OIDC' && !values.ssoClientPk) {
        return 'OIDC 接入方式必须绑定本域客户端';
      }
      if (values.visibility === 'PERMISSION' && !String(values.requiredPermissionCode || '').trim()) {
        return '按权限码可见时必须填写所需权限码';
      }
    },
    toCreate: (values) => ({
      ...values,
      ssoClientPk: values.ssoClientPk ? Number(values.ssoClientPk) : null,
    }),
    toUpdate: (values) => ({
      ...values,
      ssoClientPk: values.ssoClientPk ? Number(values.ssoClientPk) : null,
    }),
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseDrawer v-model:open="detailOpen" :title="detailRow?.appName || '应用详情'" :subtitle="detailRow?.appCode">
    <template v-if="detailRow">
      <div class="app-head">
        <span class="app-mark">
          <img v-if="detailIcon" :src="detailIcon" alt="" />
          <AppIcon v-else name="globe" :size="22" />
        </span>
        <div class="app-head-copy">
          <div class="app-head-name">{{ detailRow.appName }}</div>
          <div class="app-head-code">{{ detailRow.appCode }}</div>
        </div>
        <span v-if="detailRow.enabled === false" class="badge is-danger">已停用</span>
        <span v-else class="badge is-success">启用中</span>
      </div>

      <div class="app-links">
        <a v-if="detailEntry" class="app-link" :href="detailEntry" target="_blank" rel="noopener noreferrer">
          <AppIcon name="external" :size="14" />
          <span>打开应用入口</span>
          <span class="app-link-href">{{ detailEntry }}</span>
        </a>
        <div v-else class="app-link is-muted">
          <AppIcon name="info" :size="14" />
          <span>未配置可用的 http/https 入口地址</span>
        </div>
      </div>

      <DetailList
        :rows="[
          { label: '应用主键', value: detailRow.id, mono: true },
          { label: '应用类型', value: detailRow.appType || 'WEB' },
          { label: '令牌受众', value: (detailRow.audiences || []).join(', ') || `urn:iam:app:${detailRow.appCode}(派生)`, mono: true },
          { label: '接入方式', value: detailRow.ssoMode || 'OIDC' },
          { label: '绑定客户端', value: detailRow.ssoClientPk ? `#${detailRow.ssoClientPk}` : '未绑定', mono: Boolean(detailRow.ssoClientPk) },
          { label: '分类', value: detailRow.category },
          { label: '菜单托管', value: MENU_MODE_LABEL[detailRow.menuMode || 'HYBRID'] || detailRow.menuMode },
          { label: '授权模式', value: AUTH_MODE_LABEL[detailRow.authorizationMode || 'IAM_MANAGED'] || detailRow.authorizationMode || 'IAM_MANAGED' },
          { label: '令牌档', value: TOKEN_PROFILE_LABEL[detailRow.tokenProfile || 'LEGACY_GLOBAL'] || detailRow.tokenProfile || 'LEGACY_GLOBAL', mono: true },
          { label: '入口地址', value: detailRow.entryUrl, mono: true },
          { label: '图标地址', value: detailRow.iconUrl, mono: true },
          { label: '可见性', value: VISIBILITY_LABEL[detailRow.visibility] || detailRow.visibility },
          { label: '所需权限码', value: detailRow.requiredPermissionCode, mono: true },
          { label: '排序值', value: detailRow.sortOrder ?? 0 },
          { label: '说明', value: detailRow.description },
          { label: '更新时间', value: fmtTime(detailRow.updatedTime || detailRow.createdTime) },
        ]"
      />

      <div class="alert is-info" style="margin-top: 16px">
        <span>「授权」维护按角色可见名单,「指定到人」维护用户直授名单;两者整体覆盖保存、互不影响。</span>
      </div>
      <div v-if="detailRow.authorizationMode === 'MAPPED'" class="alert is-info" style="margin-top: 10px">
        <span>角色映射模式:令牌通过 app_roles 声明输出映射后的应用角色码,可在行操作「角色映射」中维护。</span>
      </div>
      <div v-if="(detailRow.tokenProfile || 'SCOPED') === 'SCOPED'" class="alert is-info" style="margin-top: 10px">
        <span>SCOPED 令牌只输出本应用的权限、当前客户端角色与 app_roles;平台权限与其他应用角色不会进入该令牌。</span>
      </div>
      <div v-else class="alert is-warn" style="margin-top: 10px">
        <span>LEGACY_GLOBAL 为迁移期兼容档,令牌仍带全域扁平权限;确认应用已迁移到应用级权限后应切换为 SCOPED。</span>
      </div>
      <div v-if="(detailRow.tokenProfile || 'SCOPED') === 'LEGACY_GLOBAL'" class="alert is-info" style="margin-top: 10px">
        <span>切换到 SCOPED 前,先用「权限回填」把本应用正在使用、但仍挂在平台命名空间的权限与菜单迁到本应用,避免切换后授权丢失。</span>
      </div>
    </template>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="detailOpen = false">关闭</button>
      <button
        v-if="can('app-service:update') && detailRow"
        class="btn"
        type="button"
        @click="detailOpen = false; openBackfill(detailRow)"
      >
        权限回填
      </button>
      <button
        v-if="can('app-service:assign') && detailRow"
        class="btn"
        type="button"
        @click="detailOpen = false; openGrants(detailRow, 'ROLE')"
      >
        维护授权名单
      </button>
    </template>
  </BaseDrawer>

  <AssignmentModal
    v-model:open="grantOpen"
    :title="grantTitle"
    :subtitle="grantSubjectType === 'ROLE' ? '仅当可见性为「按角色可见」时参与门户过滤' : '直接授予到人,不受角色变化影响'"
    :items="grantItems"
    :model-value="grantSelected"
    :loading="grantLoading"
    :saving="grantSaving"
    :error="grantError"
    :alert="grantAlert"
    :alert-tone="grantTarget?.visibility === 'ROLE' ? 'info' : 'warn'"
    :search-placeholder="grantSubjectType === 'ROLE' ? '按角色名称或编码过滤…' : '按登录名或昵称过滤…'"
    :save-label="grantSubjectType === 'ROLE' ? '保存角色授权' : '保存人员名单'"
    @save="saveGrants"
  />

  <BaseDrawer
    v-model:open="adminOpen"
    title="应用管理员"
    :subtitle="adminTarget ? `${adminTarget.appName} · ${adminTarget.appCode}` : ''"
    width="560px"
  >
    <template v-if="adminTarget">
      <div class="alert is-info">
        <span>应用管理员可维护本应用的角色映射并查看管理员名单;名单本身仅全局管理员可改。用户组授权会随组成员变化自动生效。</span>
      </div>

      <div class="admin-form">
        <label class="field">
          <span class="field-label">主体类型</span>
          <select v-model="adminForm.subjectType" class="input" @change="onAdminSubjectTypeChange">
            <option v-for="item in ADMIN_SUBJECT_TYPES" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">选择主体</span>
          <select v-model="adminForm.subjectId" class="input" :disabled="adminOptionsLoading">
            <option value="">{{ adminOptionsLoading ? '加载中…' : '请选择用户或用户组' }}</option>
            <option v-for="item in adminOptions" :key="item.value" :value="String(item.value)">
              {{ item.label }}
            </option>
          </select>
        </label>
        <button
          class="btn btn-sm"
          type="button"
          :disabled="adminSaving || !adminForm.subjectId"
          @click="addAdmin"
        >
          <AppIcon name="plus" :size="14" />
          {{ adminSaving ? '添加中…' : '添加管理员' }}
        </button>
      </div>

      <div v-if="adminOptionsError" class="alert is-danger">{{ adminOptionsError }}</div>
      <div v-if="adminError" class="alert is-danger">{{ adminError }}</div>

      <div class="section-title">
        当前管理员
        <span class="count-chip">{{ adminItems.length }}</span>
      </div>
      <div v-if="adminLoading" class="drawer-empty">加载中…</div>
      <div v-else-if="!adminItems.length" class="drawer-empty">
        尚未指定应用管理员,当前仅全局管理员可管理该应用。
      </div>
      <ul v-else class="subject-list">
        <li v-for="item in adminItems" :key="item.id" class="subject-row">
          <span class="subject-icon">
            <AppIcon :name="item.subjectType === 'GROUP' ? 'users' : 'user'" :size="16" />
          </span>
          <div class="subject-copy">
            <div class="subject-name">{{ item.subjectName || `#${item.subjectId}` }}</div>
            <div class="subject-meta">
              <span class="cell-mono">{{ item.subjectCode || '—' }}</span>
              <span>{{ item.subjectType === 'GROUP' ? '用户组' : '用户' }}</span>
            </div>
          </div>
          <button
            v-if="can('app-service:assign')"
            class="link-btn is-danger"
            type="button"
            @click="removeAdmin(item)"
          >
            移除
          </button>
        </li>
      </ul>
    </template>
  </BaseDrawer>

  <BaseDrawer
    v-model:open="mappingOpen"
    title="角色映射"
    :subtitle="mappingTarget ? `${mappingTarget.appName} · ${mappingTarget.appCode}` : ''"
    width="600px"
  >
    <template v-if="mappingTarget">
      <div v-if="mappingTarget.authorizationMode === 'MAPPED'" class="alert is-info">
        <span>令牌在 app_roles 声明中输出映射结果;直接授予用户的「本应用角色」按角色码原样并入同一声明,无需再建映射。变更在下次认证时生效,需要立即生效请强制下线相关账号。</span>
      </div>
      <div v-else class="alert is-warn">
        <span>
          当前授权模式为「{{
            AUTH_MODE_LABEL[mappingTarget.authorizationMode || 'IAM_MANAGED'] ||
            mappingTarget.authorizationMode ||
            'IAM_MANAGED'
          }}」,映射可先配置,但仅在切换为「角色映射」后参与令牌计算。
        </span>
      </div>

      <div class="mapping-form">
        <label class="field">
          <span class="field-label">来源类型</span>
          <select v-model="mappingForm.sourceType" class="input" @change="onMappingSourceTypeChange">
            <option v-for="item in MAPPING_SOURCE_TYPES" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </label>
        <label class="field">
          <span class="field-label">来源主体</span>
          <select v-model="mappingForm.sourceId" class="input" :disabled="mappingOptionsLoading">
            <option value="">{{ mappingOptionsLoading ? '加载中…' : '请选择映射来源' }}</option>
            <option v-for="item in mappingOptions" :key="item.value" :value="String(item.value)">
              {{ item.label }}
            </option>
          </select>
        </label>
        <label class="field is-wide">
          <span class="field-label">目标角色码</span>
          <input
            v-model="mappingForm.targetRoleCode"
            class="input is-mono"
            type="text"
            maxlength="150"
            placeholder="应用侧自有角色码,如 REPORT_VIEWER"
          />
          <span class="field-help">仅支持字母、数字、下划线、点、冒号、中划线</span>
        </label>
        <button class="btn btn-sm" type="button" :disabled="mappingSaving" @click="addMapping">
          <AppIcon name="plus" :size="14" />
          {{ mappingSaving ? '添加中…' : '添加映射' }}
        </button>
      </div>

      <div v-if="mappingOptionsError" class="alert is-danger">{{ mappingOptionsError }}</div>
      <div v-if="mappingError" class="alert is-danger">{{ mappingError }}</div>

      <div class="section-title">
        映射规则
        <span class="count-chip">{{ mappingItems.length }}</span>
      </div>
      <div v-if="mappingLoading" class="drawer-empty">加载中…</div>
      <div v-else-if="!mappingItems.length" class="drawer-empty">
        尚未配置映射。应用侧角色码保持不变,IAM 用户组或域角色经此转换后写入 app_roles;直接授予用户的应用角色无需配置映射。
      </div>
      <ul v-else class="subject-list">
        <li v-for="item in mappingItems" :key="item.id" class="subject-row">
          <span class="subject-icon">
            <AppIcon name="mapping" :size="16" />
          </span>
          <div class="subject-copy">
            <div class="subject-name">
              <span>{{ item.sourceName || `#${item.sourceId}` }}</span>
              <AppIcon name="arrowRight" :size="12" />
              <span class="target-code">{{ item.targetRoleCode }}</span>
              <span v-if="item.enabled === false" class="badge is-danger">已停用</span>
            </div>
            <div class="subject-meta">
              <span>{{ item.sourceType === 'GROUP' ? '用户组' : '域角色' }}</span>
              <span class="cell-mono">{{ item.sourceCode || '—' }}</span>
            </div>
          </div>
          <button
            v-if="can('app-service:update')"
            class="link-btn is-danger"
            type="button"
            @click="removeMapping(item)"
          >
            删除
          </button>
        </li>
      </ul>

      <div class="alert is-info" style="margin-top: 14px">
        <span>同一用户命中多条规则时,app_roles 输出全部目标角色码并自动去重。</span>
      </div>
    </template>
  </BaseDrawer>

  <AppBackfillDrawer v-model:open="backfillOpen" :app="backfillTarget" />
</template>

<style scoped>
.app-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 14px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.08));
}

.app-mark {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  overflow: hidden;
  border: 1px solid rgba(168, 115, 10, 0.22);
  border-radius: 13px;
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.app-mark img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.app-head-copy {
  min-width: 0;
}

.app-head-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}

.app-head-code {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--muted);
}

.app-head > .badge {
  margin-left: auto;
}

.app-links {
  margin-bottom: 14px;
}

.app-link {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-2);
  color: var(--ink-2);
  font-size: 12px;
  text-decoration: none;
}

.app-link:hover {
  border-color: var(--line-strong);
  color: var(--gold-deep);
}

.app-link-href {
  margin-left: auto;
  max-width: 58%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--muted);
}

.app-link.is-muted {
  color: var(--muted);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 18px 0 10px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-2);
}

.count-chip {
  min-width: 20px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-size: 10.5px;
  font-weight: 700;
  text-align: center;
}

.admin-form,
.mapping-form {
  display: grid;
  gap: 12px;
  padding: 14px;
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface-2);
}

.admin-form {
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.4fr);
  align-items: end;
}

.admin-form .btn {
  grid-column: 1 / -1;
  justify-self: start;
}

.mapping-form {
  grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.4fr);
}

.mapping-form .field.is-wide,
.mapping-form .btn {
  grid-column: 1 / -1;
}

.mapping-form .btn {
  justify-self: start;
}

.drawer-empty {
  padding: 22px 16px;
  border: 1px dashed var(--line-strong);
  border-radius: 11px;
  background: var(--surface-2);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.subject-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.subject-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface);
}

.subject-icon {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.subject-copy {
  flex: 1;
  min-width: 0;
}

.subject-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 650;
  color: var(--ink);
}

.subject-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--muted);
}

.target-code {
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-family: var(--font-mono);
  font-size: 11px;
}
</style>
