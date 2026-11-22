<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { useRouter } from 'vue-router';
import ResourcePage from '@/components/ResourcePage.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import BaseModal from '@/components/BaseModal.vue';
import DetailList from '@/components/DetailList.vue';
import AppIcon from '@/components/AppIcon.vue';
import { api, errorMessage } from '@/lib/api';
import { LOGIN_METHOD_OPTIONS, buildClientAdmissionPayload } from '@/lib/clientAdmission';
import { confirmDialog } from '@/lib/confirm';
import { copyText, fmtTime, safeHttpUrl } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { ResourceConfig } from '@/lib/types';
import { badge, dotBadge, muted } from '@/lib/ui';

const router = useRouter();

const CLIENT_TYPE: Record<string, string> = {
  CONFIDENTIAL: '机密客户端',
  PUBLIC: '公开客户端',
};

const SUBJECT_MODE: Record<string, string> = {
  LOGIN_NAME: '登录名(兼容存量)',
  USER_ID: '用户 ID(不可变)',
};

function subjectModeLabel(row: any) {
  if (!row.subjectMode) return '继承身份域';
  return SUBJECT_MODE[row.subjectMode] || row.subjectMode;
}

function securityBadges(row: any) {
  return h('span', { class: 'tag-row' }, [
    row.requirePkce === false ? badge('未强制 PKCE') : badge('PKCE', 'is-success'),
    row.requireConsent ? badge('征求同意', 'is-gold') : null,
    row.backchannelLogoutUri ? badge('单点登出', 'is-success') : badge('无登出通知', 'is-warn'),
    row.reuseRefreshTokens === false ? badge('令牌轮换', 'is-info') : null,
  ]);
}

/* ---------------- 详情 ---------------- */

const detailOpen = ref(false);
const detailRow = ref<any>(null);

function openDetail(row: any) {
  detailRow.value = row;
  detailOpen.value = true;
}

const redirectLinks = computed(() =>
  [...(detailRow.value?.redirectUris || [])].map((uri: string) => ({ uri, href: safeHttpUrl(uri) })),
);

const logoutLinks = computed(() =>
  [...(detailRow.value?.postLogoutRedirectUris || [])].map((uri: string) => ({ uri, href: safeHttpUrl(uri) })),
);

/* ---------------- 作用域分配 ---------------- */

const scopeOpen = ref(false);
const scopeTarget = ref<any>(null);
const scopeLoading = ref(false);
const scopeSaving = ref(false);
const scopeError = ref('');
const scopeItems = ref<any[]>([]);
const scopeSelected = ref<Array<string | number>>([]);

async function openScopes(row: any) {
  scopeTarget.value = row;
  scopeOpen.value = true;
  scopeLoading.value = true;
  scopeError.value = '';
  scopeItems.value = [];
  scopeSelected.value = [];
  try {
    const [scopes, assigned] = await Promise.all([
      api<any[]>('/api/v1/admin/client-scopes', { silent: true }),
      api<number[]>(`/api/v1/admin/client-scopes/clients/${row.id}`, { silent: true }),
    ]);
    scopeItems.value = (scopes || []).map((scope: any) => ({
      id: scope.id,
      label: scope.scopeName,
      hint: scope.scopeKey,
      tags: scope.isDefault ? [{ text: '默认授予', tone: 'is-gold' as const }] : [],
    }));
    scopeSelected.value = (assigned || []).map(Number);
  } catch (error) {
    scopeError.value = errorMessage(error);
  } finally {
    scopeLoading.value = false;
  }
}

async function saveScopes(ids: Array<string | number>) {
  if (!scopeTarget.value) return;
  scopeSaving.value = true;
  scopeError.value = '';
  try {
    await api(`/api/v1/admin/client-scopes/clients/${scopeTarget.value.id}`, {
      method: 'PUT',
      body: ids,
      silent: true,
    });
    toast('客户端作用域已更新', 'success');
    scopeOpen.value = false;
  } catch (error) {
    scopeError.value = errorMessage(error);
  } finally {
    scopeSaving.value = false;
  }
}

/* ---------------- 密钥生命周期 ---------------- */

