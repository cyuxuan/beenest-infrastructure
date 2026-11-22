<script setup lang="ts">
import { h, ref } from 'vue';
import { useRoute } from 'vue-router';
import ResourcePage from '@/components/ResourcePage.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api } from '@/lib/api';
import { fmtTime } from '@/lib/format';
import type { FilterOption, ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub } from '@/lib/ui';

const route = useRoute();

const CLAIM_TYPES = [
  { value: 'STRING', label: 'STRING 字符串' },
  { value: 'NUMBER', label: 'NUMBER 数字' },
  { value: 'BOOLEAN', label: 'BOOLEAN 布尔' },
  { value: 'JSON', label: 'JSON 结构' },
];

const config = ref<ResourceConfig | null>(null);
const clientNames = new Map<string, string>();

function sourceCell(row: any) {
  if (row.userAttribute) {
    return h('div', { class: 'cell-stack' }, [
      mono(row.userAttribute),
      h('div', { class: 'cell-sub' }, '用户属性'),
    ]);
  }
  if (row.claimTemplate) {
    return h('div', { class: 'cell-stack' }, [
      h('span', { class: 'cell-mono truncate mapper-template', title: row.claimTemplate }, row.claimTemplate),
      h('div', { class: 'cell-sub' }, '固定模板'),
    ]);
  }
  return muted('—');
}

function validateSource(values: Record<string, any>): string | void {
  const hasAttribute = Boolean(String(values.userAttribute || '').trim());
  const hasTemplate = Boolean(String(values.claimTemplate || '').trim());
  if (hasAttribute === hasTemplate) {
    return '「用户属性键」与「固定取值模板」必须且只能填写一个';
  }
}

async function init() {
  const options: FilterOption[] = [];
  try {
    const page = await api<any>('/api/v1/admin/clients', {
      query: { pageNum: 1, pageSize: 300 },
      silent: true,
    });
    for (const client of page?.records || []) {
      options.push({ value: client.id, label: `${client.clientName}(${client.clientKey})` });
      clientNames.set(String(client.id), client.clientName);
    }
  } catch {
    /* 客户端名录不可用时仍可维护域级映射 */
  }

  const requested = typeof route.query.clientId === 'string' ? route.query.clientId : '';
  const initialClient = options.some((option) => String(option.value) === requested) ? requested : '';
  const clientOptions: FilterOption[] = [
    { value: '', label: '域级映射(全部客户端)' },
    ...options,
  ];

  config.value = {
    title: '协议映射',
    desc: '映射器决定令牌与 userinfo 中携带哪些用户属性,是业务系统拿到身份数据的唯一通道。未选择客户端时展示域级映射。',
    endpoint: '/api/v1/admin/protocol-mappers',
    permCreate: 'protocol-mapper:create',
    createLabel: '新建映射',
    deleteName: '映射器',
    rowName: (row) => row.name,
    initialFilters: initialClient ? { clientId: initialClient } : {},
    filters: [
      { name: 'clientId', label: '归属', type: 'select', options: clientOptions, width: '290px' },
    ],
    columns: [
      {
        label: '映射器',
        render: (row) =>
          strongSub(
            row.name,
            row.clientId
              ? clientNames.get(String(row.clientId)) || `客户端 #${row.clientId}`
              : '域级映射(全部客户端)',
          ),
      },
      { label: 'Claim', render: (row) => mono(row.claimName) },
      { label: '类型', render: (row) => badge(row.claimType || 'STRING', '', true) },
      { label: '取值来源', render: (row) => sourceCell(row) },
      { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
    ],
    rowActions: (row, ctx) => [
      { label: '编辑', perm: 'protocol-mapper:update', onClick: () => ctx.openEditor(row) },
      { label: '删除', perm: 'protocol-mapper:delete', danger: true, onClick: () => ctx.remove(row) },
    ],
    form: {
      title: '协议映射',
      subtitle: '客户端归属创建后不可修改;取值来源二选一',
      fields: [
        {
          name: 'clientId',
          label: '归属',
          type: 'select',
          full: true,
          options: clientOptions,
          help: '域级映射对全部客户端生效;选择具体客户端则只在该客户端的令牌中追加 claim',
        },
        { name: 'name', label: '映射器名称', required: true, placeholder: '如 部门声明' },
        { name: 'claimName', label: 'claim 名', required: true, mono: true, placeholder: '如 department' },
        { name: 'claimType', label: 'claim 类型', type: 'select', options: CLAIM_TYPES },
        { name: 'userAttribute', label: '用户属性键', placeholder: '如 department', help: '与固定取值模板二选一' },
        {
          name: 'claimTemplate',
          label: '固定取值模板',
          type: 'textarea',
          full: true,
          placeholder: '如 蜂巢科技;填写后忽略用户属性',
          help: '与用户属性键二选一,通常用于输出固定声明',
        },
      ],
      editFields: [
        { name: 'clientLabel', label: '归属', type: 'static', full: true },
        { name: 'name', label: '映射器名称', required: true },
        { name: 'claimName', label: 'claim 名', required: true, mono: true },
        { name: 'claimType', label: 'claim 类型', type: 'select', options: CLAIM_TYPES },
        { name: 'userAttribute', label: '用户属性键', help: '与固定取值模板二选一' },
        {
          name: 'claimTemplate',
          label: '固定取值模板',
          type: 'textarea',
          full: true,
          help: '与用户属性键二选一;修改来源时清空另一个字段',
        },
      ],
      defaults: { claimType: 'STRING', clientId: initialClient },
      fromRow: (row) => ({
        clientLabel: row.clientId
          ? `${clientNames.get(String(row.clientId)) || '客户端'} · #${row.clientId}`
          : '域级映射(全部客户端)',
        name: row.name,
        claimName: row.claimName,
        claimType: row.claimType || 'STRING',
        userAttribute: row.userAttribute || '',
        claimTemplate: row.claimTemplate || '',
      }),
      validate: validateSource,
      toCreate: (values) => {
        const body: Record<string, any> = {
          name: String(values.name || '').trim(),
          claimName: String(values.claimName || '').trim(),
          claimType: values.claimType,
        };
        if (values.clientId) body.clientId = Number(values.clientId);
        if (String(values.userAttribute || '').trim()) body.userAttribute = String(values.userAttribute).trim();
        if (String(values.claimTemplate || '').trim()) body.claimTemplate = String(values.claimTemplate).trim();
        return body;
      },
      toUpdate: (values) => {
        const body: Record<string, any> = {
          name: String(values.name || '').trim(),
          claimName: String(values.claimName || '').trim(),
          claimType: values.claimType,
        };
        if (String(values.userAttribute || '').trim()) body.userAttribute = String(values.userAttribute).trim();
        if (String(values.claimTemplate || '').trim()) body.claimTemplate = String(values.claimTemplate).trim();
        return body;
      },
    },
  };
}

void init();
</script>

<template>
  <ResourcePage v-if="config" :config="config" />
  <div v-else class="view-inner">
    <div class="card mapper-loading">
      <SkeletonTable :rows="6" :columns="4" />
    </div>
  </div>
</template>

<style scoped>
.mapper-template {
  display: inline-block;
  max-width: 240px;
  vertical-align: bottom;
}

.mapper-loading {
  padding: 22px;
  margin-top: 24px;
}
</style>
