<script setup lang="ts">
import { computed, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseModal from '@/components/BaseModal.vue';
import PageHeader from '@/components/PageHeader.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage } from '@/lib/api';
import { toast } from '@/lib/toast';
import type { FieldDef } from '@/lib/types';
import { auth, can } from '@/stores/auth';

const loading = ref(true);
const loadError = ref('');
const config = ref<Record<string, any> | null>(null);
const formKey = ref(0);
const busy = ref(false);
const form = ref<InstanceType<typeof SchemaForm> | null>(null);

const testOpen = ref(false);
const testTo = ref('');
const testing = ref(false);
const testError = ref('');

const canUpdate = computed(() => can('mail-config:update'));

const fields = computed<FieldDef[]>(() => {
  const disabled = !canUpdate.value;
  return [
    { name: 'enabled', label: '启用本域发信配置', type: 'switch', onText: '已启用', disabled, full: true,
      help: '启用后本域邮件经下面的 SMTP 发出;关闭或配置不完整时回落到服务端全局配置' },
    { name: 'section-conn', label: 'SMTP 连接', type: 'section', full: true },
    { name: 'host', label: 'SMTP 主机', type: 'text', required: true, disabled, mono: true, full: true,
      placeholder: 'smtp.example.com' },
    { name: 'port', label: '端口', type: 'number', required: true, min: 1, max: 65535, disabled,
      help: 'STARTTLS 常用 587,直连 TLS 常用 465' },
    { name: 'timeoutMillis', label: '超时(毫秒)', type: 'number', required: true, min: 1000, max: 60000, disabled },
    { name: 'username', label: 'SMTP 用户名', type: 'text', disabled, mono: true, full: true,
      placeholder: '留空表示不认证',
      help: '多数服务商要求用户名与发件邮箱一致' },
    { name: 'password', label: 'SMTP 口令', type: 'password', disabled, full: true,
      autocomplete: 'new-password',
      placeholder: config.value?.passwordConfigured ? '已配置,留空则不修改' : '请输入 SMTP 口令',
      help: '信封加密后存储,界面只写不读' },
    { name: 'starttls', label: '启用 STARTTLS', type: 'switch', onText: '已启用', disabled },
    { name: 'ssl', label: '启用 SSL', type: 'switch', onText: '已启用', disabled },
    { name: 'section-from', label: '发件人', type: 'section', full: true },
    { name: 'fromAddress', label: '发件邮箱', type: 'text', required: true, disabled, mono: true, full: true,
      placeholder: 'no-reply@example.com', help: '部分服务商要求它与认证账号一致,否则会被拒收' },
    { name: 'fromName', label: '发件人展示名', type: 'text', disabled, full: true,
      placeholder: '统一身份认证' },
  ];
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const data = await api<Record<string, any>>('/api/v1/admin/mail-config', { silent: true });
    // 口令只写不读:无论是否已配置,表单内一律为空,提交时空串表示不修改
    config.value = { ...(data || {}), password: '' };
    formKey.value += 1;
  } catch (error) {
    loadError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function validate(values: Record<string, any>): string | void {
  if (values.enabled) {
    if (!String(values.host || '').trim()) return '启用本域发信配置时必须填写 SMTP 主机';
    if (!String(values.fromAddress || '').trim()) return '启用本域发信配置时必须填写发件邮箱';
  }
  const port = Number(values.port);
  if (!Number.isFinite(port) || port < 1 || port > 65535) return '端口需在 1-65535 之间';
  const timeout = Number(values.timeoutMillis);
  if (!Number.isFinite(timeout) || timeout < 1000 || timeout > 60000) return '超时需在 1000-60000 毫秒之间';
  const address = String(values.fromAddress || '').trim();
  if (address && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address)) return '发件邮箱格式不正确';
  if (values.starttls && values.ssl) return 'STARTTLS 与直连 SSL 只需开启一个';
}

async function save() {
  const target = form.value;
  if (!target) return;
  const check = await target.validate();
  if (!check.ok) return;
  busy.value = true;
  try {
    const updated = await api<Record<string, any>>('/api/v1/admin/mail-config', {
      method: 'PUT',
      body: target.read(),
      silent: true,
    });
    config.value = { ...(updated || config.value), password: '' };
    formKey.value += 1;
    toast('发信配置已保存', 'success');
  } catch (error) {
    target.setError(errorMessage(error));
  } finally {
    busy.value = false;
  }
}

function openTest() {
  testTo.value = '';
  testError.value = '';
  testOpen.value = true;
}

async function sendTest() {
  const to = testTo.value.trim();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(to)) {
    testError.value = '请填写正确的收件邮箱';
    return;
  }
  testing.value = true;
  testError.value = '';
  try {
    const result = await api<{ success: boolean; message: string }>('/api/v1/admin/mail-config/test', {
      method: 'POST',
      body: { to },
      silent: true,
    });
    if (result?.success) {
      toast(result.message || '测试邮件已发送', 'success');
      testOpen.value = false;
    } else {
      testError.value = result?.message || '测试邮件发送失败';
    }
  } catch (error) {
    testError.value = errorMessage(error);
  } finally {
    testing.value = false;
  }
}

void load();
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="发信配置"
      desc="为当前身份域配置独立的 SMTP 发信通道;未启用或配置不完整时,邮件回落到服务端全局配置。"
    >
      <template #meta>
        <span class="policy-realm">当前域 {{ auth.realmKey }}</span>
        <span v-if="config" class="badge" :class="config.globalAvailable ? 'is-info' : 'is-warn'">
          {{ config.globalAvailable ? '全局兜底可用' : '全局兜底未配置' }}
        </span>
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
        <span>当前账号只有查看权限;如需调整配置,请联系拥有 mail-config:update 权限的管理员。</span>
      </div>

      <section class="card policy-card">
        <header class="policy-head">
          <span class="policy-mark is-navy">
            <AppIcon name="inbox" :size="19" />
          </span>
          <div class="policy-head-copy">
            <div class="policy-title">SMTP 通道</div>
            <div class="policy-desc">按身份域各存一份;保存后立即作用于后续邮件,如口令找回、邮箱验证与安全提醒。</div>
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
            测试邮件使用已保存的配置;改完参数请先保存再测试。
          </span>
          <button class="btn btn-accent" type="button" :disabled="testing || busy" @click="openTest">
            <span>测试发送</span>
          </button>
          <button class="btn btn-primary" type="button" :disabled="busy || testing" @click="save">
            <span v-if="busy" class="btn-spinner" />
            <span>保存配置</span>
          </button>
        </footer>
      </section>

      <div class="alert is-warn policy-note">
        <span>
          发送记录可在「通知投递」中按收件地址与结果查询;投递失败会保留原始错误,
          便于区分模板问题、收件方拒收与发信服务不可达。邮件模板的措辞与品牌可在「邮件模板」中按域覆盖。
        </span>
      </div>
    </template>

    <BaseModal v-model:open="testOpen" size="sm" title="发送测试邮件" subtitle="用当前已保存的发信配置投递一封测试邮件">
      <label class="field">
        <span class="field-label">收件邮箱</span>
        <input v-model="testTo" class="input" type="email" placeholder="you@example.com" autocomplete="off" />
      </label>
      <div v-if="testError" class="alert is-danger test-error">{{ testError }}</div>

      <template #footer="{ close }">
        <button class="btn btn-ghost" type="button" @click="close">取消</button>
        <button class="btn btn-primary" type="button" :disabled="testing" @click="sendTest">
          <span v-if="testing" class="btn-spinner" />
          <span>发送</span>
        </button>
      </template>
    </BaseModal>
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

.test-error {
  margin-top: 12px;
}
</style>
