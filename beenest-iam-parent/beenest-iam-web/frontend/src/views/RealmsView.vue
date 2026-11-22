<script setup lang="ts">
import { h, onMounted, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import ResourcePage from '@/components/ResourcePage.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { ResourceConfig } from '@/lib/types';
import { badge, dotBadge, muted, strongSub } from '@/lib/ui';
import { can } from '@/stores/auth';

/* ---------------- 签名密钥 ---------------- */

const keyOpen = ref(false);
const keyRealm = ref<any>(null);
const keys = ref<any[]>([]);
const keysLoading = ref(false);
const keysError = ref('');
const rotateBusy = ref(false);

async function loadKeys() {
  if (!keyRealm.value) return;
  keysLoading.value = true;
  keysError.value = '';
  try {
    keys.value =
      (await api<any[]>(`/api/v1/admin/realms/${keyRealm.value.id}/keys`, { silent: true })) || [];
  } catch (error) {
    keys.value = [];
    keysError.value = errorMessage(error);
  } finally {
    keysLoading.value = false;
  }
}

function openKeys(row: any) {
  keyRealm.value = row;
  keyOpen.value = true;
  keys.value = [];
  void loadKeys();
}

async function rotateKey() {
  if (!keyRealm.value) return;
  const ok = await confirmDialog({
    title: '轮换签名密钥',
    message: `确认轮换「${keyRealm.value.realmKey}」的签名密钥吗?新签发的令牌使用新密钥,旧密钥转为退役状态继续用于验签。`,
    detail: 'JWKS 会同时发布生效密钥与退役公钥,存量令牌在自然过期前不受影响。',
    confirmText: '轮换',
  });
  if (!ok) return;
  rotateBusy.value = true;
  try {
    await api(`/api/v1/admin/realms/${keyRealm.value.id}/keys/rotate`, { method: 'POST' });
    toast('签名密钥已轮换', 'success');
    await loadKeys();
  } catch {
    /* 错误提示由 api 统一处理 */
  } finally {
    rotateBusy.value = false;
  }
}

/* ---------------- 切换身份域 ---------------- */

async function switchRealm(row: any) {
  const ok = await confirmDialog({
    title: '切换身份域',
    message: `管理接口的数据范围由登录账号所属域决定。确认退出当前会话,并以「${row.realmKey}」域重新登录吗?`,
    detail: '当前域未保存的表单内容会丢失。',
    confirmText: '重新登录',
  });
  if (!ok) return;
  try {
    await api('/api/v1/auth/logout', { method: 'POST', silent: true });
  } catch {
    /* 会话可能已失效,继续跳转 */
  }
  location.href = `/login?realm=${encodeURIComponent(row.realmKey)}`;
}

/* ---------------- 协议页主题 ---------------- */

const themeOptions = ref<{ value: string; label: string }[]>([
  { value: '', label: '跟随全局主题' },
]);

onMounted(async () => {
  try {
    const list = (await api<string[]>('/api/v1/admin/realms/theme-options', { silent: true })) || [];
    themeOptions.value = [
      { value: '', label: '跟随全局主题' },
      ...list.map((name) => ({ value: name, label: name })),
    ];
  } catch {
    /* 拉取失败时下拉只剩"跟随全局",不影响其它功能 */
  }
});

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '身份域',
  desc: '一个身份域是一套独立的租户边界:独立 issuer、独立密钥、独立用户与客户端;账号只能管理所属域的数据。',
  endpoint: '/api/v1/admin/realms',
  permCreate: 'realm:create',
  createLabel: '新建身份域',
  deleteName: '身份域',
  rowName: (row) => row.realmKey,
  filters: [
    { name: 'realmKey', label: '域标识', placeholder: '支持模糊匹配' },
    { name: 'realmName', label: '域名称', placeholder: '支持模糊匹配' },
  ],
  columns: [
    {
      label: '身份域',
      render: (row) =>
        h('div', { class: 'cell-stack' }, [
          h('div', { class: 'realm-name-row' }, [
            h('span', { class: 'cell-strong' }, row.realmName),
            row.defaultRealm ? badge('内置域', 'is-gold') : null,
          ]),
          h('div', { class: 'cell-mono' }, row.realmKey),
        ]),
    },
    {
      label: '令牌有效期',
      render: (row) =>
        h('div', { class: 'cell-stack' }, [
          h('div', { class: 'muted' }, `access ${row.accessTokenTtl ?? '—'}s`),
          h('div', { class: 'cell-sub' }, `refresh ${row.refreshTokenTtl ?? '—'}s`),
        ]),
    },
    {
      label: '会话策略',
      render: (row) =>
        h('div', { class: 'cell-stack' }, [
          h('div', { class: 'muted' }, `空闲 ${row.ssoSessionIdle ?? '—'}s`),
          h('div', { class: 'cell-sub' }, `最大 ${row.ssoSessionMax ?? '—'}s`),
        ]),
    },
    {
      label: '主体标识',
      render: (row) =>
        row.subjectMode === 'USER_ID'
          ? h('div', { class: 'cell-stack' }, [
              badge('用户 ID', 'is-info'),
              h('div', { class: 'cell-sub' }, '登录名可变,ID 不变'),
            ])
          : h('div', { class: 'cell-stack' }, [
              badge('登录名'),
              h('div', { class: 'cell-sub' }, '兼容存量接入'),
            ]),
    },
    {
      label: '主题',
      render: (row) =>
        row.theme
          ? h('div', { class: 'cell-stack' }, [
              badge(row.theme, 'is-gold'),
              h('div', { class: 'cell-sub' }, '本域专属登录页'),
            ])
          : h('div', { class: 'cell-stack' }, [
              badge('全局默认'),
              h('div', { class: 'cell-sub' }, '跟随系统主题'),
            ]),
    },
    {
      label: '状态',
      render: (row) =>
        row.enabled ? dotBadge('启用中', 'is-success') : dotBadge('已停用', 'is-danger'),
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'realm:update', onClick: () => ctx.openEditor(row) },
    { label: '密钥', perm: 'realm-key:view', onClick: () => openKeys(row) },
    { label: '以此域登录', onClick: () => switchRealm(row) },
    {
      label: '删除',
      perm: 'realm:delete',
      danger: true,
      hidden: (item) => Boolean(item.defaultRealm),
      onClick: () => ctx.remove(row),
    },
  ],
  form: {
    title: '身份域',
    subtitle: '域标识是 issuer 的路径段,创建后不可修改',
    hint: '新建域将自动生成独立签名密钥;系统内置的 default 域不可删除。',
    fields: [
      {
        name: 'realmKey',
        label: '域标识',
        required: true,
        mono: true,
        placeholder: '2-64 位,字母/数字/下划线/中划线',
      },
      { name: 'realmName', label: '域名称', required: true, placeholder: '如 研发中心' },
      { name: 'accessTokenTtl', label: 'access token 有效期(秒)', type: 'number', placeholder: '如 1800', min: 1 },
      { name: 'refreshTokenTtl', label: 'refresh token 有效期(秒)', type: 'number', placeholder: '如 604800', min: 1 },
      { name: 'ssoSessionIdle', label: '会话空闲超时(秒)', type: 'number', placeholder: '如 1800', min: 1 },
      { name: 'ssoSessionMax', label: '会话最大时长(秒)', type: 'number', placeholder: '如 28800', min: 1 },
      { name: 'enabled', label: '启用身份域', type: 'switch', onText: '启用中' },
      {
        name: 'subjectMode',
        label: '主体标识 (sub)',
        type: 'select',
        full: true,
        options: [
          { value: 'LOGIN_NAME', label: '登录名(兼容存量)' },
          { value: 'USER_ID', label: '用户 ID(不可变)' },
        ],
        help: '本域默认的 sub 取值;单个客户端可在「客户端」页覆盖',
      },
      {
        name: 'theme',
        label: '登录页主题',
        type: 'select',
        full: true,
        options: themeOptions,
        help: '该域的登录页、错误页与二步验证页使用的主题;留空跟随全局',
      },
    ],
    editFields: [
      { name: 'realmName', label: '域名称', required: true, full: true },
      { name: 'accessTokenTtl', label: 'access token 有效期(秒)', type: 'number', min: 1 },
      { name: 'refreshTokenTtl', label: 'refresh token 有效期(秒)', type: 'number', min: 1 },
      { name: 'ssoSessionIdle', label: '会话空闲超时(秒)', type: 'number', min: 1 },
      { name: 'ssoSessionMax', label: '会话最大时长(秒)', type: 'number', min: 1 },
      { name: 'enabled', label: '启用身份域', type: 'switch', onText: '启用中' },
      {
        name: 'subjectMode',
        label: '主体标识 (sub)',
        type: 'select',
        full: true,
        options: [
          { value: 'LOGIN_NAME', label: '登录名(兼容存量)' },
          { value: 'USER_ID', label: '用户 ID(不可变)' },
        ],
        help: '切换后新签发的令牌立即生效;已接入应用需确认按 sub 还是 preferred_username 匹配',
      },
      {
        name: 'theme',
        label: '登录页主题',
        type: 'select',
        full: true,
        options: themeOptions,
        help: '留空跟随全局主题;选择后该域用户立即看到新登录页',
      },
    ],
    defaults: { enabled: true, subjectMode: 'LOGIN_NAME', theme: '' },
    fromRow: (row) => ({
      realmName: row.realmName,
      accessTokenTtl: row.accessTokenTtl,
      refreshTokenTtl: row.refreshTokenTtl,
      ssoSessionIdle: row.ssoSessionIdle,
      ssoSessionMax: row.ssoSessionMax,
      enabled: row.enabled !== false,
      subjectMode: row.subjectMode || 'LOGIN_NAME',
      theme: row.theme || '',
    }),
    validate: (values) => {
      if (values.realmKey && !/^[a-zA-Z0-9_-]{2,64}$/.test(String(values.realmKey))) {
        return '域标识只能包含字母、数字、下划线、中划线,长度 2-64';
      }
    },
    toUpdate: (values) => {
      const body: Record<string, any> = { ...values };
      if (!body.subjectMode) delete body.subjectMode;
      return body;
    },
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseDrawer
    v-model:open="keyOpen"
    :title="`签名密钥 · ${keyRealm?.realmKey || ''}`"
    subtitle="JWKS 同时发布生效密钥与退役公钥,轮换不影响存量令牌验签"
    width="640px"
  >
    <div v-if="keysLoading" class="key-loading">
      <span class="btn-spinner" />
      <span>正在读取密钥生命周期…</span>
    </div>

    <div v-else-if="keysError" class="alert is-danger">
      <span>{{ keysError }}</span>
      <button class="link-btn" type="button" @click="loadKeys">重试</button>
    </div>

    <template v-else>
      <div v-if="!keys.length" class="key-empty">
        <AppIcon name="key" :size="24" />
        <span>该域尚无密钥记录</span>
      </div>
      <div v-else class="key-list">
        <article v-for="key in keys" :key="key.id" class="key-item" :class="{ 'is-active': key.active }">
          <span class="key-mark">
            <AppIcon name="key" :size="17" />
          </span>
          <div class="key-copy">
            <div class="key-id">
              <span class="cell-mono">#{{ key.id }}</span>
              <span class="badge is-outline">{{ key.keyType || 'RSA' }}</span>
            </div>
            <div class="key-times">
              <span>启用 {{ fmtTime(key.activatedTime) }}</span>
              <span>退役 {{ fmtTime(key.retiredTime) }}</span>
            </div>
          </div>
          <span v-if="key.active" class="dot-badge is-success"><i class="dot" />生效中</span>
          <span v-else class="badge">已退役</span>
        </article>
      </div>
      <div class="alert is-info" style="margin-top: 14px">
        <span>私钥永不出域:管理接口只暴露密钥生命周期,不返回任何密钥材料;公钥经 JWKS 端点发布。</span>
      </div>
    </template>

    <template #footer>
      <button class="btn btn-ghost" type="button" @click="keyOpen = false">关闭</button>
      <button
        v-if="can('realm-key:rotate')"
        class="btn btn-primary"
        type="button"
        :disabled="rotateBusy || keysLoading"
        @click="rotateKey"
      >
        <span v-if="rotateBusy" class="btn-spinner" />
        <AppIcon v-else name="rotate" :size="15" />
        <span>轮换密钥</span>
      </button>
    </template>
  </BaseDrawer>
</template>

<style scoped>
.realm-name-row {
  display: flex;
  align-items: center;
  gap: 7px;
}

.key-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--muted);
  font-size: 13px;
}

.key-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 42px 0;
  color: var(--muted);
  font-size: 12.5px;
}

.key-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.key-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface-2);
}

.key-item.is-active {
  border-color: rgba(168, 115, 10, 0.34);
  background: linear-gradient(135deg, rgba(242, 181, 46, 0.1), var(--surface));
}

.key-mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--gold-deep);
}

.key-item.is-active .key-mark {
  border-color: rgba(168, 115, 10, 0.28);
  background: var(--gold-soft);
}

.key-copy {
  min-width: 0;
  flex: 1;
}

.key-id {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  font-weight: 620;
  color: var(--ink);
}

.key-times {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 4px;
  color: var(--muted);
  font-size: 10.5px;
}
</style>
