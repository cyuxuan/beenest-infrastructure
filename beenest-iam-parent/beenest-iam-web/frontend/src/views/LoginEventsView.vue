<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { useRoute } from 'vue-router';
import ResourcePage from '@/components/ResourcePage.vue';
import BaseModal from '@/components/BaseModal.vue';
import DataTable from '@/components/DataTable.vue';
import PagerBar from '@/components/PagerBar.vue';
import { api, errorMessage } from '@/lib/api';
import { fmtTime, relTime } from '@/lib/format';
import type { PageResult, ResourceConfig } from '@/lib/types';
import { badge, muted, strongSub, timeCell, type Tone } from '@/lib/ui';

const route = useRoute();

const EVENTS: Record<string, { text: string; tone: Tone }> = {
  LOGIN_SUCCESS: { text: '登录成功', tone: 'is-success' },
  LOGIN_FAILURE: { text: '登录失败', tone: 'is-danger' },
  MFA_SUCCESS: { text: 'MFA 成功', tone: 'is-info' },
  MFA_FAILURE: { text: 'MFA 失败', tone: 'is-warn' },
  LOGOUT: { text: '退出登录', tone: '' },
};

const initialUsername = computed(() => {
  const value = route.query.username;
  return typeof value === 'string' ? value : '';
});

/* ---------------- IP 溯源 ---------------- */

const ipOpen = ref(false);
const ipAddress = ref('');
const ipRows = ref<any[]>([]);
const ipLoading = ref(false);
const ipError = ref('');
const ipPageNum = ref(1);
const ipPageSize = ref(20);
const ipTotal = ref(0);
const ipPages = ref(0);

let ipRequestSeq = 0;

async function loadIpHistory(page = ipPageNum.value) {
  const seq = ++ipRequestSeq;
  ipLoading.value = true;
  ipError.value = '';
  try {
    const result = await api<PageResult<any>>(
      `/api/v1/admin/login-events/ip/${encodeURIComponent(ipAddress.value)}`,
      { query: { pageNum: page, pageSize: ipPageSize.value }, silent: true },
    );
    if (seq !== ipRequestSeq) return;
    ipRows.value = result?.records || [];
    ipTotal.value = result?.total || 0;
    ipPages.value = result?.pages || 0;
    ipPageNum.value = result?.pageNum || page;
  } catch (error) {
    if (seq !== ipRequestSeq) return;
    ipRows.value = [];
    ipTotal.value = 0;
    ipPages.value = 0;
    ipError.value = errorMessage(error);
  } finally {
    if (seq === ipRequestSeq) ipLoading.value = false;
  }
}

function openIpHistory(ip: string) {
  if (!ip) return;
  ipAddress.value = ip;
  ipOpen.value = true;
  ipRows.value = [];
  ipTotal.value = 0;
  ipPages.value = 0;
  ipPageNum.value = 1;
  void loadIpHistory(1);
}

function changeIpPage(page: number) {
  void loadIpHistory(page);
}

