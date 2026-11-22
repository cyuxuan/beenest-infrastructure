<script setup lang="ts">
import ResourcePage from '@/components/ResourcePage.vue';
import type { ResourceConfig } from '@/lib/types';
import { api } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { toast } from '@/lib/toast';
import { mono, strongSub, tags } from '@/lib/ui';

const config: ResourceConfig = {
  title: '授权同意',
  desc: '当客户端开启「征求用户同意」时,用户勾选的 scope 会落为同意记录;撤销后下次授权将重新征求。',
  endpoint: '/api/v1/admin/consents',
  permCreate: false,
  paged: false,
  filters: [
    { name: 'principalName', label: '授权主体', placeholder: '登录名' },
    { name: 'clientId', label: '客户端标识', placeholder: '如 beenest-portal' },
  ],
  columns: [
    { label: '授权主体', render: (row) => strongSub(row.principalName) },
    { label: '客户端', render: (row) => mono(row.clientId) },
    { label: '已同意的范围', render: (row) => tags(row.authorities || [], 8) },
  ],
  rowActions: (row, ctx) => [
    {
      label: '撤销同意',
      perm: 'consent:revoke',
      danger: true,
      onClick: async () => {
        const ok = await confirmDialog({
          title: '撤销授权同意',
          message: `确认撤销 <strong>${row.principalName}</strong> 对 <strong>${row.clientId}</strong> 的授权同意吗?该客户端下次发起授权时会重新弹出同意页。`,
          confirmText: '撤销',
        });
        if (!ok) return;
        try {
          await api(
            `/api/v1/admin/consents/${encodeURIComponent(row.clientId)}/${encodeURIComponent(row.principalName)}`,
            { method: 'DELETE' },
          );
          toast('同意记录已撤销', 'success');
          await ctx.reload();
        } catch {
          /* 统一提示 */
        }
      },
    },
  ],
};
</script>

<template>
  <ResourcePage :config="config" />
</template>
