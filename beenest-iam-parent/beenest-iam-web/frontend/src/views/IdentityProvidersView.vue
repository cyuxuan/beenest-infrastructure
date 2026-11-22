<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import BaseModal from '@/components/BaseModal.vue';
import DetailList from '@/components/DetailList.vue';
import ResourcePage from '@/components/ResourcePage.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage, providerTypes } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { fmtTime } from '@/lib/format';
import { buildIdpCreatePayload, buildIdpUpdatePayload } from '@/lib/idpForm';
import { toast } from '@/lib/toast';
import type { FieldDef, ProviderConfigProperty, ProviderTypeInfo, ResourceConfig } from '@/lib/types';
import { badge, dotBadge, muted, strongSub } from '@/lib/ui';
import { can } from '@/stores/auth';

/** 已注册的身份提供方类型:由容器里的扩展包自报,引入即出现。 */
const providerTypeList = ref<ProviderTypeInfo[]>([]);

onMounted(async () => {
  try {
    providerTypeList.value = await providerTypes();
  } catch (error) {
    toast(errorMessage(error), 'error');
  }
});

/** 配置项类型 → 表单控件类型。 */
const CONFIG_FIELD_TYPE: Record<ProviderConfigProperty['type'], FieldDef['type']> = {
  STRING: 'text',
  TEXT: 'textarea',
  PASSWORD: 'password',
  NUMBER: 'number',
  BOOLEAN: 'switch',
  URL: 'text',
};

/**
 * 某类型声明的配置项 → 表单字段。
 * <p>字段名带 config. 前缀,与顶层字段(别名、展示名等)分开,提交时再收拢成配置对象。</p>
 */
function configFields(type?: ProviderTypeInfo): FieldDef[] {
  if (!type) return [];
  return type.configProperties.map((property) => ({
    name: `config.${property.name}`,
    label: property.label,
    type: CONFIG_FIELD_TYPE[property.type] ?? 'text',
    required: property.required,
    help: property.helpText ?? undefined,
    placeholder: property.defaultValue ?? undefined,
    full: property.type === 'TEXT',
  }));
}

/**
 * 表单字段:按当前选中的提供方类型给出配置项。
 * <p>类型下拉与配置项联动:改类型,配置项立刻换成新类型声明的那些字段。</p>
 */
function formFields(isEdit: boolean, row?: any, values?: Record<string, any>): FieldDef[] {
  const providerId =
    values?.providerId || (isEdit && row ? row.providerId : providerTypeList.value[0]?.providerId) || '';
  const type = providerTypeList.value.find((item) => item.providerId === providerId);
  return [
    ...(isEdit
      ? []
      : [{ name: 'alias', label: '别名', required: true, mono: true, placeholder: '如 wx-mini' }]),
    { name: 'displayName', label: '展示名', required: true, placeholder: '如 微信小程序' },
    {
      name: 'providerId',
      label: '提供方类型',
      type: 'select',
      required: true,
      // 类型创建后不可改:后端的更新接口不收这个字段,这里禁掉,
      // 免得改了看着生效了、其实没生效
      disabled: isEdit,
      options: providerTypeList.value.map((item) => ({ value: item.providerId, label: item.name })),
      help: '由容器内已注册的扩展包决定:引入扩展包即出现,移除即下线',
    },
    { name: 'enabled', label: '启用', type: 'switch', onText: '启用中' },
    ...configFields(type),
  ];
}

const PROVIDER_LABEL: Record<string, string> = {
  oidc: 'OIDC',
  wechat: '微信',
  alipay: '支付宝',
  feishu: '飞书',
};

/** 提供方类型展示名:优先用服务端自报的名称。 */
function providerTypeName(providerId: string): string {
  return (
    providerTypeList.value.find((item) => item.providerId === providerId)?.name ||
    PROVIDER_LABEL[providerId] ||
    providerId ||
    '—'
  );
}

/** 配置项标签:优先用该类型声明里的标签。 */
function configLabel(providerId: string, key: string): string {
  const type = providerTypeList.value.find((item) => item.providerId === providerId);
  return type?.configProperties.find((property) => property.name === key)?.label || key;
}

const MAPPER_TYPES = [
  { value: 'ATTRIBUTE', label: 'ATTRIBUTE 属性' },
  { value: 'ROLE', label: 'ROLE 角色' },
];

