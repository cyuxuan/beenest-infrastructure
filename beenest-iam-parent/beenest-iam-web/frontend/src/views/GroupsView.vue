<script setup lang="ts">
import { computed, h, ref } from 'vue';
import ResourcePage from '@/components/ResourcePage.vue';
import AssignmentModal from '@/components/AssignmentModal.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import DetailList from '@/components/DetailList.vue';
import { api, errorMessage } from '@/lib/api';
import { fmtTime } from '@/lib/format';
import { toast } from '@/lib/toast';
import type { FilterOption, ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub } from '@/lib/ui';

const USER_STATUS: Record<number, { text: string; tone: 'is-success' | 'is-danger' | 'is-warn' | '' }> = {
  0: { text: '禁用', tone: 'is-danger' },
  1: { text: '正常', tone: 'is-success' },
  2: { text: '锁定', tone: 'is-warn' },
};

const GROUP_TYPE: Record<string, { text: string; tone: 'is-gold' | '' }> = {
  DEPARTMENT: { text: '部门', tone: 'is-gold' },
  GROUP: { text: '用户组', tone: '' },
};

/* ---------------- 组树 ---------------- */

/** 树 → 摊平行(带层级深度),父组一定排在子组之前。 */
function flattenTree(nodes: any[], depth = 0, out: any[] = []): any[] {
  for (const node of nodes || []) {
    out.push({ ...node, depth });
    flattenTree(node.children || [], depth + 1, out);
  }
  return out;
}

/** 摊平后补上父组名:顺着走一遍即可,父组总在子组之前。 */
function withParentNames(rows: any[]): any[] {
  const names = new Map<number, string>();
  for (const row of rows) names.set(Number(row.id), row.groupName);
  return rows.map((row) => ({
    ...row,
    parentName: row.parentId ? names.get(Number(row.parentId)) || '' : '',
  }));
}

/** 接口返回 → 摊平的表格行。 */
function toGroupRows(raw: any): any[] {
  return withParentNames(flattenTree(Array.isArray(raw) ? raw : []));
}

/** 收集节点及其全部子孙的主键(编辑时用来排除自己的下级)。 */
function collectIds(nodes: any[]): number[] {
  const ids: number[] = [];
  for (const node of nodes || []) {
    ids.push(Number(node.id));
    ids.push(...collectIds(node.children || []));
  }
  return ids;
}

/** 上级组候选项:按层级缩进,一眼看得出归属。 */
async function loadGroupOptions(excludeIds: number[] = []): Promise<FilterOption[]> {
  try {
    const tree = await api<any[]>('/api/v1/admin/groups/tree', { silent: true });
    const excluded = new Set(excludeIds.map(Number));
    return toGroupRows(tree)
      .filter((row) => !excluded.has(Number(row.id)))
      .map((row) => ({
        value: row.id,
        label: `${'\u3000'.repeat(row.depth || 0)}${row.groupName}(${row.groupCode})`,
      }));
  } catch {
    return [];
  }
}

/** 用户候选项(部门负责人 / 成员任职共用)。 */
async function loadUserOptions(): Promise<FilterOption[]> {
  try {
    const page = await api<any>('/api/v1/admin/users', {
      query: { pageNum: 1, pageSize: 500 },
      silent: true,
    });
    return (page?.records || []).map((user: any) => ({
      value: user.id,
      label: `${user.nickname || user.username}(${user.username})`,
    }));
  } catch {
    return [];
  }
}

/** 岗位候选项(成员任职时选择"在这个部门担任什么岗位")。 */
async function loadPositionOptions(): Promise<FilterOption[]> {
  try {
    const page = await api<any>('/api/v1/admin/positions', {
      query: { pageNum: 1, pageSize: 200 },
      silent: true,
    });
    return (page?.records || []).map((position: any) => ({
      value: position.id,
      label: `${position.positionName}${position.positionLevel ? ` · ${position.positionLevel}` : ''}(${position.positionCode})`,
    }));
  } catch {
    return [];
  }
}

/* ---------------- 详情 ---------------- */

const detailOpen = ref(false);
const detailRow = ref<any>(null);

function openDetail(row: any) {
  detailRow.value = row;
  detailOpen.value = true;
}

/* ---------------- 成员任职分配 ---------------- */

const memberOpen = ref(false);
const memberTarget = ref<any>(null);
const memberLoading = ref(false);
const memberSaving = ref(false);
const memberError = ref('');
const memberKeyword = ref('');
/** 候选用户(全量,前端过滤) */
const memberCandidates = ref<any[]>([]);
/** 岗位选项 */
const memberPositions = ref<FilterOption[]>([]);
/** 任职行:一个成员一行,带岗位与主部门标记 */
const memberRows = ref<Array<{ userId: number; positionId: number | null; primary: boolean }>>([]);

