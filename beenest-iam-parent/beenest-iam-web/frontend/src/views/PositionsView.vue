<script setup lang="ts">
import { ref } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import { api, errorMessage } from '@/lib/api';
import { fmtTime } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub } from '@/lib/ui';

/* ---------------- 岗位角色 ---------------- */

const roleOpen = ref(false);
const roleTarget = ref<any>(null);
const roleLoading = ref(false);
const roleSaving = ref(false);
const roleError = ref('');
const roleItems = ref<any[]>([]);
const roleSelected = ref<Array<string | number>>([]);

async function openRoles(row: any) {
  roleTarget.value = row;
  roleOpen.value = true;
  roleLoading.value = true;
  roleError.value = '';
  roleItems.value = [];
  roleSelected.value = [];
  try {
    const [page, assigned] = await Promise.all([
      api<any>('/api/v1/admin/roles', { query: { pageNum: 1, pageSize: 200 }, silent: true }),
      api<any[]>(`/api/v1/admin/positions/${row.id}/roles`, { silent: true }),
    ]);
    roleItems.value = (page?.records || []).map((role: any) => ({
      id: role.id,
      label: role.roleName,
      hint: role.roleCode,
      group: role.containerType === 'CLIENT' ? '客户端角色' : '域角色',
      tags: role.isActive === false ? [{ text: '已停用', tone: 'is-danger' }] : [],
    }));
    roleSelected.value = (assigned || []).map((role: any) => role.id);
  } catch (error) {
    roleError.value = errorMessage(error);
  } finally {
    roleLoading.value = false;
  }
}

async function saveRoles(ids: Array<string | number>) {
  if (!roleTarget.value) return;
  roleSaving.value = true;
  roleError.value = '';
  try {
    await api(`/api/v1/admin/positions/${roleTarget.value.id}/roles`, {
      method: 'PUT',
      body: { roleIds: ids },
      silent: true,
    });
    toast('岗位角色已更新,任职该岗位的用户重新登录后生效', 'success');
    roleOpen.value = false;
  } catch (error) {
    roleError.value = errorMessage(error);
  } finally {
    roleSaving.value = false;
  }
}

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '岗位',
  desc: '岗位是人事语义的职务,角色是授权语义的权限集合;岗位挂载角色后,任该岗位者即继承这些角色。',
  endpoint: '/api/v1/admin/positions',
  permCreate: 'position:create',
  deleteName: '岗位',
  createLabel: '新建岗位',
  rowName: (row) => row.positionName,
  filters: [
    { name: 'positionCode', label: '岗位编码', placeholder: '支持模糊匹配' },
    { name: 'positionName', label: '岗位名称', placeholder: '支持模糊匹配' },
  ],
  columns: [
    {
      label: '岗位',
      render: (row) =>
        strongSub(`${row.positionName ?? '—'}${row.positionLevel ? ` · ${row.positionLevel}` : ''}`, row.positionCode),
    },
    { label: '岗位编码', render: (row) => mono(row.positionCode) },
    {
      label: '携带角色',
      align: 'right',
      render: (row) =>
        row.roleCount > 0
          ? badge(`${row.roleCount} 个角色`, 'is-gold', true)
          : badge('未挂载角色', '', true),
    },
    {
      label: '排序',
      align: 'right',
      render: (row) => muted(String(row.sortOrder ?? 0)),
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'position:update', onClick: () => ctx?.openEditor(row) },
    { label: '岗位角色', perm: 'position:assign-role', onClick: () => openRoles(row) },
    { label: '删除', perm: 'position:delete', danger: true, onClick: () => ctx?.remove(row) },
  ],
  form: {
    title: '岗位',
    subtitle: '岗位编码创建后不可修改',
    fields: [
      { name: 'positionCode', label: '岗位编码', required: true, mono: true, placeholder: '如 dept-manager' },
      { name: 'positionName', label: '岗位名称', required: true, placeholder: '如 部门经理' },
      { name: 'positionLevel', label: '职级/序列', placeholder: '如 M2 / P7', help: '仅用于展示,不参与鉴权' },
      { name: 'sortOrder', label: '排序号', type: 'number', placeholder: '升序排列' },
    ],
    editFields: [
      { name: 'positionName', label: '岗位名称', required: true },
      { name: 'positionLevel', label: '职级/序列', placeholder: '如 M2 / P7' },
      { name: 'sortOrder', label: '排序号', type: 'number' },
    ],
    fromRow: (row) => ({
      positionName: row.positionName,
      positionLevel: row.positionLevel ?? '',
      sortOrder: row.sortOrder ?? 0,
    }),
    toUpdate: (values) => ({
      positionName: values.positionName,
      positionLevel: values.positionLevel ?? '',
      sortOrder: Number(values.sortOrder ?? 0),
    }),
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <AssignmentModal
    v-model:open="roleOpen"
    :title="`岗位角色 · ${roleTarget?.positionName || ''}`"
    subtitle="任职该岗位的用户继承这些角色"
    :items="roleItems"
    :model-value="roleSelected"
    :loading="roleLoading"
    :saving="roleSaving"
    :error="roleError"
    alert="停用中的角色不参与权限计算;保存后任职该岗位的用户重新登录生效。"
    search-placeholder="按角色名称或编码过滤…"
    save-label="保存角色"
    @save="saveRoles"
  />
</template>