/* ---------------- 详情 ---------------- */

const detailOpen = ref(false);
const detailRow = ref<any>(null);

function openDetail(row: any) {
  detailRow.value = row;
  detailOpen.value = true;
}

/* ---------------- 属性映射 ---------------- */

const mapperOpen = ref(false);
const mapperTarget = ref<any>(null);
const mappers = ref<any[]>([]);
const mapperLoading = ref(false);
const mapperError = ref('');
const mapperBusy = ref(false);
const mapperFormKey = ref(0);
const mapperForm = ref<InstanceType<typeof SchemaForm> | null>(null);

const mapperFields: FieldDef[] = [
  {
    name: 'mapperType',
    label: '映射类型',
    type: 'select',
    options: MAPPER_TYPES,
    help: '属性映射:把 IdP 返回的属性搬进本地账号扩展属性;角色映射:外部角色命中时自动授予本地角色',
  },
  {
    name: 'sourceAttribute',
    label: '外部属性名 / 外部角色名',
    required: true,
    mono: true,
    placeholder: '如 mail,或外部角色名 admin',
    help: '属性映射填 IdP 返回的 claim 名;角色映射填外部角色名(外部角色集合取自 userinfo 的 roles,缺失时取 groups)',
  },
  {
    name: 'targetAttribute',
    label: '本地属性键 / 本地角色编码',
    required: true,
    mono: true,
    full: true,
    placeholder: '如 email,或本地角色编码 STAFF',
    help: '属性映射填本地扩展属性键;角色映射填本地域级角色编码(只认域角色,应用角色不在此授权)',
  },
];

async function loadMappers() {
  if (!mapperTarget.value) return;
  mapperLoading.value = true;
  mapperError.value = '';
  try {
    mappers.value =
      (await api<any[]>(`/api/v1/admin/identity-providers/${mapperTarget.value.id}/mappers`, {
        silent: true,
      })) || [];
  } catch (error) {
    mappers.value = [];
    mapperError.value = errorMessage(error);
  } finally {
    mapperLoading.value = false;
  }
}

function openMappers(row: any) {
  mapperTarget.value = row;
  mapperOpen.value = true;
  mappers.value = [];
  mapperFormKey.value += 1;
  void loadMappers();
}

async function addMapper() {
  const form = mapperForm.value;
  if (!form || !mapperTarget.value) return;
  const check = await form.validate();
  if (!check.ok) return;
  mapperBusy.value = true;
  try {
    await api(`/api/v1/admin/identity-providers/${mapperTarget.value.id}/mappers`, {
      method: 'POST',
      body: form.read(),
      silent: true,
    });
    toast('属性映射已添加', 'success');
    mapperFormKey.value += 1;
    await loadMappers();
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    mapperBusy.value = false;
  }
}

async function removeMapper(mapper: any) {
  if (!mapperTarget.value) return;
  const ok = await confirmDialog({
    title: '删除属性映射',
    message: `确认删除「${mapper.sourceAttribute} → ${mapper.targetAttribute}」的映射吗?`,
    detail: '删除后,该映射规则不再执行:外部属性不再写入本地账号,外部角色也不再自动授予本地角色;已同步的数据不会回滚。',
    confirmText: '删除',
  });
  if (!ok) return;
  try {
    await api(
      `/api/v1/admin/identity-providers/${mapperTarget.value.id}/mappers/${mapper.id}`,
      { method: 'DELETE' },
    );
    toast('映射已删除', 'success');
    await loadMappers();
  } catch {
    /* 错误提示由 api 统一处理 */
  }
}

/* ---------------- 配置 ---------------- */

function configCell(row: any) {
  const keys: string[] = row.configKeys || [];
  if (!keys.length) return muted('未配置');
  return h('div', { class: 'cell-stack' }, [
    h('span', { class: 'cell-strong' }, `${keys.length} 项配置`),
    h('div', { class: 'cell-sub' }, keys.slice(0, 3).join(' · ') + (keys.length > 3 ? ' …' : '')),
  ]);
}