const memberSelected = computed(() => new Set(memberRows.value.map((row) => row.userId)));

const filteredCandidates = computed(() => {
  const keyword = memberKeyword.value.trim().toLowerCase();
  if (!keyword) return memberCandidates.value;
  return memberCandidates.value.filter((user) =>
    `${user.username}${user.nickname || ''}`.toLowerCase().includes(keyword),
  );
});

async function openMembers(row: any) {
  memberTarget.value = row;
  memberOpen.value = true;
  memberLoading.value = true;
  memberError.value = '';
  memberKeyword.value = '';
  memberRows.value = [];
  try {
    const [users, positions, members] = await Promise.all([
      api<any>('/api/v1/admin/users', { query: { pageNum: 1, pageSize: 500 }, silent: true }),
      api<any>('/api/v1/admin/positions', { query: { pageNum: 1, pageSize: 200 }, silent: true }),
      api<any>(`/api/v1/admin/groups/${row.id}/members`, {
        query: { pageNum: 1, pageSize: 500 },
        silent: true,
      }),
    ]);
    memberCandidates.value = users?.records || [];
    memberPositions.value = (positions?.records || []).map((position: any) => ({
      value: position.id,
      label: `${position.positionName}${position.positionLevel ? ` · ${position.positionLevel}` : ''}(${position.positionCode})`,
    }));
    // 已有任职原样回填:只带主键集合会把岗位与主部门标记丢掉
    memberRows.value = (members?.records || []).map((item: any) => ({
      userId: Number(item.userId),
      positionId: item.positionId == null ? null : Number(item.positionId),
      primary: Boolean(item.primary),
    }));
  } catch (error) {
    memberError.value = errorMessage(error);
  } finally {
    memberLoading.value = false;
  }
}

function candidateOf(userId: number): any {
  return memberCandidates.value.find((user) => Number(user.id) === Number(userId));
}

function toggleMember(userId: number, checked: boolean) {
  if (checked) {
    if (!memberSelected.value.has(userId)) {
      // 组内第一个成员默认为主部门,避免出现"人人都没有主部门"的空档
      memberRows.value.push({
        userId,
        positionId: null,
        primary: memberRows.value.length === 0,
      });
    }
    return;
  }
  const removed = memberRows.value.filter((row) => row.userId !== userId);
  // 主部门被移出后补位给剩下第一个,保证"至多一个且尽量有一个"
  if (!removed.some((row: any) => row.primary) && removed.length > 0) {
    removed[0] = { ...removed[0], primary: true };
  }
  memberRows.value = removed;
}

function setPrimary(userId: number) {
  memberRows.value = memberRows.value.map((row) => ({ ...row, primary: row.userId === userId }));
}

async function saveMembers() {
  if (!memberTarget.value) return;
  memberSaving.value = true;
  memberError.value = '';
  try {
    await api(`/api/v1/admin/groups/${memberTarget.value.id}/members`, {
      method: 'PUT',
      body: {
        members: memberRows.value.map((row) => ({
          userId: row.userId,
          positionId: row.positionId,
          primary: row.primary,
        })),
      },
      silent: true,
    });
    toast('组织成员与任职已更新', 'success');
    memberOpen.value = false;
  } catch (error) {
    memberError.value = errorMessage(error);
  } finally {
    memberSaving.value = false;
  }
}

/* ---------------- 含下级成员(只读) ---------------- */

const subOpen = ref(false);
const subTarget = ref<any>(null);
const subLoading = ref(false);
const subError = ref('');
const subRows = ref<any[]>([]);

async function openSubMembers(row: any) {
  subTarget.value = row;
  subOpen.value = true;
  subLoading.value = true;
  subError.value = '';
  subRows.value = [];
  try {
    const page = await api<any>(`/api/v1/admin/groups/${row.id}/members`, {
      query: { pageNum: 1, pageSize: 200, includeSub: true },
      silent: true,
    });
    subRows.value = page?.records || [];
  } catch (error) {
    subError.value = errorMessage(error);
  } finally {
    subLoading.value = false;
  }
}

/* ---------------- 角色分配 ---------------- */

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
      api<any[]>(`/api/v1/admin/groups/${row.id}/roles`, { silent: true }),
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
    await api(`/api/v1/admin/groups/${roleTarget.value.id}/roles`, {
      method: 'PUT',
      body: { roleIds: ids },
      silent: true,
    });
    toast('组角色已更新,成员重新登录后生效', 'success');
    roleOpen.value = false;
  } catch (error) {
    roleError.value = errorMessage(error);
  } finally {
    roleSaving.value = false;
  }
}

