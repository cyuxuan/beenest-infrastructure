<script setup lang="ts">
import ResourcePage from '@/components/ResourcePage.vue';
import type { ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub } from '@/lib/ui';
import { fmtTime } from '@/lib/format';

const config: ResourceConfig = {
  title: '客户端作用域',
  desc: '作用域是 OAuth2 的授权边界:客户端只能申请、用户只能同意已登记的作用域。',
  endpoint: '/api/v1/admin/client-scopes',
  permCreate: 'client-scope:create',
  deleteName: '作用域',
  rowName: (row) => row.scopeKey,
  createLabel: '新建作用域',
  paged: false,
  filters: [
    { name: 'scopeKey', label: '作用域键', placeholder: '如 profile' },
    { name: 'scopeName', label: '作用域名称', placeholder: '支持模糊匹配' },
  ],
  columns: [
    { label: '作用域键', render: (row) => mono(row.scopeKey) },
    { label: '名称', render: (row) => strongSub(row.scopeName) },
    {
      label: '默认授予',
      render: (row) => (row.isDefault ? badge('默认授予', 'is-gold') : badge('按需申请')),
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'client-scope:update', onClick: () => ctx.openEditor(row) },
    { label: '删除', perm: 'client-scope:delete', danger: true, onClick: () => ctx.remove(row) },
  ],
  form: {
    title: '作用域',
    subtitle: '作用域键创建后不可修改',
    fields: [
      { name: 'scopeKey', label: '作用域键', required: true, placeholder: '如 profile', mono: true },
      { name: 'scopeName', label: '作用域名称', required: true, placeholder: '如 基本资料' },
      {
        name: 'isDefault',
        label: '默认授予',
        type: 'switch',
        onText: '默认授予',
        help: '默认作用域在未显式申请时也会包含在授权中',
      },
    ],
    editFields: [
      { name: 'scopeName', label: '作用域名称', required: true },
      { name: 'isDefault', label: '默认授予', type: 'switch', onText: '默认授予' },
    ],
    fromRow: (row) => ({ scopeName: row.scopeName, isDefault: Boolean(row.isDefault) }),
  },
};
</script>

<template>
  <ResourcePage :config="config" />
</template>