const config: ResourceConfig = {
  title: '身份提供方',
  desc: '接入企业已有的 IdP(企业微信、AD、OIDC 服务商),把外部身份联邦为本地账号。当前部署注册通用 OIDC provider。',
  endpoint: '/api/v1/admin/identity-providers',
  permCreate: 'idp:create',
  createLabel: '接入提供方',
  deleteName: '身份提供方',
  rowName: (row) => row.displayName,
  paged: false,
  filters: [
    { name: 'alias', label: '别名', placeholder: '如 corp-oidc' },
    { name: 'displayName', label: '展示名', placeholder: '支持模糊匹配' },
  ],
  columns: [
    { label: '提供方', render: (row) => strongSub(row.displayName, row.alias) },
    {
      label: '类型',
      render: (row) => badge(providerTypeName(row.providerId), 'is-info'),
    },
    { label: '配置项', render: (row) => configCell(row) },
    {
      label: '状态',
      render: (row) =>
        row.enabled ? dotBadge('启用中', 'is-success') : dotBadge('已停用', 'is-danger'),
    },
    { label: '创建时间', render: (row) => muted(fmtTime(row.createdTime)) },
  ],
  rowActions: (row, ctx) => [
    { label: '详情', onClick: () => openDetail(row) },
    { label: '编辑', perm: 'idp:update', onClick: () => ctx.openEditor(row) },
    { label: '属性映射', perm: 'idp-mapper:view', onClick: () => openMappers(row) },
    { label: '删除', perm: 'idp:delete', danger: true, onClick: () => ctx.remove(row) },
  ],
  rowClick: (row) => openDetail(row),
  form: {
    title: '身份提供方',
    subtitle: '别名创建后不可修改;配置项只写不读',
    hint: '敏感配置(clientSecret)加密落库且不可回显:编辑时留空表示保持原值,填写则覆盖;其余配置项填写即整体覆盖,未列出的键会被删除。',
    fields: (isEdit: boolean, row?: any, values?: Record<string, any>) => formFields(isEdit, row, values),
    editFields: (isEdit: boolean, row?: any, values?: Record<string, any>) => formFields(isEdit, row, values),
    defaults: () => ({ providerId: providerTypeList.value[0]?.providerId ?? '', enabled: true }),
    fromRow: (row) => ({
      displayName: row.displayName,
      // 类型必须带回表单:不带的话类型下拉会落到第一个选项上,
      // 保存时就会把别的类型的配置项写进这一行
      providerId: row.providerId,
      enabled: row.enabled !== false,
    }),
    toCreate: (values) => buildIdpCreatePayload(values),
    toUpdate: (values) => buildIdpUpdatePayload(values),
  },
};
</script>

