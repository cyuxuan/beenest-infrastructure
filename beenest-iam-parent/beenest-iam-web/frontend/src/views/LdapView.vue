<script setup lang="ts">
import { computed, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseModal from '@/components/BaseModal.vue';
import PageHeader from '@/components/PageHeader.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { toast } from '@/lib/toast';
import type { FieldDef } from '@/lib/types';
import { auth, can } from '@/stores/auth';

const loading = ref(true);
const loadError = ref('');
const config = ref<Record<string, any> | null>(null);
const formKey = ref(0);
const busy = ref(false);
const testing = ref(false);
const form = ref<InstanceType<typeof SchemaForm> | null>(null);

interface SyncReport {
  success: boolean;
  dryRun: boolean;
  truncated: boolean;
  scanned: number;
  created: number;
  updated: number;
  unchanged: number;
  skipped: number;
  failed: number;
  durationMs: number;
  samples: string[];
  message: string;
}

const syncOpen = ref(false);
const syncBusy = ref(false);
const syncMax = ref(2000);
const syncReport = ref<SyncReport | null>(null);

const canUpdate = computed(() => can('ldap-config:update'));

const fields = computed<FieldDef[]>(() => {
  const disabled = !canUpdate.value;
  return [
    { name: 'enabled', label: '启用 LDAP 用户联合', type: 'switch', onText: '已启用', disabled, full: true,
      help: '启用后,本地不存在的登录名将到目录认证并通过后自动创建账号;本地账号永远优先' },
    { name: 'section-conn', label: '目录连接', type: 'section', full: true },
    { name: 'serverUrl', label: '目录地址', type: 'text', required: true, disabled, mono: true, full: true,
      placeholder: 'ldap://ad.corp.local:389 或 ldaps://ad.corp.local:636',
      help: 'AD 一般为 ldaps://;内网测试可用 ldap://(明文,不推荐生产)' },
    { name: 'bindDn', label: '搜索账号 DN', type: 'text', disabled, mono: true, full: true,
      placeholder: 'cn=iam-svc,ou=Service Accounts,dc=corp,dc=local',
      help: '只读权限即可;留空表示匿名绑定(目录允许时)' },
    { name: 'bindPassword', label: '搜索账号口令', type: 'password', disabled, full: true,
      autocomplete: 'new-password',
      placeholder: config.value?.bindPasswordConfigured ? '已配置,留空则不修改' : '请输入搜索账号口令',
      help: '信封加密后存储,界面只写不读' },
    { name: 'section-search', label: '用户定位', type: 'section', full: true },
    { name: 'userBaseDn', label: '用户搜索基 DN', type: 'text', required: true, disabled, mono: true, full: true,
      placeholder: 'ou=Employees,dc=corp,dc=local',
      help: '只在员工 OU 子树内搜索(如 ou=Employees,dc=corp,dc=local),避免目录管理员被 JIT 成本地账号' },
    { name: 'userFilter', label: '用户过滤器', type: 'text', disabled, mono: true, full: true,
      placeholder: '(sAMAccountName={0})',
      help: '{0} 为登录名占位;AD 用 sAMAccountName,OpenLDAP 用 uid' },
    { name: 'section-attr', label: '属性映射', type: 'section', full: true },
    { name: 'uuidAttribute', label: '稳定标识属性', type: 'text', disabled, mono: true,
      placeholder: 'objectGUID', help: 'AD: objectGUID;OpenLDAP: entryUUID' },
    { name: 'usernameAttribute', label: '登录名属性', type: 'text', disabled, mono: true,
      placeholder: 'sAMAccountName' },
    { name: 'emailAttribute', label: '邮箱属性', type: 'text', disabled, mono: true,
      placeholder: 'mail' },
    { name: 'displayNameAttribute', label: '显示名属性', type: 'text', disabled, mono: true,
      placeholder: 'displayName' },
    { name: 'section-adv', label: '高级', type: 'section', full: true },
    { name: 'connectTimeoutMs', label: '连接超时(毫秒)', type: 'number', required: true, min: 100, max: 30000, disabled },
    { name: 'readTimeoutMs', label: '读取超时(毫秒)', type: 'number', required: true, min: 100, max: 60000, disabled },
  ];
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const data = await api<Record<string, any>>('/api/v1/admin/ldap-config', { silent: true });
    // 口令只写不读:无论是否已配置,表单内一律为空,提交时空串表示不修改
    config.value = { ...(data || {}), bindPassword: '' };
    formKey.value += 1;
  } catch (error) {
    loadError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function validate(values: Record<string, any>): string | void {
  if (!String(values.serverUrl || '').trim()) return '请填写目录地址';
  if (!/^ldaps?:\/\/.+/.test(String(values.serverUrl).trim())) {
    return '目录地址需以 ldap:// 或 ldaps:// 开头';
  }
  if (!String(values.userBaseDn || '').trim()) return '请填写用户搜索基 DN';
  if (String(values.userFilter || '').trim() && !String(values.userFilter).includes('{0}')) {
    return '过滤器需包含 {0} 登录名占位,否则无法按登录名定位用户';
  }
  const connect = Number(values.connectTimeoutMs);
  const read = Number(values.readTimeoutMs);
  if (!Number.isFinite(connect) || connect < 100 || connect > 30000) return '连接超时需在 100-30000 毫秒之间';
  if (!Number.isFinite(read) || read < 100 || read > 60000) return '读取超时需在 100-60000 毫秒之间';
}

async function save() {
  const target = form.value;
  if (!target) return;
  const check = await target.validate();
  if (!check.ok) return;
  busy.value = true;
  try {
    const updated = await api<Record<string, any>>('/api/v1/admin/ldap-config', {
      method: 'PUT',
      body: target.read(),
      silent: true,
    });
    config.value = { ...(updated || config.value), bindPassword: '' };
    formKey.value += 1;
    toast('LDAP 联合配置已保存', 'success');
  } catch (error) {
    target.setError(errorMessage(error));
  } finally {
    busy.value = false;
  }
}

async function testConnection() {
  const target = form.value;
  if (!target) return;
  const check = await target.validate();
  if (!check.ok) return;
  testing.value = true;
  try {
    const result = await api<{ success: boolean; message: string }>('/api/v1/admin/ldap-config/test', {
      method: 'POST',
      body: target.read(),
      silent: true,
    });
    if (result?.success) {
      toast(result.message || '连接成功', 'success');
    } else {
      toast(result?.message || '连接失败', 'error');
    }
  } catch (error) {
    toast(errorMessage(error), 'error');
  } finally {
    testing.value = false;
  }
}

function openSync() {
  syncReport.value = null;
  syncOpen.value = true;
}

async function runSync(dryRun: boolean) {
  const maxEntries = Number(syncMax.value);
  if (!Number.isFinite(maxEntries) || maxEntries < 1 || maxEntries > 20000) {
    toast('读取上限需在 1-20000 之间', 'error');
    return;
  }
  if (!dryRun) {
    const ok = await confirmDialog({
      title: '执行目录全量同步',
      message: `将按已保存的目录配置读取最多 ${maxEntries} 个账号,补建或补全本地账号。`,
      detail: '同步不会删除本地账号,也不会覆盖本地口令、角色与状态;建议先预演确认影响面。',
      confirmText: '开始同步',
      danger: false,
    });
    if (!ok) return;
  }
  syncBusy.value = true;
  try {
    syncReport.value = await api<SyncReport>('/api/v1/admin/ldap-config/sync', {
      method: 'POST',
      body: { maxEntries, dryRun },
      silent: true,
    });
  } catch (error) {
    toast(errorMessage(error), 'error');
  } finally {
    syncBusy.value = false;
  }
}

void load();
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="LDAP/AD 用户联合"
      desc="把企业目录(AD/OpenLDAP)作为兜底认证源:本地账号优先,本地不存在时按登录名到目录绑定认证,通过后自动创建账号。"
    >
      <template #meta>
        <span class="policy-realm">当前域 {{ auth.realmKey }}</span>
      </template>
    </PageHeader>

    <div v-if="loading" class="card policy-loading">
      <SkeletonTable :rows="8" :columns="2" />
    </div>

    <div v-else-if="loadError" class="card policy-error">
      <AppIcon name="alert" :size="22" />
      <div>
        <strong>配置加载失败</strong>
        <p>{{ loadError }}</p>
      </div>
      <button class="btn btn-sm" type="button" @click="load">重试</button>
    </div>

    <template v-else>
      <div v-if="!canUpdate" class="alert is-info policy-readonly">
        <AppIcon name="lock" :size="15" />
        <span>当前账号只有查看权限;如需调整配置,请联系拥有 ldap-config:update 权限的管理员。</span>
      </div>

      <section class="card policy-card">
        <header class="policy-head">
          <span class="policy-mark is-navy">
            <AppIcon name="users" :size="19" />
          </span>
          <div class="policy-head-copy">
            <div class="policy-title">目录配置</div>
            <div class="policy-desc">按身份域各存一份;保存后立即作用于后续登录。</div>
          </div>
          <span class="badge is-gold">域级</span>
        </header>
        <div class="policy-body">
          <SchemaForm
            v-if="config"
            :key="formKey"
            ref="form"
            :fields="fields"
            :values="config"
            mode="edit"
            :validate="validate"
          />
        </div>
        <footer v-if="canUpdate" class="policy-foot">
          <span class="policy-foot-note">
            口令校验走"用户 DN + 输入口令"的目录绑定;保存前可先测试连接。
          </span>
          <button class="btn" type="button" :disabled="testing || busy" @click="openSync">
            <span>同步目录用户</span>
          </button>
          <button class="btn btn-accent" type="button" :disabled="testing || busy" @click="testConnection">
            <span v-if="testing" class="btn-spinner" />
            <span>测试连接</span>
          </button>
          <button class="btn btn-primary" type="button" :disabled="busy || testing" @click="save">
            <span v-if="busy" class="btn-spinner" />
            <span>保存配置</span>
          </button>
        </footer>
      </section>

      <BaseModal
        v-model:open="syncOpen"
        size="lg"
        title="目录用户同步"
        subtitle="把目录中已存在的账号铺底为本地账号;同步使用已保存的配置"
      >
        <div class="sync-body">
          <label class="sync-field">
            <span class="sync-label">单次读取上限</span>
            <input v-model.number="syncMax" class="input" type="number" min="1" max="20000" step="100" />
            <span class="sync-hint">1-20000 条;目录用户更多时可分批同步,报告会提示是否截断</span>
          </label>
          <div class="alert is-info sync-note">
            <span>
              只补建与补全:已有本地口令的账号整体跳过,本地口令、角色、状态一律不覆盖;
              目录中已删除的账号不会在本地删除。建议先预演确认影响面,再执行正式同步。
            </span>
          </div>
          <div v-if="syncReport" class="sync-report">
            <div class="sync-summary" :class="syncReport.success ? 'is-ok' : 'is-bad'">
              {{ syncReport.message }}
            </div>
            <div class="sync-stats">
              <div><strong>{{ syncReport.scanned }}</strong><span>读取</span></div>
              <div><strong>{{ syncReport.created }}</strong><span>新建</span></div>
              <div><strong>{{ syncReport.updated }}</strong><span>更新</span></div>
              <div><strong>{{ syncReport.unchanged }}</strong><span>无变化</span></div>
              <div><strong>{{ syncReport.skipped }}</strong><span>跳过</span></div>
              <div><strong>{{ syncReport.failed }}</strong><span>失败</span></div>
            </div>
            <div v-if="syncReport.samples?.length" class="sync-samples">
              <div class="sync-samples-title">样例说明</div>
              <ul>
                <li v-for="(line, index) in syncReport.samples" :key="index">{{ line }}</li>
              </ul>
            </div>
            <div class="sync-meta">
              <span v-if="syncReport.dryRun">预演模式:未写入任何数据</span>
              <span v-if="syncReport.truncated">结果已按读取上限截断</span>
              <span>耗时 {{ syncReport.durationMs }} ms</span>
            </div>
          </div>
        </div>
        <template #footer="{ close }">
          <button class="btn" type="button" :disabled="syncBusy" @click="close">关闭</button>
          <button class="btn" type="button" :disabled="syncBusy" @click="runSync(true)">预演</button>
          <button class="btn btn-accent" type="button" :disabled="syncBusy" @click="runSync(false)">
            <span v-if="syncBusy" class="btn-spinner" />
            <span>执行同步</span>
          </button>
        </template>
      </BaseModal>

      <div class="alert is-warn policy-note">
        <span>
          安全约定:本地已存在同名账号时只走本地口令,目录口令不会覆盖本地账号;
          目录认证失败(不可达、账号不存在、口令错误)对登录用户一律显示"账号或密码错误",
          真实原因可在「登录事件」中排查。建议搜索基只包含员工 OU,避免目录管理员被 JIT 成本地账号。
        </span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.policy-realm {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border: 1px solid rgba(168, 115, 10, 0.24);
  border-radius: 999px;
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-size: 10.5px;
  font-weight: 650;
}

.policy-loading,
.policy-error {
  padding: 22px;
}

.policy-error {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--muted);
}

.policy-error strong {
  display: block;
  color: var(--ink);
  font-size: 14px;
}

.policy-error p {
  margin: 4px 0 0;
  font-size: 12px;
}

.policy-error > .btn {
  margin-left: auto;
}

.policy-readonly {
  margin-bottom: 14px;
}

.policy-card {
  overflow: hidden;
}

.policy-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 18px 20px 16px;
  border-bottom: 1px solid var(--line-soft);
  background: linear-gradient(135deg, var(--surface-2), rgba(242, 181, 46, 0.06));
}

.policy-mark {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex: none;
  border: 1px solid rgba(168, 115, 10, 0.24);
  border-radius: 11px;
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.policy-mark.is-navy {
  border-color: rgba(16, 24, 40, 0.16);
  background: rgba(16, 24, 40, 0.06);
  color: var(--navy);
}

.policy-head-copy {
  min-width: 0;
  flex: 1;
}

.policy-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}

.policy-desc {
  margin-top: 4px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.6;
}

.policy-head > .badge {
  flex: none;
}

.policy-body {
  padding: 18px 20px 6px;
}

.policy-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--line-soft);
  background: var(--surface-2);
}

.policy-foot-note {
  flex: 1;
  color: var(--faint);
  font-size: 10.5px;
  line-height: 1.5;
}

.policy-note {
  margin-top: 16px;
  line-height: 1.7;
}

.sync-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.sync-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sync-label {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink);
}

.sync-hint {
  color: var(--faint);
  font-size: 10.5px;
}

.sync-note {
  line-height: 1.65;
}

.sync-report {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  background: var(--surface-2);
}

.sync-summary {
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink);
}

.sync-summary.is-bad {
  color: var(--danger, #b42318);
}

.sync-stats {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}

.sync-stats > div {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 8px 4px;
  border-radius: 8px;
  background: var(--surface);
}

.sync-stats strong {
  font-size: 16px;
  color: var(--ink);
}

.sync-stats span {
  color: var(--muted);
  font-size: 10.5px;
}

.sync-samples-title {
  margin-bottom: 6px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 650;
}

.sync-samples ul {
  margin: 0;
  padding-left: 16px;
  max-height: 180px;
  overflow: auto;
}

.sync-samples li {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
  word-break: break-all;
}

.sync-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--faint);
  font-size: 10.5px;
}
</style>
