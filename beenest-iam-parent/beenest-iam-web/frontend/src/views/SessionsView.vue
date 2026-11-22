<script setup lang="ts">
import { computed, h, ref } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import BaseModal from '@/components/BaseModal.vue';
import DataTable from '@/components/DataTable.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime, relTime, shortId } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { ResourceConfig } from '@/lib/types';
import { badge, dotBadge, mono, muted, strongSub, type Tone } from '@/lib/ui';
import { can } from '@/stores/auth';

const STATES: Record<string, { text: string; tone: Tone }> = {
  ACTIVE: { text: '在线', tone: 'is-success' },
  LOGGED_OUT: { text: '已注销', tone: '' },
  EXPIRED: { text: '已过期', tone: 'is-warn' },
  REPLACED: { text: '被顶下线', tone: 'is-gold' },
};

const BROADCAST_STATES: Record<string, { text: string; tone: Tone }> = {
  SENT: { text: '已送达', tone: 'is-success' },
  FAILED: { text: '投递失败', tone: 'is-danger' },
  SKIPPED: { text: '未登记地址', tone: 'is-warn' },
  PENDING: { text: '待投递', tone: '' },
};

async function revoke(row: any, reload: () => Promise<void>) {
  const ok = await confirmDialog({
    title: '强制下线',
    message: `确认结束会话「${shortId(row.sid, 14)}」吗?该浏览器或客户端将立即失去登录态,并向其登录过的接入应用广播单点登出。`,
    confirmText: '结束会话',
  });
  if (!ok) return;
  try {
    await api(`/api/v1/admin/sessions/${encodeURIComponent(row.sid)}`, { method: 'DELETE' });
    toast('会话已结束', 'success');
    await reload();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

/* ---------------- 登出广播 ---------------- */

const broadcastOpen = ref(false);
const broadcastRow = ref<any>(null);
const broadcastLoading = ref(false);
const broadcastError = ref('');
const broadcasts = ref<any[]>([]);

const broadcastSummary = computed(() => {
  const counts = { SENT: 0, FAILED: 0, SKIPPED: 0, PENDING: 0 };
  for (const item of broadcasts.value) {
    if (item.state in counts) counts[item.state as keyof typeof counts] += 1;
    else counts.PENDING += 1;
  }
  return counts;
});

const broadcastRisk = computed(
  () => broadcastSummary.value.FAILED + broadcastSummary.value.SKIPPED,
);

async function openBroadcasts(row: any) {
  broadcastRow.value = row;
  broadcastOpen.value = true;
  broadcastLoading.value = true;
  broadcastError.value = '';
  broadcasts.value = [];
  try {
    broadcasts.value =
      (await api<any[]>(`/api/v1/admin/sessions/${encodeURIComponent(row.sid)}/broadcasts`, {
        silent: true,
      })) || [];
  } catch (error) {
    broadcastError.value = errorMessage(error);
  } finally {
    broadcastLoading.value = false;
  }
}

const broadcastColumns = [
  {
    label: '应用',
    render: (row: any) => strongSub(row.clientKey || '—', `#${row.id}`),
  },
  {
    label: '投递地址',
    render: (row: any) =>
      row.targetUri
        ? h('span', { class: 'cell-mono truncate broadcast-uri', title: row.targetUri }, row.targetUri)
        : muted('未登记'),
  },
  {
    label: '状态',
    render: (row: any) => {
      const meta = BROADCAST_STATES[row.state] || { text: row.state || '未知', tone: '' as Tone };
      return h('div', { class: 'cell-stack' }, [
        dotBadge(meta.text, meta.tone),
        row.errorMessage
          ? h('div', { class: 'cell-sub is-danger broadcast-error' }, row.errorMessage)
          : null,
      ]);
    },
  },
  {
    label: 'HTTP',
    align: 'right' as const,
    render: (row: any) => muted(row.httpStatus == null ? '—' : String(row.httpStatus)),
  },
  {
    label: '时间',
    render: (row: any) => muted(fmtTime(row.createdTime)),
  },
];

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '在线会话',
  desc: '会话是浏览器登录态的载体:结束会话只影响该端并广播单点登出,全端下线请使用「用户」页的强制下线。',
  endpoint: '/api/v1/admin/sessions',
  permCreate: false,
  rowKey: 'id',
  filters: [
    { name: 'username', label: '登录名', placeholder: '支持模糊匹配' },
    {
      name: 'state',
      label: '会话状态',
      type: 'select',
      options: [
        { value: '', label: '全部状态' },
        { value: 'ACTIVE', label: '在线' },
        { value: 'LOGGED_OUT', label: '已注销' },
        { value: 'EXPIRED', label: '已过期' },
        { value: 'REPLACED', label: '被顶下线' },
      ],
    },
  ],
  columns: [
    {
      label: '会话',
      width: '170px',
      render: (row) =>
        h('div', { title: row.sid }, [
          h('div', { class: 'cell-mono' }, shortId(row.sid, 16)),
          h('div', { class: 'cell-sub' }, `用户 #${row.userId}`),
        ]),
    },
    {
      label: '登录方式',
      width: '110px',
      render: (row) => badge(row.loginType || 'PASSWORD', '', true),
    },
    {
      label: '来源',
      width: '260px',
      render: (row) =>
        h('div', { class: 'cell-stack session-origin' }, [
          h('div', { class: 'cell-mono' }, row.ip || '—'),
          h('div', { class: 'cell-sub truncate', title: row.userAgent || '' }, row.userAgent || '—'),
        ]),
    },
    {
      label: '状态',
      width: '90px',
      render: (row) => {
        const meta = STATES[row.state] || { text: row.state || '未知', tone: '' as Tone };
        return dotBadge(meta.text, meta.tone);
      },
    },
    { label: '登录时间', width: '150px', render: (row) => muted(fmtTime(row.createdTime)) },
    {
      label: '最后活跃',
      width: '120px',
      render: (row) =>
        h('span', { class: 'muted', title: fmtTime(row.lastActiveTime) }, relTime(row.lastActiveTime)),
    },
  ],
  rowActions: (row, ctx) => [
    {
      label: '登出广播',
      perm: 'session:broadcast',
      onClick: () => openBroadcasts(row),
    },
    {
      label: '强制下线',
      perm: 'session:revoke',
      danger: true,
      hidden: (item) => item.state !== 'ACTIVE',
      onClick: () => revoke(row, ctx.reload),
    },
  ],
  rowClick: (row) => {
    if (can('session:broadcast')) openBroadcasts(row);
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseModal
    v-model:open="broadcastOpen"
    size="xl"
    :title="`登出广播 · ${shortId(broadcastRow?.sid, 14)}`"
    subtitle="回答一个具体问题:这次登出,哪个接入应用其实没有收到通知?"
  >
    <div v-if="broadcastLoading" class="broadcast-loading">
      <span class="btn-spinner" />
      <span>正在读取投递记录…</span>
    </div>

    <div v-else-if="broadcastError" class="alert is-danger">
      {{ broadcastError }}
    </div>

    <template v-else>
      <div class="broadcast-summary">
        <div class="broadcast-chip">
          <span class="broadcast-chip-value">{{ broadcasts.length }}</span>
          <span class="broadcast-chip-label">应用总数</span>
        </div>
        <div class="broadcast-chip is-success">
          <span class="broadcast-chip-value">{{ broadcastSummary.SENT }}</span>
          <span class="broadcast-chip-label">已送达</span>
        </div>
        <div class="broadcast-chip" :class="{ 'is-danger': broadcastSummary.FAILED }">
          <span class="broadcast-chip-value">{{ broadcastSummary.FAILED }}</span>
          <span class="broadcast-chip-label">投递失败</span>
        </div>
        <div class="broadcast-chip" :class="{ 'is-warn': broadcastSummary.SKIPPED }">
          <span class="broadcast-chip-value">{{ broadcastSummary.SKIPPED }}</span>
          <span class="broadcast-chip-label">未登记地址</span>
        </div>
        <div class="broadcast-chip" :class="{ 'is-warn': broadcastSummary.PENDING }">
          <span class="broadcast-chip-value">{{ broadcastSummary.PENDING }}</span>
          <span class="broadcast-chip-label">待投递</span>
        </div>
      </div>

      <div v-if="broadcastRisk" class="alert is-danger broadcast-alert">
        <span>
          {{ broadcastSummary.FAILED ? `${broadcastSummary.FAILED} 个应用投递失败;` : '' }}
          {{ broadcastSummary.SKIPPED ? `${broadcastSummary.SKIPPED} 个应用未登记后通道登出地址,无法通知。` : '' }}
          请尽快检查应用的单点登出配置,否则这些应用上的登录态会一直保留。
        </span>
      </div>
      <div v-else-if="broadcasts.length" class="alert is-success broadcast-alert">
        <span>所有已登记应用均已收到登出通知。</span>
      </div>
      <div v-else class="alert is-warn broadcast-alert">
        <span>该会话没有登出广播记录:用户可能未通过本平台授权过任何接入应用,或记录已超出保留期。</span>
      </div>

      <DataTable
        :columns="broadcastColumns"
        :rows="broadcasts"
        empty-text="没有登出广播记录"
      />
    </template>

    <template #footer="{ close }">
      <button class="btn btn-ghost" type="button" @click="close">关闭</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.session-origin {
  max-width: 300px;
}

.broadcast-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.broadcast-chip {
  flex: 1 1 120px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface-2);
}

.broadcast-chip-value {
  display: block;
  font-size: 22px;
  font-weight: 720;
  font-variant-numeric: tabular-nums;
  color: var(--ink);
}

.broadcast-chip-label {
  font-size: 12px;
  color: var(--muted);
}

.broadcast-chip.is-success {
  border-color: rgba(22, 122, 87, 0.24);
  background: rgba(22, 122, 87, 0.07);
}

.broadcast-chip.is-success .broadcast-chip-value {
  color: var(--success, #167a57);
}

.broadcast-chip.is-danger {
  border-color: rgba(176, 42, 55, 0.28);
  background: rgba(176, 42, 55, 0.07);
}

.broadcast-chip.is-danger .broadcast-chip-value {
  color: var(--danger, #b02a37);
}

.broadcast-chip.is-warn {
  border-color: rgba(164, 116, 5, 0.3);
  background: rgba(242, 181, 46, 0.12);
}

.broadcast-chip.is-warn .broadcast-chip-value {
  color: var(--gold-deep);
}

.broadcast-alert {
  margin-bottom: 14px;
}

.broadcast-uri {
  display: inline-block;
  max-width: 300px;
  vertical-align: bottom;
}

.broadcast-error {
  max-width: 260px;
  white-space: normal;
  color: var(--danger, #b02a37);
}

.broadcast-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--muted);
  font-size: 13px;
}
</style>