<template>
  <ResourcePage :config="config" />

  <BaseDrawer v-model:open="detailOpen" :title="detailRow?.displayName || '身份提供方'" :subtitle="detailRow?.alias">
    <template v-if="detailRow">
      <div class="idp-head">
        <span class="idp-mark">
          <AppIcon name="idp" :size="21" />
        </span>
        <div>
          <div class="idp-head-name">{{ detailRow.displayName }}</div>
          <div class="idp-head-alias">{{ detailRow.alias }}</div>
        </div>
        <span class="badge is-info">{{ providerTypeName(detailRow.providerId) }}</span>
        <span v-if="detailRow.enabled" class="badge is-success">启用中</span>
        <span v-else class="badge is-danger">已停用</span>
      </div>

      <DetailList
        :rows="[
          { label: '主键', value: detailRow.id, mono: true },
          { label: '别名', value: detailRow.alias, mono: true },
          { label: '提供方类型', value: detailRow.providerId },
          { label: '状态', value: detailRow.enabled ? '启用中' : '已停用' },
          { label: '创建时间', value: fmtTime(detailRow.createdTime) },
        ]"
      />

      <div class="idp-config">
        <div class="idp-config-title">配置项</div>
        <div v-if="!(detailRow.configKeys || []).length" class="idp-config-empty">
          未配置任何连接参数
        </div>
        <div v-else class="idp-config-list">
          <span v-for="key in detailRow.configKeys" :key="key" class="idp-config-chip" :title="key">
            <span>{{ configLabel(detailRow.providerId, key) }}</span>
            <code>{{ key }}</code>
          </span>
        </div>
      </div>

      <div class="alert is-info" style="margin-top: 14px">
        <span>敏感配置只写不读:接口仅返回配置键名,不回显客户端密钥等敏感值。</span>
      </div>
    </template>
    <template #footer>
      <button class="btn btn-ghost" type="button" @click="detailOpen = false">关闭</button>
      <button
        v-if="can('idp-mapper:view') && detailRow"
        class="btn"
        type="button"
        @click="detailOpen = false; openMappers(detailRow)"
      >
        属性映射
      </button>
    </template>
  </BaseDrawer>

  <BaseModal
    v-model:open="mapperOpen"
    size="xl"
    :title="`属性映射 · ${mapperTarget?.displayName || ''}`"
    subtitle="把外部 IdP 返回的属性写入本地账号;未映射的属性不会落地"
  >
    <div v-if="mapperLoading" class="mapper-loading">
      <span class="btn-spinner" />
      <span>正在读取属性映射…</span>
    </div>

    <div v-else-if="mapperError" class="alert is-danger">
      <span>{{ mapperError }}</span>
      <button class="link-btn" type="button" @click="loadMappers">重试</button>
    </div>

    <template v-else>
      <div v-if="!mappers.length" class="mapper-empty">
        <AppIcon name="mapping" :size="22" />
        <span>暂无映射:外部身份登录后只会创建基础账号,不会写入部门、邮箱等扩展属性。</span>
      </div>
      <div v-else class="mapper-list">
        <article v-for="mapper in mappers" :key="mapper.id" class="mapper-item">
          <span class="badge is-outline">{{ mapper.mapperType || 'ATTRIBUTE' }}</span>
          <div class="mapper-flow">
            <span class="mapper-attr">{{ mapper.sourceAttribute }}</span>
            <span class="mapper-arrow" aria-hidden="true">→</span>
            <span class="mapper-attr is-target">{{ mapper.targetAttribute }}</span>
          </div>
          <button
            v-if="can('idp-mapper:delete')"
            class="link-btn is-danger"
            type="button"
            @click="removeMapper(mapper)"
          >
            删除
          </button>
        </article>
      </div>

      <div v-if="can('idp-mapper:create')" class="mapper-add">
        <div class="form-section-title">新增映射</div>
        <SchemaForm
          :key="mapperFormKey"
          ref="mapperForm"
          :fields="mapperFields"
          :values="{ mapperType: 'ATTRIBUTE' }"
          mode="create"
        />
        <div class="mapper-add-foot">
          <button class="btn btn-primary btn-sm" type="button" :disabled="mapperBusy" @click="addMapper">
            <span v-if="mapperBusy" class="btn-spinner" />
            <AppIcon v-else name="plus" :size="14" />
            <span>添加映射</span>
          </button>
        </div>
      </div>
    </template>

    <template #footer="{ close }">
      <button class="btn btn-ghost" type="button" @click="close">关闭</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.idp-head {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 14px;
  margin-bottom: 15px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.08));
}

.idp-mark {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: none;
  border: 1px solid rgba(168, 115, 10, 0.22);
  border-radius: 12px;
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.idp-head-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}

.idp-head-alias {
  margin-top: 2px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--muted);
}

.idp-head > .badge:nth-last-child(2) {
  margin-left: auto;
}

.idp-config {
  margin-top: 16px;
}

.idp-config-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 660;
  color: var(--ink);
}

.idp-config-empty {
  color: var(--muted);
  font-size: 12px;
}

.idp-config-list {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
}

.idp-config-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-2);
  font-size: 11px;
  color: var(--ink-2);
}

.idp-config-chip code {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--faint);
}

.mapper-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 0;
  color: var(--muted);
  font-size: 13px;
}

.mapper-empty {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  margin-bottom: 14px;
  border: 1px dashed var(--line-strong, rgba(16, 24, 40, 0.18));
  border-radius: 12px;
  background: var(--surface-2);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.mapper-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: 16px;
}

.mapper-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-2);
}

.mapper-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.mapper-attr {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mapper-attr.is-target {
  color: var(--gold-deep);
  font-weight: 620;
}

.mapper-arrow {
  color: var(--faint);
  font-size: 12px;
}

.mapper-add {
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

.mapper-add .form-section-title {
  margin-bottom: 10px;
}

.mapper-add-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}
</style>