/* ---------------- 配置 ---------------- */

const config: ResourceConfig = {
  title: '组织架构',
  desc: '部门与用户组共用一棵树:部门承载任职与负责人,组角色随成员参与权限计算。调整组织即批量调整权限。',
  endpoint: '/api/v1/admin/groups/tree',
  // 组按层级返回,摊平成带缩进的行;筛选由后端做(命中节点连同祖先一起返回),
  // 前端再筛一遍会把祖先筛掉,层级就断了
  paged: false,
  clientFilter: false,
  transform: toGroupRows,
  permCreate: 'group:create',
  createLabel: '新建组织单元',
  deleteName: '组织单元',
  rowName: (row) => row.groupName,
  cardTitle: '组织架构',
  filters: [
    { name: 'groupCode', label: '编码', placeholder: '支持模糊匹配' },
    { name: 'groupName', label: '名称', placeholder: '支持模糊匹配' },
  ],
  columns: [
    {
      label: '组织单元',
      render: (row) =>
        h('div', { class: 'cell-stack', style: { paddingLeft: `${(row.depth || 0) * 18}px` } }, [
          h('div', { class: 'cell-strong' }, String(row.groupName ?? '—')),
          h('div', { class: 'cell-sub' }, String(row.groupCode ?? '—')),
        ]),
    },
    {
      label: '类型',
      render: (row) => {
        const type = GROUP_TYPE[row.groupType] || GROUP_TYPE.GROUP;
        return badge(type.text, type.tone, true);
      },
    },
    {
      label: '上级',
      render: (row) => (row.parentId ? muted(row.parentName || `#${row.parentId}`) : badge('顶级', '', true)),
    },
    {
      label: '负责人',
      render: (row) => (row.leaderName ? strongSub(row.leaderName) : muted('未设置')),
    },
    {
      label: '成员数',
      align: 'right',
      render: (row) => h('span', { class: 'cell-strong' }, String(row.memberCount ?? 0)),
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '详情', onClick: () => openDetail(row) },
    { label: '编辑', perm: 'group:update', onClick: () => ctx?.openEditor(row) },
    { label: '成员任职', perm: 'group:assign-user', onClick: () => openMembers(row) },
    { label: '含下级成员', perm: 'group:view', onClick: () => openSubMembers(row) },
    { label: '角色', perm: 'group:assign-role', onClick: () => openRoles(row) },
    { label: '删除', perm: 'group:delete', danger: true, onClick: () => ctx?.remove(row) },
  ],
  rowClick: (row) => openDetail(row),
  form: {
    title: '组织单元',
    subtitle: '编码创建后不可修改',
    fields: async () => [
      { name: 'groupCode', label: '编码', required: true, mono: true, placeholder: '如 rd-center' },
      { name: 'groupName', label: '名称', required: true, placeholder: '如 研发中心' },
      {
        name: 'groupType',
        label: '类型',
        type: 'select',
        defaultValue: 'DEPARTMENT',
        options: [
          { value: 'DEPARTMENT', label: '部门(组织架构节点)' },
          { value: 'GROUP', label: '用户组(任意集合)' },
        ],
        help: '部门可用于任职与负责人;两者共用同一棵树,授权方式一致',
      },
      {
        name: 'leaderUserId',
        label: '部门负责人',
        type: 'select',
        placeholder: '不选表示未设置',
        options: await loadUserOptions(),
        help: '仅作展示与流程指派,不自带任何权限',
      },
      { name: 'sortOrder', label: '排序号', type: 'number', placeholder: '升序排列' },
      {
        name: 'parentId',
        label: '上级',
        type: 'select',
        full: true,
        placeholder: '不选表示顶级',
        options: await loadGroupOptions(),
      },
    ],
    editFields: async (_isEdit, row) => {
      // 排除自己与自己的全部下级:挂到下级上会形成环,
      // 一旦成环,层级遍历就绕不回来了
      const exclude = [Number(row?.id), ...collectIds(row?.children || [])].filter(Boolean);
      return [
        { name: 'groupName', label: '名称', required: true },
        {
          name: 'groupType',
          label: '类型',
          type: 'select',
          options: [
            { value: 'DEPARTMENT', label: '部门(组织架构节点)' },
            { value: 'GROUP', label: '用户组(任意集合)' },
          ],
        },
        {
          name: 'leaderUserId',
          label: '部门负责人',
          type: 'select',
          placeholder: '留空表示清空负责人',
          options: await loadUserOptions(),
          omitWhenEmpty: true,
        },
        { name: 'sortOrder', label: '排序号', type: 'number' },
        {
          name: 'parentId',
          label: '上级',
          type: 'select',
          full: true,
          placeholder: '留空表示提升为顶级',
          options: await loadGroupOptions(exclude),
          help: '可调整到其他组织单元;留空即把本节点提升为顶级',
        },
      ];
    },
    fromRow: (row) => ({
      groupName: row.groupName,
      groupType: row.groupType || 'GROUP',
      leaderUserId: row.leaderUserId ?? '',
      sortOrder: row.sortOrder ?? 0,
      parentId: row.parentId ?? '',
    }),
    toCreate: (values) => ({
      groupCode: values.groupCode,
      groupName: values.groupName,
      groupType: values.groupType || 'GROUP',
      leaderUserId: values.leaderUserId ? Number(values.leaderUserId) : null,
      sortOrder: Number(values.sortOrder ?? 0),
      parentId: values.parentId ? Number(values.parentId) : null,
    }),
    toUpdate: (values) => ({
      groupName: values.groupName,
      groupType: values.groupType || 'GROUP',
      // 负责人留空 = 清空。负责人在接口语义里为 null 表示"不调整",
      // 清空意图必须用开关表达,否则负责人再也撤不下来
      leaderUserId: values.leaderUserId ? Number(values.leaderUserId) : null,
      clearLeader: !values.leaderUserId,
      sortOrder: Number(values.sortOrder ?? 0),
      parentId: values.parentId ? Number(values.parentId) : null,
      clearParent: !values.parentId,
    }),
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseDrawer v-model:open="detailOpen" :title="detailRow?.groupName || '组织单元详情'" subtitle="组织归属与继承关系">
    <template v-if="detailRow">
      <div class="group-head">
        <span class="group-mark">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12 3l8.5 4.5L12 12 3.5 7.5z" />
            <path d="M4 12.2l8 4.3 8-4.3" />
            <path d="M4 16.7l8 4.3 8-4.3" />
          </svg>
        </span>
        <div>
          <div class="group-name">{{ detailRow.groupName }}</div>
          <div class="cell-mono">{{ detailRow.groupCode }}</div>
        </div>
        <span class="badge is-gold group-count">{{ detailRow.memberCount ?? 0 }} 名成员</span>
      </div>
      <DetailList
        :rows="[
          { label: '组织主键', value: detailRow.id, mono: true },
          { label: '类型', value: (GROUP_TYPE[detailRow.groupType] || GROUP_TYPE.GROUP).text },
          { label: '上级', value: detailRow.parentId ? (detailRow.parentName || `#${detailRow.parentId}`) : '顶级' },
          { label: '层级路径', value: detailRow.path || detailRow.groupName },
          { label: '编码', value: detailRow.groupCode, mono: true },
          { label: '名称', value: detailRow.groupName },
          { label: '负责人', value: detailRow.leaderName || '未设置' },
          { label: '成员数', value: detailRow.memberCount ?? 0 },
          { label: '创建时间', value: fmtTime(detailRow.createdTime) },
        ]"
      />
      <div class="alert is-info" style="margin-top: 18px">
        <span>
          组角色随成员参与权限计算,成员在本部门的任职岗位同样携带角色。
          层级只表达归属,子部门成员不继承上级组织的角色。
        </span>
      </div>
    </template>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="detailOpen = false">关闭</button>
    </template>
  </BaseDrawer>

  <!-- 成员任职:谁、任什么岗位、是否主部门 -->
  <BaseDrawer
    v-model:open="memberOpen"
    :title="`成员任职 · ${memberTarget?.groupName || ''}`"
    subtitle="勾选加入组织,逐人指定岗位与主部门"
  >
    <div v-if="memberLoading" class="drawer-hint">加载中…</div>
    <template v-else>
      <div class="member-search">
        <input v-model="memberKeyword" class="input" type="search" placeholder="按登录名或昵称过滤…" />
        <span class="muted">{{ memberRows.length }} 名成员</span>
      </div>

      <div v-if="memberError" class="alert is-danger">{{ memberError }}</div>

      <div class="member-candidates">
        <label v-for="user in filteredCandidates" :key="user.id" class="member-candidate">
          <input
            type="checkbox"
            :checked="memberSelected.has(Number(user.id))"
            @change="toggleMember(Number(user.id), ($event.target as HTMLInputElement).checked)"
          />
          <span class="member-name">{{ user.nickname || user.username }}</span>
          <span class="muted">{{ user.username }}</span>
          <span
            v-if="USER_STATUS[user.status]"
            class="badge"
            :class="USER_STATUS[user.status].tone"
          >{{ USER_STATUS[user.status].text }}</span>
        </label>
      </div>

      <div v-if="memberRows.length" class="member-assign">
        <div class="member-assign-head">
          <span>任职明细</span>
          <span class="muted">岗位携带的角色随任职生效;主部门至多一个</span>
        </div>
        <div v-for="row in memberRows" :key="row.userId" class="member-assign-row">
          <div class="member-assign-who">
            <strong>{{ candidateOf(row.userId)?.nickname || candidateOf(row.userId)?.username || `#${row.userId}` }}</strong>
            <span class="muted">{{ candidateOf(row.userId)?.username }}</span>
          </div>
          <select v-model="row.positionId" class="select">
            <option :value="null">不任职岗位</option>
            <option v-for="option in memberPositions" :key="option.value" :value="Number(option.value)">
              {{ option.label }}
            </option>
          </select>
          <label class="member-primary">
            <input type="radio" name="primary-dept" :checked="row.primary" @change="setPrimary(row.userId)" />
            <span>主部门</span>
          </label>
          <button class="btn btn-ghost btn-sm" type="button" @click="toggleMember(row.userId, false)">移出</button>
        </div>
      </div>
      <div v-else class="drawer-hint">尚未选择成员。</div>
    </template>

    <template #footer>
      <button class="btn btn-ghost" type="button" @click="memberOpen = false">取消</button>
      <button class="btn btn-primary" type="button" :disabled="memberSaving" @click="saveMembers">
        {{ memberSaving ? '保存中…' : '保存任职' }}
      </button>
    </template>
  </BaseDrawer>

  <!-- 含下级成员:只读取数视图 -->
  <BaseDrawer
    v-model:open="subOpen"
    :title="`含下级成员 · ${subTarget?.groupName || ''}`"
    subtitle="本部门及全部下级部门的成员"
  >
    <div v-if="subLoading" class="drawer-hint">加载中…</div>
    <div v-else-if="subError" class="alert is-danger">{{ subError }}</div>
    <div v-else-if="!subRows.length" class="drawer-hint">该组织及其下级暂无成员。</div>
    <div v-else class="sub-list">
      <div v-for="item in subRows" :key="`${item.userId}-${item.groupId}`" class="sub-row">
        <div class="member-assign-who">
          <strong>{{ item.nickname || item.username }}</strong>
          <span class="muted">{{ item.username }}</span>
        </div>
        <span class="muted">{{ item.groupName }}</span>
        <span>{{ item.positionName ? badge(item.positionName, 'is-gold', true) : muted('未任职') }}</span>
        <span>{{ item.primary ? badge('主部门', 'is-gold', true) : muted('兼职') }}</span>
      </div>
    </div>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="subOpen = false">关闭</button>
    </template>
  </BaseDrawer>

  <AssignmentModal
    v-model:open="roleOpen"
    :title="`组角色 · ${roleTarget?.groupName || ''}`"
    subtitle="组内所有成员继承这些角色"
    :items="roleItems"
    :model-value="roleSelected"
    :loading="roleLoading"
    :saving="roleSaving"
    :error="roleError"
    alert="停用中的角色不参与权限计算;保存后组内成员重新登录生效。"
    search-placeholder="按角色名称或编码过滤…"
    save-label="保存角色"
    @save="saveRoles"
  />
</template>

<style scoped>
.group-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.08));
}

.group-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  color: var(--gold-deep);
  background: var(--gold-soft);
  border: 1px solid rgba(168, 115, 10, 0.22);
}

.group-name {
  font-size: 15px;
  font-weight: 680;
  color: var(--ink);
}

.group-count {
  margin-left: auto;
}

.drawer-hint {
  padding: 18px 0;
  color: var(--ink-3);
  font-size: 13px;
}

.member-search {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.member-candidates {
  max-height: 220px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 8px;
}

.member-candidate {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
}

.member-candidate:hover {
  background: var(--surface-2);
}

.member-name {
  font-weight: 600;
  color: var(--ink);
}

.member-assign {
  margin-top: 18px;
}

.member-assign-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-weight: 650;
  color: var(--ink);
}

.member-assign-head .muted {
  font-weight: 400;
  font-size: 12px;
}

.member-assign-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(140px, 1.1fr) auto auto;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid var(--line);
}

.member-assign-who {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.member-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.sub-list {
  display: flex;
  flex-direction: column;
}

.sub-row {
  display: grid;
  grid-template-columns: minmax(120px, 1.2fr) minmax(100px, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-top: 1px solid var(--line);
}
</style>
