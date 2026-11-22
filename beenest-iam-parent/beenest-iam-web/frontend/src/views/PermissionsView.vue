<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import { api } from '@/lib/api';
import { fmtTime } from '@/lib/format';
import type { FilterOption, PageResult, ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub } from '@/lib/ui';

const apps = ref<any[]>([]);
const appFilterOptions = reactive<FilterOption[]>([{ value: '', label: '全部应用' }]);

const appIndex = computed(() => new Map(apps.value.map((app) => [String(app.id), app])));

function appLabel(appServiceId: unknown): string {
  if (appServiceId === null || appServiceId === undefined) return '';
  const app = appIndex.value.get(String(appServiceId));
  return app ? `${app.appName}(${app.appCode})` : `#${appServiceId}`;
}

async function appOptions(): Promise<FilterOption[]> {
  return apps.value.map((app) => ({
    value: String(app.id),
    label: `${app.appName}(${app.appCode})`,
  }));
}

async function loadApps() {
  try {
    const page = await api<PageResult<any>>('/api/v1/admin/application-services', {
      query: { pageNum: 1, pageSize: 300 },
      silent: true,
    });
    apps.value = page?.records || [];
  } catch {
    apps.value = [];
  }
  appFilterOptions.splice(
    1,
    appFilterOptions.length - 1,
    ...apps.value.map((app) => ({
      value: String(app.id),
      label: `${app.appName}(${app.appCode})`,
    })),
  );
}

const config: ResourceConfig = {
  title: '权限',
  desc: '权限码是鉴权的原子单位(如 user:view);平台权限(不归属应用)与应用权限按命名空间隔离,不同应用可复用同一权限码。',
  endpoint: '/api/v1/admin/permissions',
  cardTitle: '权限清单',
  permCreate: 'permission:create',
  createLabel: '新建权限',
  deleteName: '权限',
  rowName: (row) => `${row.permissionName}(${row.permissionCode})`,
  filters: [
    { name: 'permissionCode', label: '权限码', placeholder: '如 user:create' },
    { name: 'permissionName', label: '权限名称', placeholder: '支持模糊匹配' },
    { name: 'resource', label: '资源', placeholder: '如 user / client' },
    { name: 'appServiceId', label: '所属应用', type: 'select', options: appFilterOptions },
  ],
  columns: [
    { label: '权限码', render: (row) => mono(row.permissionCode) },
    { label: '权限名称', render: (row) => strongSub(row.permissionName) },
    {
      label: '所属应用',
      render: (row) =>
        row.appServiceId
          ? badge(appLabel(row.appServiceId), 'is-info', true)
          : badge('平台权限', 'is-gold', true),
    },
    { label: '资源', render: (row) => badge(row.resource || '—', '', true) },
    { label: '动作', render: (row) => badge(row.action || '—') },
    { label: '说明', render: (row) => muted(row.description || '—') },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'permission:update', onClick: () => ctx?.openEditor(row) },
    {
      label: '删除',
      perm: 'permission:delete',
      danger: true,
      onClick: () => ctx?.remove(row),
    },
  ],
  form: {
    title: '权限',
    subtitle: '权限码创建后不可修改;应用权限只在该应用的命名空间内唯一',
    hint: '平台权限可授予域角色;应用权限只能授予所属应用的应用角色/客户端角色,以及域角色。删除前需先解除角色、菜单与用户特批的引用。',
    fields: async () => [
      {
        name: 'appServiceId',
        label: '所属应用',
        type: 'select',
        placeholder: '平台权限(不归属应用)',
        options: await appOptions(),
        full: true,
        help: '留空表示平台权限;选择应用则在该应用命名空间内维护权限码',
      },
      {
        name: 'permissionCode',
        label: '权限编码',
        required: true,
        mono: true,
        placeholder: '如 order:export',
        help: '格式 resource:action,只能包含字母、数字、下划线、中划线、冒号',
      },
      { name: 'permissionName', label: '权限名称', required: true, placeholder: '如 导出订单' },
      { name: 'resource', label: '资源', placeholder: '留空时取冒号前部分' },
      { name: 'action', label: '动作', placeholder: '留空时取冒号后部分' },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    editFields: (_, row) => [
      {
        name: 'appLabel',
        label: '所属应用',
        type: 'static',
        full: true,
      },
      {
        name: 'codeLabel',
        label: '权限编码',
        type: 'static',
        full: true,
      },
      {
        name: 'permissionName',
        label: '权限名称',
        required: true,
        full: true,
        placeholder: row?.permissionName || '',
      },
      { name: 'description', label: '说明', type: 'textarea', full: true },
    ],
    defaults: { appServiceId: '' },
    fromRow: (row) => ({
      appLabel: row.appServiceId ? appLabel(row.appServiceId) : '平台权限(全域生效)',
      codeLabel: row.permissionCode,
      permissionName: row.permissionName,
      description: row.description,
    }),
    validate: (values) => {
      const code = String(values.permissionCode || '').trim();
      if (!/^[A-Za-z0-9:_-]{2,150}$/.test(code)) {
        return '权限编码只能包含字母、数字、下划线、中划线、冒号,长度 2-150';
      }
      const separator = code.indexOf(':');
      if (separator <= 0 || separator === code.length - 1) {
        return '权限编码格式必须为 resource:action';
      }
    },
    toCreate: (values) => ({
      appServiceId: values.appServiceId ? Number(values.appServiceId) : null,
      permissionCode: String(values.permissionCode || '').trim(),
      permissionName: values.permissionName,
      resource: values.resource || undefined,
      action: values.action || undefined,
      description: values.description || '',
    }),
    toUpdate: (values) => ({
      permissionName: values.permissionName,
      description: values.description || '',
    }),
  },
};

void loadApps();
</script>

<template>
  <ResourcePage :config="config" />
</template>
