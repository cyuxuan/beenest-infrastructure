<script setup lang="ts">
import ResourcePage from '@/components/ResourcePage.vue';
import { download, errorMessage, saveBlob } from '@/lib/api';
import { notifyError } from '@/lib/toast';
import type { ResourceConfig } from '@/lib/types';
import type { Tone } from '@/lib/ui';
import { badge, mono, muted, timeCell } from '@/lib/ui';

const ACTION_TONES: Record<string, Tone> = {
  CREATE: 'is-success',
  UPDATE: 'is-info',
  DELETE: 'is-danger',
  LOGIN: 'is-gold',
  LOGOUT: '',
};

function actionTone(action: unknown): Tone {
  if (!action) return '';
  return ACTION_TONES[String(action).toUpperCase().split('_')[0]] || '';
}

const config: ResourceConfig = {
  title: '审计日志',
  desc: '谁在什么时间对什么对象做了什么,全部落库;审计记录不可修改,用于合规检查与事故回溯。',
  endpoint: '/api/v1/admin/audit-logs',
  permCreate: false,
  filters: [
    { name: 'action', label: '动作', placeholder: '如 USER_CREATE' },
    { name: 'operatorName', label: '操作人', placeholder: '登录名' },
    { name: 'targetType', label: '对象类型', placeholder: '如 USER' },
    { name: 'fromTime', label: '起始时间', type: 'datetime' },
    { name: 'toTime', label: '结束时间', type: 'datetime' },
  ],
  headerActions: [
    {
      label: '导出 CSV',
      icon: 'download',
      perm: 'audit:export',
      onClick: async ({ filters }) => {
        try {
          // 导出用与列表同一套筛选条件:看到什么就导出什么
          const { blob, filename } = await download('/api/v1/admin/audit-logs/export', { query: filters });
          saveBlob(blob, filename);
        } catch (error) {
          notifyError(errorMessage(error));
        }
      },
    },
  ],
  columns: [
    { label: '时间', width: '176px', render: (row) => timeCell(row.occurredTime) },
    { label: '操作人', width: '120px', render: (row) => badge(row.operatorName || '—', '', true) },
    { label: '动作', width: '170px', render: (row) => badge(row.action || '—', actionTone(row.action)) },
    {
      label: '对象',
      width: '150px',
      render: (row) => (row.targetType ? mono(`${row.targetType}${row.targetId ? `#${row.targetId}` : ''}`) : muted('—')),
    },
    { label: '描述', width: '240px', render: (row) => muted(row.description || '—') },
    { label: '来源 IP', width: '170px', render: (row) => mono(row.ip || '—') },
  ],
};
</script>

<template>
  <ResourcePage :config="config" />
</template>
