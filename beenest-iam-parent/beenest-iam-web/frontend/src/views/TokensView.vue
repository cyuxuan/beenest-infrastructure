<script setup lang="ts">
import { computed, h, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import DataTable from '@/components/DataTable.vue';
import DetailList from '@/components/DetailList.vue';
import PageHeader from '@/components/PageHeader.vue';
import PagerBar from '@/components/PagerBar.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime, shortId } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { PageResult } from '@/lib/types';
import { badge, mono, muted, tags } from '@/lib/ui';
import { can } from '@/stores/auth';

const usernameDraft = ref('');
const clientDraft = ref('');
const username = ref('');
const clientId = ref('');
const rows = ref<any[]>([]);
const loading = ref(false);
const error = ref('');
const queried = ref(false);
const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const pages = ref(0);

let requestSeq = 0;

const canRevoke = computed(() => can('token:revoke'));
const canRevokeAll = computed(
  () => canRevoke.value && can('user:view') && usernameDraft.value.trim().length > 0,
);
const scopeLabel = computed(() =>
  [username.value && `账号 ${username.value}`, clientId.value && `客户端 ${clientId.value}`]
    .filter(Boolean)
    .join(' · '),
);

function isExpired(row: any): boolean {
  if (!row.expiresAt) return false;
  const time = new Date(String(row.expiresAt)).getTime();
  return Number.isFinite(time) && time < Date.now();
}

async function load() {
  const name = username.value;
  if (!name) {
    toast('请先输入登录名', 'warn');
    return;
  }
  const seq = ++requestSeq;
  loading.value = true;
  error.value = '';
  try {
    const page = await api<PageResult<any>>('/api/v1/admin/tokens', {
      query: {
        username: name,
        clientId: clientId.value,
        pageNum: pageNum.value,
        pageSize: pageSize.value,
      },
      silent: true,
    });
    if (seq !== requestSeq) return;
    rows.value = page?.records || [];
    total.value = page?.total || 0;
    pages.value = page?.pages || 0;
    if (page?.pageNum) pageNum.value = page.pageNum;
    queried.value = true;
  } catch (err) {
    if (seq !== requestSeq) return;
    rows.value = [];
    total.value = 0;
    pages.value = 0;
    error.value = errorMessage(err);
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function doQuery() {
  const name = usernameDraft.value.trim();
  if (!name) {
    toast('请先输入登录名', 'warn');
    return;
  }
  username.value = name;
  clientId.value = clientDraft.value.trim();
  pageNum.value = 1;
  void load();
}

function changePage(next: number) {
  pageNum.value = next;
  void load();
}

function changePageSize(size: number) {
  pageSize.value = size;
  pageNum.value = 1;
  void load();
}

function refresh() {
  if (queried.value) void load();
}

async function revoke(row: any) {
  const ok = await confirmDialog({
    title: '吊销令牌',
    message: `确认吊销客户端「${row.clientId}」的这条授权吗?该授权下的 access / refresh token 将立即失效。`,
    detail: '吊销会写入令牌黑名单并删除授权记录,业务侧下一次校验即刻失败。',
    confirmText: '吊销',
  });
  if (!ok) return;
  try {
    await api(`/api/v1/admin/tokens/${encodeURIComponent(row.authorizationId)}`, { method: 'DELETE' });
    toast('令牌已吊销', 'success');
    if (rows.value.length === 1 && pageNum.value > 1) pageNum.value -= 1;
    await load();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

async function revokeAll() {
  const name = usernameDraft.value.trim();
  if (!name) {
    toast('请先输入登录名', 'warn');
    return;
  }
  const ok = await confirmDialog({
    title: '吊销全部令牌',
    message: `确认吊销账号「${name}」的全部令牌吗?该账号在所有客户端上的授权都会被清除。`,
    detail: '该操作不可撤销,用户需要重新登录并重新授权。',
    confirmText: '全部吊销',
  });
  if (!ok) return;
  try {
    const page = await api<PageResult<any>>('/api/v1/admin/users', {
      query: { username: name, pageNum: 1, pageSize: 20 },
      silent: true,
    });
    const user = (page?.records || []).find((item: any) => item.username === name);
    if (!user) {
      toast('未找到该账号,无法批量吊销', 'warn');
      return;
    }
    const count = await api<number>(`/api/v1/admin/tokens/users/${user.id}`, { method: 'DELETE' });
    toast(`已吊销 ${count ?? 0} 条授权`, 'success');
    if (username.value === name) await load();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

/* ---------------- 详情 ---------------- */

const detailOpen = ref(false);
const detailRow = ref<any>(null);

function openDetail(row: any) {
  detailRow.value = row;
  detailOpen.value = true;
}

const columns = [
  {
    label: '授权',
    render: (row: any) =>
      h('div', { title: row.authorizationId }, [
        h('div', { class: 'cell-mono' }, shortId(row.authorizationId, 14)),
        h('div', { class: 'cell-sub' }, row.grantType || '—'),
      ]),
  },
  { label: '客户端', render: (row: any) => mono(row.clientId) },
  {
    label: '作用域',
    render: (row: any) => tags(row.scopes || [], 4),
  },
  {
    label: '令牌',
    render: (row: any) => {
      const list = [];
      if (row.hasAccessToken) list.push(badge('access', 'is-info'));
      if (row.hasRefreshToken) list.push(badge('refresh', 'is-gold'));
      return list.length ? h('span', { class: 'tag-row' }, list) : muted('—');
    },
  },
  { label: '签发时间', render: (row: any) => muted(fmtTime(row.issuedAt)) },
  {
    label: '过期时间',
    render: (row: any) =>
      h('div', { class: 'cell-stack' }, [
        h('span', { class: isExpired(row) ? 'muted' : '' }, fmtTime(row.expiresAt)),
        isExpired(row) ? h('span', { class: 'cell-sub' }, '已过期') : null,
      ]),
  },
];

const rowActions = (row: any) => [
  { label: '详情', onClick: () => openDetail(row) },
  { label: '吊销', perm: 'token:revoke', danger: true, onClick: () => revoke(row) },
];
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="令牌"
      desc="按账号检索已签发的授权;吊销即写入黑名单,业务侧下一次校验即刻失败。"
    >
      <template #meta>
        <span class="token-mode">以账号为中心检索</span>
      </template>
    </PageHeader>

    <section class="card token-console">
      <div class="token-console-glow" aria-hidden="true" />
      <div class="card-head">
        <div class="card-head-main">
          <div class="card-title">令牌检索</div>
          <div class="card-sub">登录名为精确匹配;客户端标识可选,用于收窄到单个应用。</div>
        </div>
        <div class="card-actions">
          <span class="token-scope-badge">
            <AppIcon name="shield" :size="13" />
            <span>令牌值不可见 · 仅展示元数据</span>
          </span>
        </div>
      </div>
      <form class="filter-bar" @submit.prevent="doQuery">
        <div class="filter token-filter">
          <label class="filter-label" for="token-username">
            登录名 <em class="token-required">必填</em>
          </label>
          <input
            id="token-username"
            v-model="usernameDraft"
            class="input"
            type="search"
            autocomplete="off"
            placeholder="精确登录名,如 admin"
          />
        </div>
        <div class="filter token-filter">
          <label class="filter-label" for="token-client">
            客户端标识 <span class="token-optional">可选</span>
          </label>
          <input
            id="token-client"
            v-model="clientDraft"
            class="input"
            type="search"
            autocomplete="off"
            placeholder="如 beenest-portal"
          />
        </div>
        <div class="filter-actions">
          <button class="btn btn-accent" type="submit" :disabled="loading">
            <AppIcon name="search" :size="15" />
            <span>查询令牌</span>
          </button>
          <button
            v-if="canRevoke"
            class="btn btn-danger-soft"
            type="button"
            :disabled="!canRevokeAll || loading"
            title="按账号清除全部客户端授权"
            @click="revokeAll"
          >
            <AppIcon name="trash" :size="15" />
            <span>吊销全部</span>
          </button>
        </div>
      </form>
    </section>

    <section class="card table-card token-results">
      <div class="card-head">
        <div class="card-head-main">
          <div class="card-title">查询结果</div>
          <div class="card-sub">
            <template v-if="queried">
              {{ scopeLabel || '全部账号' }} · 共 {{ total }} 条授权
            </template>
            <template v-else>输入登录名后查询该账号已签发的授权</template>
          </div>
        </div>
        <div v-if="queried" class="card-actions">
          <button class="icon-btn" type="button" title="刷新" aria-label="刷新" :disabled="loading" @click="refresh">
            <AppIcon name="refresh" :size="16" :class="{ spin: loading }" />
          </button>
        </div>
      </div>

      <div v-if="error" class="load-error">
        <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
        <div class="load-error-copy">
          <strong>数据加载失败</strong>
          <span>{{ error }}</span>
        </div>
        <button class="btn btn-sm" type="button" @click="load">重试</button>
      </div>

      <div v-else-if="!queried && !loading" class="token-blank">
        <span class="token-blank-mark">
          <AppIcon name="token" :size="26" />
        </span>
        <div class="token-blank-title">以账号为起点</div>
        <p class="token-blank-hint">
          输入登录名后,这里会列出该账号在各客户端上的授权、作用域与令牌有效期。事故处置时可直接定点吊销。
        </p>
        <div class="token-blank-steps">
          <span><i>01</i>输入精确登录名</span>
          <span><i>02</i>可选收窄客户端</span>
          <span><i>03</i>定点吊销或全部清除</span>
        </div>
      </div>

      <template v-else>
        <DataTable
          :columns="columns"
          :rows="rows"
          row-key="authorizationId"
          :row-actions="rowActions"
          :row-click="openDetail"
          :loading="loading"
          empty-text="该账号没有已签发的授权"
        />
        <PagerBar
          v-if="!loading && rows.length"
          :page-num="pageNum"
          :pages="pages"
          :total="total"
          :page-size="pageSize"
          @change="changePage"
          @update:page-size="changePageSize"
        />
      </template>
    </section>

    <BaseDrawer
      v-model:open="detailOpen"
      title="令牌详情"
      :subtitle="detailRow?.authorizationId"
      width="580px"
    >
      <template v-if="detailRow">
        <div class="token-detail-head">
          <span class="token-detail-mark">
            <AppIcon name="token" :size="20" />
          </span>
          <div>
            <div class="cell-strong">{{ detailRow.clientId }}</div>
            <div class="cell-sub">{{ detailRow.grantType || '授权' }} · {{ detailRow.principalName }}</div>
          </div>
          <span v-if="isExpired(detailRow)" class="badge is-warn token-detail-state">已过期</span>
          <span v-else class="badge is-success token-detail-state">有效期内</span>
        </div>
        <DetailList
          :rows="[
            { label: '授权主键', value: detailRow.authorizationId, mono: true },
            { label: '客户端', value: detailRow.clientId, mono: true },
            { label: '授权类型', value: detailRow.grantType || '—' },
            { label: '授权主体', value: detailRow.principalName, mono: true },
            { label: '作用域', value: [...(detailRow.scopes || [])].join(' ') || '—', mono: true },
            { label: 'access token', value: detailRow.hasAccessToken ? '已签发' : '未签发' },
            { label: 'refresh token', value: detailRow.hasRefreshToken ? '已签发(可续期)' : '未签发' },
            { label: '签发时间', value: fmtTime(detailRow.issuedAt) },
            { label: '过期时间', value: fmtTime(detailRow.expiresAt) },
          ]"
        />
        <div class="alert is-warn" style="margin-top: 16px">
          <span>吊销会写入令牌黑名单并删除授权记录,该授权下的 access / refresh token 在业务接口校验时立即失效。</span>
        </div>
      </template>
      <template #footer="{ close }">
        <button class="btn btn-ghost" type="button" @click="close">关闭</button>
        <button
          v-if="canRevoke && detailRow"
          class="btn btn-danger-soft"
          type="button"
          @click="close(); revoke(detailRow)"
        >
          吊销该授权
        </button>
      </template>
    </BaseDrawer>
  </div>
</template>

<style scoped>
.token-mode {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border: 1px solid rgba(168, 115, 10, 0.24);
  border-radius: 999px;
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-size: 10.5px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.token-console {
  position: relative;
  overflow: hidden;
}

.token-console-glow {
  position: absolute;
  top: -80px;
  right: -60px;
  width: 240px;
  height: 240px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(242, 181, 46, 0.16), transparent 68%);
  pointer-events: none;
}

.token-scope-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--muted);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.token-filter {
  width: 240px;
}

.token-required {
  margin-left: 4px;
  font-style: normal;
  font-weight: 700;
  color: var(--gold-deep);
}

.token-optional {
  margin-left: 4px;
  font-weight: 500;
  color: var(--faint);
}

.token-results {
  margin-top: 18px;
}

.token-blank {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 52px 24px 46px;
  background:
    radial-gradient(circle at 50% 0%, rgba(242, 181, 46, 0.09), transparent 52%),
    var(--surface);
}

.token-blank-mark {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin-bottom: 16px;
  border: 1px solid rgba(168, 115, 10, 0.24);
  border-radius: 17px;
  background: linear-gradient(150deg, var(--gold-soft), rgba(255, 253, 248, 0.7));
  color: var(--gold-deep);
}

.token-blank-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink);
}

.token-blank-hint {
  max-width: 460px;
  margin: 8px 0 18px;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.7;
}

.token-blank-steps {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.token-blank-steps span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-2);
  color: var(--ink-2);
  font-size: 11.5px;
}

.token-blank-steps i {
  font-style: normal;
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
  color: var(--gold-deep);
}

.token-detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.07));
}

.token-detail-mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border: 1px solid rgba(168, 115, 10, 0.22);
  border-radius: 12px;
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.token-detail-state {
  margin-left: auto;
}

@media (max-width: 720px) {
  .token-filter {
    width: 100%;
  }

  .token-scope-badge {
    display: none;
  }
}
</style>
