<script setup lang="ts">
import { computed, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import PageHeader from '@/components/PageHeader.vue';
import SchemaForm from '@/components/SchemaForm.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage } from '@/lib/api';
import { toast } from '@/lib/toast';
import type { FieldDef } from '@/lib/types';
import { auth, can } from '@/stores/auth';

const loading = ref(true);
const loadError = ref('');
const passwordPolicy = ref<Record<string, any> | null>(null);
const loginPolicy = ref<Record<string, any> | null>(null);
const passwordKey = ref(0);
const loginKey = ref(0);
const passwordBusy = ref(false);
const loginBusy = ref(false);
const passwordForm = ref<InstanceType<typeof SchemaForm> | null>(null);
const loginForm = ref<InstanceType<typeof SchemaForm> | null>(null);

const canUpdate = computed(() => can('security-policy:update'));

const passwordFields = computed<FieldDef[]>(() => {
  const disabled = !canUpdate.value;
  return [
    { name: 'minLength', label: '最小长度', type: 'number', required: true, min: 4, max: 128, disabled },
    { name: 'maxLength', label: '最大长度', type: 'number', required: true, min: 4, max: 128, disabled },
    { name: 'historyCount', label: '口令历史条数', type: 'number', min: 0, max: 24, disabled, help: '禁止复用最近 N 次口令,0 表示不限制' },
    { name: 'expireDays', label: '口令有效期(天)', type: 'number', min: 0, max: 3650, disabled, help: '0 表示长期有效;到期后强制修改' },
    { name: 'requireUppercase', label: '必须包含大写字母', type: 'switch', onText: '要求', disabled, full: true },
    { name: 'requireLowercase', label: '必须包含小写字母', type: 'switch', onText: '要求', disabled, full: true },
    { name: 'requireDigit', label: '必须包含数字', type: 'switch', onText: '要求', disabled, full: true },
    { name: 'requireSymbol', label: '必须包含符号', type: 'switch', onText: '要求', disabled, full: true },
  ];
});

const loginFields = computed<FieldDef[]>(() => {
  const disabled = !canUpdate.value;
  return [
    { name: 'maxFailAttempts', label: '最大失败次数', type: 'number', required: true, min: 1, max: 100, disabled, help: '窗口内达到该次数即锁定账号' },
    { name: 'failureWindowMinutes', label: '失败统计窗口(分钟)', type: 'number', required: true, min: 1, max: 1440, disabled },
    { name: 'lockDurationMinutes', label: '锁定时长(分钟)', type: 'number', required: true, min: 1, max: 1440, disabled },
    { name: 'captchaThreshold', label: '验证码触发阈值', type: 'number', required: true, min: 0, max: 100, disabled, help: '失败达到该次数后要求图形验证码,0 表示首错即要求' },
    { name: 'forceMfa', label: '强制 MFA', type: 'switch', onText: '全员强制', disabled, full: true, help: '开启后该域所有账号登录均需动态口令' },
    { name: 'smsLoginEnabled', label: '短信验证码登录', type: 'switch', onText: '已开启', disabled, full: true, help: '手机号 + 短信验证码作为独立第一因素;需在服务端配置短信通道(iam.sms.*)' },
    { name: 'smsCodeTtlSeconds', label: '验证码有效期(秒)', type: 'number', required: true, min: 60, max: 3600, disabled, help: '短信验证码的有效时长,到期需重新获取' },
    { name: 'smsMaxSendPerHour', label: '每小时发送上限', type: 'number', required: true, min: 0, max: 100, disabled, help: '同一手机号每小时最多发送条数,0 表示不限制(防短信轰炸)' },
    { name: 'smsMaxVerifyAttempts', label: '校验失败上限', type: 'number', required: true, min: 1, max: 20, disabled, help: '同一枚验证码连续输错达到该次数即作废,需重新获取' },
    { name: 'maxConcurrentSessions', label: '并发会话上限', type: 'number', required: true, min: 0, max: 20, disabled, help: '同一账号同时在线的设备数上限,0 表示不限制;超限时新登录会顶掉最旧的会话' },
  ];
});

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const [password, login] = await Promise.all([
      api<Record<string, any>>('/api/v1/admin/security-policies/password', { silent: true }),
      api<Record<string, any>>('/api/v1/admin/security-policies/login', { silent: true }),
    ]);
    passwordPolicy.value = password || {};
    loginPolicy.value = login || {};
    passwordKey.value += 1;
    loginKey.value += 1;
  } catch (error) {
    loadError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function validatePassword(values: Record<string, any>): string | void {
  const min = Number(values.minLength);
  const max = Number(values.maxLength);
  if (!Number.isFinite(min) || !Number.isFinite(max)) return '请填写完整的最小与最大长度';
  if (min < 4 || max > 128) return '口令长度需在 4-128 之间';
  if (min > max) return '最小长度不能大于最大长度';
  if (values.historyCount != null && (Number(values.historyCount) < 0 || Number(values.historyCount) > 24)) {
    return '口令历史条数需在 0-24 之间';
  }
  if (values.expireDays != null && (Number(values.expireDays) < 0 || Number(values.expireDays) > 3650)) {
    return '口令有效期需在 0-3650 天之间';
  }
}

function validateLogin(values: Record<string, any>): string | void {
  const attempts = Number(values.maxFailAttempts);
  const windowMinutes = Number(values.failureWindowMinutes);
  const lockMinutes = Number(values.lockDurationMinutes);
  const captcha = Number(values.captchaThreshold);
  if ([attempts, windowMinutes, lockMinutes, captcha].some((value) => !Number.isFinite(value))) {
    return '请填写完整的登录策略参数';
  }
  if (attempts < 1 || attempts > 100) return '最大失败次数需在 1-100 之间';
  if (windowMinutes < 1 || windowMinutes > 1440) return '失败统计窗口需在 1-1440 分钟之间';
  if (lockMinutes < 1 || lockMinutes > 1440) return '锁定时长需在 1-1440 分钟之间';
  if (captcha < 0 || captcha > 100) return '验证码阈值需在 0-100 之间';
  const ttl = Number(values.smsCodeTtlSeconds);
  const sendPerHour = Number(values.smsMaxSendPerHour);
  const verifyAttempts = Number(values.smsMaxVerifyAttempts);
  if ([ttl, sendPerHour, verifyAttempts].some((value) => !Number.isFinite(value))) {
    return '请填写完整的短信登录参数';
  }
  if (ttl < 60 || ttl > 3600) return '验证码有效期需在 60-3600 秒之间';
  if (sendPerHour < 0 || sendPerHour > 100) return '每小时发送上限需在 0-100 之间';
  if (verifyAttempts < 1 || verifyAttempts > 20) return '校验失败上限需在 1-20 之间';
  const concurrent = Number(values.maxConcurrentSessions);
  if (!Number.isFinite(concurrent)) return '请填写并发会话上限';
  if (concurrent < 0 || concurrent > 20) return '并发会话上限需在 0-20 之间';
}

async function savePassword() {
  const form = passwordForm.value;
  if (!form) return;
  const check = await form.validate();
  if (!check.ok) return;
  passwordBusy.value = true;
  try {
    const updated = await api<Record<string, any>>('/api/v1/admin/security-policies/password', {
      method: 'PUT',
      body: form.read(),
      silent: true,
    });
    passwordPolicy.value = updated || passwordPolicy.value;
    passwordKey.value += 1;
    toast('口令策略已保存', 'success');
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    passwordBusy.value = false;
  }
}

async function saveLogin() {
  const form = loginForm.value;
  if (!form) return;
  const check = await form.validate();
  if (!check.ok) return;
  loginBusy.value = true;
  try {
    const updated = await api<Record<string, any>>('/api/v1/admin/security-policies/login', {
      method: 'PUT',
      body: form.read(),
      silent: true,
    });
    loginPolicy.value = updated || loginPolicy.value;
    loginKey.value += 1;
    toast('登录策略已保存', 'success');
  } catch (error) {
    form.setError(errorMessage(error));
  } finally {
    loginBusy.value = false;
  }
}

void load();
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="安全策略"
      desc="口令与登录风控按身份域生效;修改后立即应用于后续登录(已登录会话不受影响)。"
    >
      <template #meta>
        <span class="policy-realm">当前域 {{ auth.realmKey }}</span>
      </template>
    </PageHeader>

    <div v-if="loading" class="card policy-loading">
      <SkeletonTable :rows="6" :columns="2" />
    </div>

    <div v-else-if="loadError" class="card policy-error">
      <AppIcon name="alert" :size="22" />
      <div>
        <strong>策略加载失败</strong>
        <p>{{ loadError }}</p>
      </div>
      <button class="btn btn-sm" type="button" @click="load">重试</button>
    </div>

    <template v-else>
      <div v-if="!canUpdate" class="alert is-info policy-readonly">
        <AppIcon name="lock" :size="15" />
        <span>当前账号只有查看权限;如需调整策略,请联系拥有 security-policy:update 权限的管理员。</span>
      </div>

      <div class="policy-grid">
        <section class="card policy-card">
          <header class="policy-head">
            <span class="policy-mark">
              <AppIcon name="key" :size="19" />
            </span>
            <div class="policy-head-copy">
              <div class="policy-title">口令策略</div>
              <div class="policy-desc">新建账号、重置口令与用户自助改密共用同一套规则。</div>
            </div>
            <span class="badge is-gold">域级</span>
          </header>
          <div class="policy-body">
            <SchemaForm
              v-if="passwordPolicy"
              :key="passwordKey"
              ref="passwordForm"
              :fields="passwordFields"
              :values="passwordPolicy"
              mode="edit"
              :validate="validatePassword"
            />
          </div>
          <footer v-if="canUpdate" class="policy-foot">
            <span class="policy-foot-note">修改后立即应用于后续改密与新建账号。</span>
            <button class="btn btn-primary" type="button" :disabled="passwordBusy" @click="savePassword">
              <span v-if="passwordBusy" class="btn-spinner" />
              <span>保存口令策略</span>
            </button>
          </footer>
        </section>

        <section class="card policy-card">
          <header class="policy-head">
            <span class="policy-mark is-navy">
              <AppIcon name="shield" :size="19" />
            </span>
            <div class="policy-head-copy">
              <div class="policy-title">登录策略</div>
              <div class="policy-desc">失败锁定用于抵御撞库;验证码阈值与强制 MFA 用于阶梯式加固。</div>
            </div>
            <span class="badge is-info">域级</span>
          </header>
          <div class="policy-body">
            <SchemaForm
              v-if="loginPolicy"
              :key="loginKey"
              ref="loginForm"
              :fields="loginFields"
              :values="loginPolicy"
              mode="edit"
              :validate="validateLogin"
            />
          </div>
          <footer v-if="canUpdate" class="policy-foot">
            <span class="policy-foot-note">登录风控立即生效;已锁定账号需在「用户」页手动解锁。</span>
            <button class="btn btn-primary" type="button" :disabled="loginBusy" @click="saveLogin">
              <span v-if="loginBusy" class="btn-spinner" />
              <span>保存登录策略</span>
            </button>
          </footer>
        </section>
      </div>

      <div class="alert is-warn policy-note">
        <span>
          策略调严后,已有账号可能因口令过期或复杂度不足而在下次登录时被要求改密;调宽不会回溯已锁定账号。
          强制 MFA 开启后,未绑定动态口令的账号将无法完成登录。
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

.policy-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
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
  gap: 14px;
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
}

@media (max-width: 1100px) {
  .policy-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 620px) {
  .policy-foot {
    flex-direction: column;
    align-items: stretch;
  }

  .policy-foot-note {
    flex: none;
  }
}
</style>
