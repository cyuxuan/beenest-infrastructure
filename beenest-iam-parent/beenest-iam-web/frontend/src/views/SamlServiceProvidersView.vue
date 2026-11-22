<script setup lang="ts">
import { ref } from 'vue';
import BaseModal from '@/components/BaseModal.vue';
import DetailList from '@/components/DetailList.vue';
import ResourcePage from '@/components/ResourcePage.vue';
import { api, errorMessage } from '@/lib/api';
import { toast } from '@/lib/toast';
import type { ResourceConfig } from '@/lib/types';
import { badge, mono, muted, strongSub } from '@/lib/ui';
import { can } from '@/stores/auth';

/** 常用 NameID 格式:企业应用最常要的就是前两种 */
const NAME_ID_FORMATS = [
  { value: 'urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified', label: '未指定(unspecified)' },
  { value: 'urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress', label: '邮箱地址(emailAddress)' },
  { value: 'urn:oasis:names:tc:SAML:2.0:nameid-format:persistent', label: '持久标识(persistent)' },
  { value: 'urn:oasis:names:tc:SAML:2.0:nameid-format:transient', label: '临时标识(transient)' },
];

/* ---------------- 对接信息 ---------------- */

const endpointsOpen = ref(false);
const endpoints = ref<Record<string, string> | null>(null);

/**
 * 拉取本域 IdP 端点并展示。
 * <p>这些地址要交给对端管理员填进他们的 SP 配置,页面上直接给出比让运维拼路径可靠。</p>
 */
async function showEndpoints() {
  try {
    endpoints.value = await api<Record<string, string>>('/api/v1/admin/saml-sp/endpoints');
    endpointsOpen.value = true;
  } catch (error) {
    toast(errorMessage(error), 'error');
  }
}

