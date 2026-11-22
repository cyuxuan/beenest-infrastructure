<script setup lang="ts">
import ResourcePage from '@/components/ResourcePage.vue';
import type { ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub, tags } from '@/lib/ui';
import { fmtTime } from '@/lib/format';

const TYPES = [
  { value: 'STRING', label: 'STRING 字符串' },
  { value: 'NUMBER', label: 'NUMBER 数值' },
  { value: 'BOOLEAN', label: 'BOOLEAN 布尔' },
  { value: 'DATE', label: 'DATE 日期' },
  { value: 'ENUM', label: 'ENUM 枚举' },
  { value: 'JSON', label: 'JSON 结构' },
];

function typeLabel(value: unknown) {
  return TYPES.find((type) => type.value === value)?.label || String(value || '—');
}

const config: ResourceConfig = {
  title: '扩展属性',
  desc: '属性定义是用户数据的契约:类型与校验规则决定写入是否合法,可检索属性会建立索引。',
  endpoint: '/api/v1/admin/attribute-defs',
  permCreate: 'attribute-def:create',
  deleteName: '属性定义',
  rowName: (row) => row.attrKey,
  createLabel: '新建属性',
  paged: false,
  filters: [
    { name: 'attrKey', label: '属性键', placeholder: '如 department' },
    { name: 'attrName', label: '属性名称', placeholder: '支持模糊匹配' },
  ],
  columns: [
    { label: '属性键', render: (row) => mono(row.attrKey) },
    { label: '属性名称', render: (row) => strongSub(row.attrName) },
    { label: '类型', render: (row) => badge(typeLabel(row.attrType), '', true) },
    {
      label: '约束',
      render: (row) => {
        const items: string[] = [];
        if (row.required) items.push('必填');
        if (row.searchable) items.push('可检索');
        if (row.uniqueFlag) items.push('唯一');
        return items.length ? tags(items, 4) : muted('—');
      },
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'attribute-def:update', onClick: () => ctx.openEditor(row) },
    { label: '删除', perm: 'attribute-def:delete', danger: true, onClick: () => ctx.remove(row) },
  ],
  form: {
    title: '属性',
    subtitle: '属性键创建后不可修改',
    fields: [
      { name: 'attrKey', label: '属性键', required: true, placeholder: '如 department', mono: true },
      { name: 'attrName', label: '属性名称', required: true, placeholder: '如 所属部门' },
      { name: 'attrType', label: '属性类型', type: 'select', options: TYPES },
      { name: 'validationRegex', label: '校验正则', placeholder: '如 ^[a-z0-9-]{2,32}$', mono: true },
      {
        name: 'enumOptions',
        label: '枚举选项',
        type: 'textarea',
        full: true,
        placeholder: '仅 ENUM 类型需要,逗号分隔:研发,产品,运营',
      },
      { name: 'required', label: '必填', type: 'switch', onText: '必填' },
      { name: 'searchable', label: '可检索', type: 'switch', onText: '建立索引' },
      { name: 'uniqueFlag', label: '唯一', type: 'switch', onText: '全局唯一' },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    editFields: [
      { name: 'attrName', label: '属性名称', required: true },
      { name: 'attrType', label: '属性类型', type: 'select', options: TYPES },
      { name: 'validationRegex', label: '校验正则', mono: true },
      { name: 'enumOptions', label: '枚举选项', type: 'textarea', full: true },
      { name: 'required', label: '必填', type: 'switch', onText: '必填' },
      { name: 'searchable', label: '可检索', type: 'switch', onText: '建立索引' },
      { name: 'uniqueFlag', label: '唯一', type: 'switch', onText: '全局唯一' },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    defaults: { attrType: 'STRING', required: false, searchable: false, uniqueFlag: false },
    fromRow: (row) => ({
      attrName: row.attrName,
      attrType: row.attrType,
      validationRegex: row.validationRegex,
      enumOptions: row.enumOptions,
      required: Boolean(row.required),
      searchable: Boolean(row.searchable),
      uniqueFlag: Boolean(row.uniqueFlag),
      description: row.description,
    }),
  },
};
</script>

<template>
  <ResourcePage :config="config" />
</template>
