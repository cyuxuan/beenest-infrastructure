<script setup lang="ts">
import { h } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import type { ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub, timeCell, type Tone } from '@/lib/ui';

const CHANNELS: Record<string, { text: string; tone: Tone }> = {
  EMAIL: { text: '邮件', tone: 'is-info' },
  SMS: { text: '短信', tone: 'is-gold' },
};

const STATUSES: Record<string, { text: string; tone: Tone }> = {
  SENT: { text: '已投递', tone: 'is-success' },
  FAILED: { text: '投递失败', tone: 'is-danger' },
};

const config: ResourceConfig = {
  title: '通知投递',
  desc: '口令找回、凭证变更提醒与开户通知的投递结果只追加不修改;失败记录附带原始错误,便于直接定位是模板、收件地址还是发信服务的问题。',
  endpoint: '/api/v1/admin/notification-logs',
  permCreate: false,
  filters: [
    {
      name: 'channel',
      label: '渠道',
      type: 'select',
      options: [
        { value: '', label: '全部渠道' },
        { value: 'EMAIL', label: '邮件' },
        { value: 'SMS', label: '短信' },
      ],
    },
    {
      name: 'status',
      label: '投递结果',
      type: 'select',
      options: [
        { value: '', label: '全部结果' },
        { value: 'SENT', label: '已投递' },
        { value: 'FAILED', label: '投递失败' },
      ],
    },
    { name: 'recipient', label: '收件地址', placeholder: '支持模糊匹配' },
    { name: 'templateCode', label: '模板编码', placeholder: '如 password-reset' },
    { name: 'fromTime', label: '起始时间', type: 'datetime' },
    { name: 'toTime', label: '结束时间', type: 'datetime' },
  ],
  columns: [
    { label: '时间', width: '150px', render: (row) => timeCell(row.createdTime, true) },
    {
      label: '渠道',
      width: '90px',
      render: (row) => {
        const meta = CHANNELS[row.channel] || { text: row.channel || '未知', tone: '' as Tone };
        return badge(meta.text, meta.tone);
      },
    },
    { label: '收件地址', width: '220px', render: (row) => mono(row.recipient) },
    {
      label: '通知',
      width: '220px',
      render: (row) => strongSub(row.subject || '—', row.templateCode || '无模板编码'),
    },
    {
      label: '结果',
      width: '100px',
      render: (row) => {
        const meta = STATUSES[row.status] || { text: row.status || '未知', tone: '' as Tone };
        return badge(meta.text, meta.tone);
      },
    },
    {
      label: '失败原因',
      render: (row) =>
        row.errorMessage
          ? h('span', { class: 'muted truncate', title: row.errorMessage }, row.errorMessage)
          : muted('—'),
    },
  ],
  emptyText: '当前条件下没有投递记录',
};
</script>

<template>
  <ResourcePage :config="config" />
</template>