const config: ResourceConfig = {
  title: 'SAML 应用',
  desc: '登记后可让存量应用以 SAML 2.0 单点登录接入:应用把本平台当身份提供方,本平台按登记签发断言。',
  endpoint: '/api/v1/admin/saml-sp',
  permCreate: 'saml-sp:update',
  permDelete: 'saml-sp:update',
  deleteName: 'SAML 应用',
  rowName: (row) => row.spKey,
  createLabel: '登记应用',
  paged: false,
  emptyText: '还没有登记任何 SAML 应用',
  headerActions: [{ label: '对接信息', icon: 'info', perm: 'saml-sp:view', onClick: showEndpoints }],
  filters: [
    { name: 'spKey', label: '别名', placeholder: '如 oa-system' },
    { name: 'entityId', label: '实体 ID', placeholder: '支持模糊匹配' },
  ],
  columns: [
    { label: '别名', render: (row) => mono(row.spKey) },
    { label: '展示名', render: (row) => strongSub(row.displayName || '—') },
    { label: 'SP 实体 ID', render: (row) => muted(row.entityId) },
    { label: '断言消费地址', render: (row) => muted(row.acsUrl) },
    {
      label: '断言签名',
      render: (row) => (row.signAssertion ? badge('已签名', 'is-gold') : badge('未签名')),
    },
    {
      label: '请求验签',
      render: (row) => (row.requireSignedAuthnRequest ? badge('强制', 'is-gold') : badge('不强制')),
    },
    { label: '状态', render: (row) => (row.enabled ? badge('启用中', 'is-success') : badge('已停用')) },
  ],
  rowActions: (row, ctx) => [
    { label: '编辑', perm: 'saml-sp:update', onClick: () => ctx.openEditor(row) },
    { label: '删除', perm: 'saml-sp:update', danger: true, onClick: () => ctx.remove(row) },
  ],
  form: {
    title: 'SAML 应用',
    subtitle: '实体 ID 与断言消费地址必须与对端配置完全一致,否则断言会被对端拒绝',
    fields: [
      {
        name: 'spKey',
        label: '别名',
        required: true,
        mono: true,
        placeholder: '如 oa-system',
        help: '仅用于平台内识别与日志,不会出现在 SAML 报文里',
      },
      { name: 'displayName', label: '展示名', placeholder: '如 协同办公' },
      {
        name: 'entityId',
        label: 'SP 实体 ID',
        required: true,
        mono: true,
        full: true,
        placeholder: 'https://oa.example.com/saml/metadata',
        help: '对端元数据里的 entityID,断言的受众按它匹配',
      },
      {
        name: 'acsUrl',
        label: '断言消费地址',
        required: true,
        mono: true,
        full: true,
        placeholder: 'https://oa.example.com/saml/acs',
        help: '断言的收件地址,必须与对端配置完全一致',
      },
      {
        name: 'sloUrl',
        label: '单点登出地址',
        mono: true,
        full: true,
        placeholder: 'https://oa.example.com/saml/slo',
        help: '留空表示该应用不支持单点登出',
      },
      {
        name: 'nameIdFormat',
        label: 'NameID 格式',
        type: 'select',
        options: NAME_ID_FORMATS,
        defaultValue: NAME_ID_FORMATS[0].value,
        help: '对端在登录请求里指定格式时以对端为准',
      },
      {
        name: 'signAssertion',
        label: '对断言签名',
        type: 'switch',
        onText: '签名',
        offText: '不签名',
        defaultValue: true,
        help: '关闭后对端无法验证断言来源,仅在对端明确要求时关闭',
      },
      {
        name: 'requireSignedAuthnRequest',
        label: '强制请求验签',
        type: 'switch',
        onText: '强制',
        offText: '不强制',
        help: '开启后对端必须用登记的证书给登录请求签名,否则拒绝',
      },
      {
        name: 'spCertificate',
        label: '对端签名证书',
        type: 'textarea',
        rows: 4,
        mono: true,
        full: true,
        placeholder: '-----BEGIN CERTIFICATE-----',
        help: '强制请求验签时必填;只放证书,不放私钥',
      },
      {
        name: 'attributeMapping',
        label: '属性映射',
        type: 'kv',
        full: true,
        keyPlaceholder: '断言属性名,如 mail',
        valuePlaceholder: '取值,如 email',
        help: '留空时只下发 username / email / displayName 三项。取值可用 username、nickname、email、phone、userId、realm、roles、attr.扩展属性名',
      },
      { name: 'enabled', label: '启用', type: 'switch', onText: '启用中' },
    ],
    defaults: {
      signAssertion: true,
      requireSignedAuthnRequest: false,
      enabled: true,
      nameIdFormat: NAME_ID_FORMATS[0].value,
    },
    // 别名创建后不可改:对端不会用到它,但改动会让日志与审计里的历史记录对不上
    editFields: [
      { name: 'displayName', label: '展示名' },
      {
        name: 'entityId',
        label: 'SP 实体 ID',
        required: true,
        mono: true,
        full: true,
      },
      { name: 'acsUrl', label: '断言消费地址', required: true, mono: true, full: true },
      { name: 'sloUrl', label: '单点登出地址', mono: true, full: true },
      { name: 'nameIdFormat', label: 'NameID 格式', type: 'select', options: NAME_ID_FORMATS },
      { name: 'signAssertion', label: '对断言签名', type: 'switch', onText: '签名', offText: '不签名' },
      {
        name: 'requireSignedAuthnRequest',
        label: '强制请求验签',
        type: 'switch',
        onText: '强制',
        offText: '不强制',
      },
      {
        name: 'spCertificate',
        label: '对端签名证书',
        type: 'textarea',
        rows: 4,
        mono: true,
        full: true,
      },
      { name: 'attributeMapping', label: '属性映射', type: 'kv', full: true },
      { name: 'enabled', label: '启用', type: 'switch', onText: '启用中' },
    ],
    fromRow: (row) => ({
      displayName: row.displayName,
      entityId: row.entityId,
      acsUrl: row.acsUrl,
      sloUrl: row.sloUrl,
      nameIdFormat: row.nameIdFormat,
      signAssertion: Boolean(row.signAssertion),
      requireSignedAuthnRequest: Boolean(row.requireSignedAuthnRequest),
      spCertificate: row.spCertificate,
      attributeMapping: { ...(row.attributeMapping || {}) },
      enabled: Boolean(row.enabled),
    }),
    validate: (values) => {
      if (!String(values.entityId || '').trim()) return '请填写 SP 实体 ID';
      if (!String(values.acsUrl || '').trim()) return '请填写断言消费地址';
      if (values.requireSignedAuthnRequest && !String(values.spCertificate || '').trim()) {
        return '强制请求验签时必须填写对端签名证书';
      }
      return undefined;
    },
  },
};

/** 权限不足时页面不注册按钮,这里只是把入口再挡一层 */
const canView = can('saml-sp:view');
</script>

<template>
  <ResourcePage v-if="canView" :config="config" />

  <BaseModal
    :open="endpointsOpen"
    title="对接信息"
    subtitle="把下列地址填进对端应用的 SAML 配置;对端也可直接导入元数据地址"
    size="lg"
    @close="endpointsOpen = false"
  >
    <DetailList
      :rows="[
        { label: 'IdP 实体 ID', value: endpoints?.entityId, mono: true },
        { label: '单点登录地址 (SSO)', value: endpoints?.ssoUrl, mono: true },
        { label: '单点登出地址 (SLO)', value: endpoints?.sloUrl, mono: true },
        { label: '元数据地址', value: endpoints?.metadataUrl, mono: true },
      ]"
    />
  </BaseModal>
</template>