const ipColumns = [
  {
    label: '时间',
    render: (row: any) =>
      h('div', null, [h('div', null, fmtTime(row.occurredTime)), h('div', { class: 'cell-sub' }, relTime(row.occurredTime))]),
  },
  { label: '账号', render: (row: any) => strongSub(row.username || '—', row.userId ? `用户 #${row.userId}` : '账号不存在') },
  {
    label: '事件',
    render: (row: any) => {
      const meta = EVENTS[row.eventType] || { text: row.eventType || '未知', tone: '' as Tone };
      return badge(meta.text, meta.tone);
    },
  },
  { label: '方式', render: (row: any) => badge(row.loginType || 'PASSWORD', '', true) },
  { label: '失败原因', render: (row: any) => muted(row.failReason || '—') },
  {
    label: '客户端',
    render: (row: any) =>
      h('span', { class: 'muted truncate ip-agent', title: row.userAgent || '' }, row.userAgent || '—'),
  },
];

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '登录事件',
  desc: '每次认证尝试都会留痕(成功、失败、MFA、退出);同一 IP 的高频失败通常意味着撞库或口令喷洒。',
  endpoint: '/api/v1/admin/login-events',
  permCreate: false,
  initialFilters: () => ({ username: initialUsername.value }),
  filters: [
    { name: 'username', label: '登录名', placeholder: '支持模糊匹配' },
    {
      name: 'eventType',
      label: '事件类型',
      type: 'select',
      options: [
        { value: '', label: '全部事件' },
        { value: 'LOGIN_SUCCESS', label: '登录成功' },
        { value: 'LOGIN_FAILURE', label: '登录失败' },
        { value: 'MFA_SUCCESS', label: 'MFA 成功' },
        { value: 'MFA_FAILURE', label: 'MFA 失败' },
        { value: 'LOGOUT', label: '退出登录' },
      ],
    },
    { name: 'ip', label: '来源 IP', placeholder: '精确匹配' },
    { name: 'fromTime', label: '起始时间', type: 'datetime' },
    { name: 'toTime', label: '结束时间', type: 'datetime' },
  ],
  columns: [
    { label: '时间', width: '150px', render: (row) => timeCell(row.occurredTime, true) },
    {
      label: '账号',
      width: '140px',
      render: (row) => strongSub(row.username || '—', row.userId ? `用户 #${row.userId}` : '账号不存在'),
    },
    {
      label: '事件',
      width: '110px',
      render: (row) => {
        const meta = EVENTS[row.eventType] || { text: row.eventType || '未知', tone: '' as Tone };
        return badge(meta.text, meta.tone);
      },
    },
    { label: '方式', width: '110px', render: (row) => badge(row.loginType || 'PASSWORD', '', true) },
    {
      label: '来源',
      width: '180px',
      render: (row) =>
        row.ip
          ? h(
              'button',
              {
                class: 'link-btn link-inline cell-mono ip-link',
                type: 'button',
                title: '查看该 IP 的全部登录轨迹',
                onClick: (event: MouseEvent) => {
                  event.stopPropagation();
                  openIpHistory(row.ip);
                },
              },
              [row.ip, h('span', { class: 'link-ext', 'aria-hidden': 'true' }, '↗')],
            )
          : muted('—'),
    },
    { label: '失败原因', width: '130px', render: (row) => muted(row.failReason || '—') },
    {
      label: '客户端',
      width: '240px',
      render: (row) =>
        h('span', { class: 'muted truncate', title: row.userAgent || '' }, row.userAgent || '—'),
    },
  ],
};
</script>

<template>
  <ResourcePage :key="initialUsername || 'all'" :config="config" />

  <BaseModal
    v-model:open="ipOpen"
    size="xl"
    :title="`来源 IP 轨迹 · ${ipAddress}`"
    subtitle="按来源地址复盘认证行为,用于撞库识别与取证的横向关联"
  >
    <div v-if="ipLoading" class="ip-loading">
      <span class="btn-spinner" />
      <span>正在读取该 IP 的登录事件…</span>
    </div>

    <div v-else-if="ipError" class="alert is-danger">
      <span>{{ ipError }}</span>
      <button class="link-btn" type="button" @click="loadIpHistory(ipPageNum)">重试</button>
    </div>

    <template v-else>
      <div class="ip-summary">
        <span class="ip-summary-ip">{{ ipAddress }}</span>
        <span class="ip-summary-count">共 {{ ipTotal }} 次认证尝试</span>
      </div>
      <DataTable
        :columns="ipColumns"
        :rows="ipRows"
        empty-text="该 IP 暂无登录事件"
      />
      <PagerBar
        v-if="ipRows.length"
        :page-num="ipPageNum"
        :pages="ipPages"
        :total="ipTotal"
        :page-size="ipPageSize"
        :sizes="[20, 50, 100]"
        @change="changeIpPage"
      />
    </template>

    <template #footer="{ close }">
      <button class="btn btn-ghost" type="button" @click="close">关闭</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.event-agent {
  display: inline-block;
  max-width: 220px;
  vertical-align: bottom;
}

.ip-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.ip-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 56px 0;
  color: var(--muted);
  font-size: 13px;
}

.ip-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.07));
}

.ip-summary-ip {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 680;
  color: var(--ink);
}

.ip-summary-count {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}

.ip-agent {
  display: inline-block;
  max-width: 220px;
  vertical-align: bottom;
}

@media (max-width: 640px) {
  .event-agent,
  .ip-agent {
    max-width: 140px;
  }
}
</style>