const secretOpen = ref(false);
const secretValue = ref('');
const secretClientName = ref('');
const secretCode = ref<HTMLElement | null>(null);

function showSecret(name: string, value: string) {
  secretClientName.value = name;
  secretValue.value = value;
  secretOpen.value = true;
}

async function rotateSecret(row: any) {
  const ok = await confirmDialog({
    title: '轮换客户端密钥',
    message: `确认轮换「${row.clientName}」的密钥吗?旧密钥立即失效,使用旧密钥的调用方将无法换取令牌。`,
    confirmText: '轮换密钥',
  });
  if (!ok) return;
  try {
    const updated = await api<any>(`/api/v1/admin/clients/${row.id}/secret`, { method: 'POST' });
    if (updated?.clientSecret) {
      showSecret(updated.clientName || row.clientName, updated.clientSecret);
    } else {
      toast('密钥已轮换,但服务端未返回明文', 'warn');
    }
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

/* ---------------- 类型变更 ---------------- */

const typeOpen = ref(false);
const typeTarget = ref<any>(null);
const typeCtx = ref<any>(null);
const typeSaving = ref(false);
const typeError = ref('');
const typeChoice = ref('CONFIDENTIAL');

function openTypeChange(row: any, ctx?: any) {
  typeTarget.value = row;
  typeCtx.value = ctx || null;
  typeChoice.value = row.clientType === 'PUBLIC' ? 'CONFIDENTIAL' : 'PUBLIC';
  typeError.value = '';
  typeOpen.value = true;
}

async function submitTypeChange() {
  if (!typeTarget.value) return;
  typeSaving.value = true;
  typeError.value = '';
  try {
    const updated = await api<any>(`/api/v1/admin/clients/${typeTarget.value.id}/type`, {
      method: 'POST',
      body: { clientType: typeChoice.value },
      silent: true,
    });
    toast('客户端类型已变更', 'success');
    typeOpen.value = false;
    if (updated?.clientSecret) {
      showSecret(updated.clientName || typeTarget.value.clientName, updated.clientSecret);
    }
    await typeCtx.value?.reload();
  } catch (error) {
    typeError.value = errorMessage(error);
  } finally {
    typeSaving.value = false;
  }
}

async function copySecret() {
  if (!secretValue.value) return;
  if (await copyText(secretValue.value, '客户端密钥已复制')) return;
  const code = secretCode.value;
  if (!code) return;
  const range = document.createRange();
  range.selectNodeContents(code);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
}

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '客户端',
  desc: '客户端是接入方在身份平台的登记:回调地址决定授权码去向,作用域决定可申请的权限范围。',
  endpoint: '/api/v1/admin/clients',
  permCreate: 'client:create',
  createLabel: '登记客户端',
  deleteName: '客户端',
  rowName: (row) => row.clientName,
  cardTitle: '接入登记',
  filters: [
    { name: 'clientKey', label: '客户端标识', placeholder: '支持模糊匹配' },
    { name: 'clientName', label: '客户端名称', placeholder: '支持模糊匹配' },
    {
      name: 'clientType',
      label: '类型',
      type: 'select',
      options: [
        { value: '', label: '全部类型' },
        { value: 'CONFIDENTIAL', label: '机密客户端' },
        { value: 'PUBLIC', label: '公开客户端' },
      ],
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
    { label: '客户端', render: (row) => h('div', { class: 'cell-stack' }, [h('div', { class: 'cell-strong' }, row.clientName), h('div', { class: 'cell-mono' }, row.clientKey)]) },
    {
      label: '类型',
      render: (row) =>
        row.clientType === 'PUBLIC' ? badge('公开客户端', 'is-warn') : badge('机密客户端', 'is-info'),
    },
    {
      label: '回调地址',
      render: (row) => {
        const uris = [...(row.redirectUris || [])];
        return h('div', { class: 'cell-stack' }, [
          h('div', { class: 'cell-mono truncate', style: { maxWidth: '220px' }, title: uris[0] || '' }, uris[0] || '—'),
          uris.length > 1 ? h('div', { class: 'cell-sub' }, `共 ${uris.length} 个`) : null,
        ]);
      },
    },
    {
      label: '作用域',
      render: (row) => {
        const scopes = [...(row.scopes || [])];
        if (!scopes.length) return muted('默认');
        return h('span', { class: 'tag-row' }, [
          ...scopes.slice(0, 3).map((scope) => h('span', { class: 'tag', key: scope }, scope)),
          scopes.length > 3 ? h('span', { class: 'tag-more' }, `+${scopes.length - 3}`) : null,
        ]);
      },
    },
    { label: '安全', render: (row) => securityBadges(row) },
    {
      label: '状态',
      render: (row) =>
        row.enabled === false ? dotBadge('已停用', 'is-danger') : dotBadge('启用中', 'is-success'),
    },
  ],
  rowActions: (row, ctx) => [
    { label: '详情', onClick: () => openDetail(row) },
    { label: '编辑', perm: 'client:update', onClick: () => ctx?.openEditor(row) },
    { label: '作用域', perm: 'client-scope:update', onClick: () => openScopes(row) },
    {
      label: '协议映射',
      perm: 'protocol-mapper:view',
      onClick: () => router.push({ path: '/protocol-mappers', query: { clientId: String(row.id) } }),
    },
    {
      label: '轮换密钥',
      perm: 'client:update',
      hidden: (item) => item.clientType === 'PUBLIC',
      onClick: () => rotateSecret(row),
    },
    { label: '变更类型', perm: 'client:update', onClick: () => openTypeChange(row, ctx) },
    { label: '删除', perm: 'client:delete', danger: true, onClick: () => ctx?.remove(row) },
  ],
  rowClick: (row) => openDetail(row),
  form: {
    title: '客户端',
    subtitle: '客户端标识创建后不可修改;密钥仅在创建与轮换时展示一次',
    hint: '回调地址必须与调用方实际地址完全一致(含协议、端口与路径),否则授权码会被拒绝。',
    fields: [
      { name: 'clientKey', label: '客户端标识', required: true, mono: true, placeholder: '如 beenest-portal' },
      { name: 'clientName', label: '客户端名称', required: true, placeholder: '如 员工门户' },
      {
        name: 'clientType',
        label: '客户端类型',
        type: 'select',
        options: [
          { value: 'CONFIDENTIAL', label: '机密客户端(有密钥,服务端调用)' },
          { value: 'PUBLIC', label: '公开客户端(无密钥,SPA/移动端)' },
        ],
      },
      {
        name: 'redirectUris',
        label: '回调地址',
        type: 'tags',
        required: true,
        full: true,
        help: '每行一个,仅允许 https(本地调试可用 http://localhost)',
        placeholder: 'https://portal.example.com/callback',
      },
      {
        name: 'postLogoutRedirectUris',
        label: '登出后回跳地址',
        type: 'tags',
        full: true,
        help: '每行一个;留空则登出后只回 IAM 登录页',
        placeholder: 'https://portal.example.com/signed-out',
      },
      {
        name: 'backchannelLogoutUri',
        label: '后通道登出地址',
        full: true,
        placeholder: 'https://app.example.com/backchannel-logout',
        help: '登记后,IAM 可向该地址推送 OIDC 登出令牌;留空则退出单点登出广播',
      },
      { name: 'scopes', label: '授权作用域', type: 'tags', full: true, help: '每行一个,如 openid / profile / email(平台不提供 offline_access 离线令牌语义)' },
      { name: 'accessTokenTtl', label: 'access token 有效期(秒)', type: 'number', placeholder: '缺省继承身份域' },
      { name: 'refreshTokenTtl', label: 'refresh token 有效期(秒)', type: 'number', placeholder: '缺省继承身份域' },
      { name: 'requirePkce', label: '强制 PKCE', type: 'switch', onText: '强制' },
      { name: 'requireConsent', label: '征求用户同意', type: 'switch', onText: '开启' },
      {
        name: 'reuseRefreshTokens',
        label: '复用刷新令牌',
        type: 'switch',
        onText: '复用',
        help: '关闭表示每次刷新轮换,旧令牌立即失效;开启对多标签页并发刷新更宽容',
      },
      {
        name: 'allowedLoginTypes',
        label: '允许的登录方式',
        type: 'multiselect',
        full: true,
        options: LOGIN_METHOD_OPTIONS,
        help: '留空表示不限制(沿用身份域可用方式);选定后,会话认证方式不在其中时转入补验流程',
      },
      { name: 'requireMfa', label: '强制二次验证', type: 'switch', onText: '强制' },
      {
        name: 'subjectMode',
        label: '主体标识 (sub)',
        type: 'select',
        full: true,
        placeholder: '继承身份域(默认)',
        options: [
          { value: 'LOGIN_NAME', label: '登录名(兼容存量)' },
          { value: 'USER_ID', label: '用户 ID(不可变)' },
        ],
        help: 'sub 是应用侧识别用户唯一不变的锚点:切换后存量应用需改用 preferred_username 或重新映射',
      },
    ],
    editFields: [
      { name: 'clientName', label: '客户端名称', required: true },
      { name: 'redirectUris', label: '回调地址', type: 'tags', required: true, full: true },
      { name: 'postLogoutRedirectUris', label: '登出后回跳地址', type: 'tags', full: true, help: '清空后该应用登出只能回 IAM 登录页' },
      { name: 'backchannelLogoutUri', label: '后通道登出地址', full: true, help: '清空即退出单点登出广播' },
      { name: 'scopes', label: '授权作用域', type: 'tags', full: true },
      { name: 'accessTokenTtl', label: 'access token 有效期(秒)', type: 'number' },
      { name: 'refreshTokenTtl', label: 'refresh token 有效期(秒)', type: 'number' },
      { name: 'requirePkce', label: '强制 PKCE', type: 'switch', onText: '强制' },
      { name: 'requireConsent', label: '征求用户同意', type: 'switch', onText: '开启' },
      { name: 'reuseRefreshTokens', label: '复用刷新令牌', type: 'switch', onText: '复用' },
      {
        name: 'allowedLoginTypes',
        label: '允许的登录方式',
        type: 'multiselect',
        full: true,
        options: LOGIN_METHOD_OPTIONS,
        help: '留空表示不限制(沿用身份域可用方式);选定后,会话认证方式不在其中时转入补验流程',
      },
      { name: 'requireMfa', label: '强制二次验证', type: 'switch', onText: '强制' },
      {
        name: 'subjectMode',
        label: '主体标识 (sub)',
        type: 'select',
        full: true,
        options: [
          { value: '', label: '继承身份域' },
          { value: 'LOGIN_NAME', label: '登录名(兼容存量)' },
          { value: 'USER_ID', label: '用户 ID(不可变)' },
        ],
        help: '选「继承身份域」即清空客户端覆盖,回到所属域的策略',
      },
      { name: 'enabled', label: '启用客户端', type: 'switch', onText: '启用中' },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    defaults: {
      clientType: 'CONFIDENTIAL',
      requirePkce: true,
      requireConsent: false,
      reuseRefreshTokens: true,
      redirectUris: [],
      scopes: [],
      postLogoutRedirectUris: [],
      allowedLoginTypes: [],
      requireMfa: false,
    },
    fromRow: (row) => ({
      clientName: row.clientName,
      redirectUris: [...(row.redirectUris || [])],
      postLogoutRedirectUris: [...(row.postLogoutRedirectUris || [])],
      backchannelLogoutUri: row.backchannelLogoutUri || '',
      scopes: [...(row.scopes || [])],
      accessTokenTtl: row.accessTokenTtl,
      refreshTokenTtl: row.refreshTokenTtl,
      requirePkce: row.requirePkce !== false,
      requireConsent: Boolean(row.requireConsent),
      reuseRefreshTokens: row.reuseRefreshTokens !== false,
      allowedLoginTypes: [...(row.allowedLoginTypes || [])],
      requireMfa: Boolean(row.requireMfa),
      subjectMode: row.subjectMode || '',
      enabled: row.enabled !== false,
      description: row.description,
    }),
    // SchemaForm.read() 已经按字段产出数组,这里再归一一次是为了挡住
    // 选项被删改后残留的旧值——发一个后端不认识的码过去,整单都会被拒
    toCreate: (values) => ({ ...values, ...buildClientAdmissionPayload(values) }),
    toUpdate: (values) => ({ ...values, ...buildClientAdmissionPayload(values) }),
    onCreated: (created) => {
      if (created?.clientSecret) {
        showSecret(created.clientName || created.clientKey || '新客户端', created.clientSecret);
      }
    },
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseDrawer
    v-model:open="detailOpen"
    :title="detailRow?.clientName || '客户端详情'"
    :subtitle="detailRow?.clientKey"
    width="640px"
  >
    <template v-if="detailRow">
      <div class="client-head">
        <span class="client-mark"><AppIcon name="apps" :size="20" /></span>
        <div class="client-head-copy">
          <div class="client-head-name">{{ detailRow.clientName }}</div>
          <div class="cell-mono">{{ detailRow.clientKey }}</div>
        </div>
        <span class="dot-badge" :class="detailRow.enabled === false ? 'is-danger' : 'is-success'">
          <i class="dot" />{{ detailRow.enabled === false ? '已停用' : '启用中' }}
        </span>
      </div>

      <DetailList
        :rows="[
          { label: '客户端主键', value: detailRow.id, mono: true },
          { label: '客户端类型', value: CLIENT_TYPE[detailRow.clientType] || detailRow.clientType },
          { label: 'access token', value: detailRow.accessTokenTtl ? `${detailRow.accessTokenTtl} 秒` : '继承身份域' },
          { label: 'refresh token', value: detailRow.refreshTokenTtl ? `${detailRow.refreshTokenTtl} 秒` : '继承身份域' },
          { label: '刷新令牌', value: detailRow.reuseRefreshTokens === false ? '每次刷新轮换' : '复用同一枚' },
          { label: '主体标识 (sub)', value: subjectModeLabel(detailRow) },
          { label: '后通道登出', value: detailRow.backchannelLogoutUri || '未登记' },
          { label: '创建时间', value: fmtTime(detailRow.createdTime) },
          { label: '更新时间', value: fmtTime(detailRow.updatedTime) },
        ]"
      />

      <div class="form-section-title" style="margin: 20px 0 10px">回调地址</div>
      <div v-if="redirectLinks.length" class="uri-list">
        <template v-for="link in redirectLinks" :key="link.uri">
          <a
            v-if="link.href"
            class="uri-item"
            :href="link.href"
            target="_blank"
            rel="noopener noreferrer"
          >{{ link.uri }}<span aria-hidden="true">↗</span></a>
          <span v-else class="uri-item">{{ link.uri }}</span>
        </template>
      </div>
      <p v-else class="muted">未配置回调地址</p>

      <div class="form-section-title" style="margin: 20px 0 10px">授权作用域</div>
      <div v-if="(detailRow.scopes || []).length" class="tag-row">
        <span v-for="scope in detailRow.scopes" :key="scope" class="tag">{{ scope }}</span>
      </div>
      <p v-else class="muted">使用默认作用域</p>

      <div class="form-section-title" style="margin: 20px 0 10px">登出后回跳地址</div>
      <div v-if="logoutLinks.length" class="uri-list">
        <template v-for="link in logoutLinks" :key="link.uri">
          <a
            v-if="link.href"
            class="uri-item"
            :href="link.href"
            target="_blank"
            rel="noopener noreferrer"
          >{{ link.uri }}<span aria-hidden="true">↗</span></a>
          <span v-else class="uri-item">{{ link.uri }}</span>
        </template>
      </div>
      <p v-else class="muted">未登记:登出后只能回到 IAM 登录页</p>

      <div v-if="detailRow.description" class="form-section-title" style="margin: 20px 0 10px">说明</div>
      <p v-if="detailRow.description" class="muted">{{ detailRow.description }}</p>

      <div class="alert" :class="detailRow.backchannelLogoutUri ? 'is-info' : 'is-warn'" style="margin-top: 18px">
        <AppIcon name="info" :size="15" />
        <span v-if="detailRow.backchannelLogoutUri">已登记后通道登出:IAM 会在用户登出或被他处强制下线时通知该应用。</span>
        <span v-else>未登记后通道登出:用户从 IAM 注销后,该应用可能仍保持在线状态。</span>
      </div>
    </template>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="detailOpen = false">关闭</button>
    </template>
  </BaseDrawer>

  <AssignmentModal
    v-model:open="scopeOpen"
    :title="`作用域分配 · ${scopeTarget?.clientName || ''}`"
    subtitle="客户端只能申请这里批准的作用域"
    :items="scopeItems"
    :model-value="scopeSelected"
    :loading="scopeLoading"
    :saving="scopeSaving"
    :error="scopeError"
    alert="客户端作用域决定可申请的授权边界;变更后新发起的授权立即生效。"
    search-placeholder="按作用域名称或键过滤…"
    save-label="保存作用域"
    @save="saveScopes"
  />

  <BaseModal
    v-model:open="typeOpen"
    title="变更客户端类型"
    :subtitle="typeTarget?.clientName"
    size="sm"
    :close-on-backdrop="false"
  >
    <div class="alert is-warn" style="margin-bottom: 14px">
      <AppIcon name="alert" :size="15" />
      <span>类型决定令牌端点的认证方式。只有尚未投入使用的客户端(从未产生会话、无授权同意)可以变更;已接入的客户端请新建并迁移。</span>
    </div>
    <label class="field">
      <span class="field-label">目标类型</span>
      <select v-model="typeChoice" class="input">
        <option value="CONFIDENTIAL">机密客户端(有密钥,服务端调用)</option>
        <option value="PUBLIC">公开客户端(无密钥,SPA/移动端)</option>
      </select>
    </label>
    <p class="muted" style="margin-top: 10px">
      <template v-if="typeChoice === 'CONFIDENTIAL'">升级为机密客户端会现场签发新密钥,明文只在下一步显示一次;原回调地址与作用域保持不变。</template>
      <template v-else>降级为公开客户端会清空密钥并强制 PKCE;调用方必须改用授权码 + PKCE,不再携带密钥。</template>
    </p>
    <p v-if="typeError" class="field-error" style="margin-top: 10px">{{ typeError }}</p>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="typeOpen = false">取消</button>
      <button class="btn btn-primary" type="button" :disabled="typeSaving" @click="submitTypeChange">
        {{ typeSaving ? '变更中…' : '确认变更' }}
      </button>
    </template>
  </BaseModal>

  <BaseModal v-model:open="secretOpen" title="客户端密钥" :subtitle="secretClientName" size="md" :close-on-backdrop="false">
    <div class="alert is-warn" style="margin-bottom: 14px">
      <AppIcon name="alert" :size="15" />
      <span>密钥仅在此处显示一次,关闭后无法再次读取。请立即通过安全渠道交付并妥善保管。</span>
    </div>
    <div class="secret-panel">
      <code ref="secretCode">{{ secretValue }}</code>
      <button class="btn btn-sm" type="button" @click="copySecret">
        <AppIcon name="copy" :size="14" />
        <span>复制</span>
      </button>
    </div>
    <template #footer>
      <button class="btn btn-primary" type="button" @click="secretOpen = false">我已保存</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.client-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.08));
}

.client-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  color: var(--gold-deep);
  background: var(--gold-soft);
  border: 1px solid rgba(168, 115, 10, 0.22);
}

.client-head-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.client-head-name {
  font-size: 15px;
  font-weight: 680;
  color: var(--ink);
}

.client-head .dot-badge {
  margin-left: auto;
}

.uri-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.uri-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 9px;
  border-radius: 8px;
  border: 1px solid var(--line-soft);
  background: var(--surface-2);
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-2);
  text-decoration: none;
  word-break: break-all;
}

.uri-item span {
  color: var(--gold-deep);
  flex-shrink: 0;
}

.uri-item:hover {
  border-color: var(--line-strong);
  background: var(--surface);
}

.secret-panel {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px;
  border-radius: 11px;
  border: 1px solid var(--line-strong);
  background: var(--surface-3);
}

.secret-panel code {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink);
  word-break: break-all;
}
</style>
