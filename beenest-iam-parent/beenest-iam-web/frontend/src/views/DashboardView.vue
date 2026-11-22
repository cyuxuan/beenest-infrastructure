<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import PageHeader from '@/components/PageHeader.vue';
import AppIcon from '@/components/AppIcon.vue';
import EmptyState from '@/components/EmptyState.vue';
import { api, errorMessage } from '@/lib/api';
import { fmtTime, nowClock, relTime } from '@/lib/format';
import type { IconName } from '@/lib/icons';
import { can } from '@/stores/auth';
import { auth } from '@/stores/auth';

interface DashboardStats {
  userTotal: number;
  userActive: number;
  userDisabled: number;
  userLocked: number;
  clientTotal: number;
  groupTotal: number;
  sessionActive: number;
  mfaEnrolledUsers: number;
  todayLoginSuccess: number;
  todayLoginFailure: number;
  todayMfaFailure: number;
  todayLogout: number;
}

const stats = ref<DashboardStats | null>(null);
const events = ref<any[]>([]);
const loading = ref(true);
const eventsDegraded = ref(false);
const error = ref('');
const refreshedAt = ref('');
const clock = ref(nowClock());
let timer: number | undefined;

const EVENTS: Record<string, { text: string; tone: 'is-success' | 'is-danger' | 'is-warn' | '' }> = {
  LOGIN_SUCCESS: { text: '登录成功', tone: 'is-success' },
  LOGIN_FAILURE: { text: '登录失败', tone: 'is-danger' },
  MFA_SUCCESS: { text: 'MFA 通过', tone: 'is-success' },
  MFA_FAILURE: { text: 'MFA 失败', tone: 'is-warn' },
  LOGOUT: { text: '退出登录', tone: '' },
};

const shortcuts: Array<{ path: string; label: string; hint: string; icon: IconName; perm: string }> = [
  { path: '/users', label: '用户开户', hint: '开户、重置口令、锁定', icon: 'users', perm: 'user:create' },
  { path: '/clients', label: '接入应用', hint: '登记 OAuth2 客户端', icon: 'apps', perm: 'client:create' },
  { path: '/roles', label: '角色编排', hint: '权限与菜单授予', icon: 'shield', perm: 'role:view' },
  { path: '/security-policies', label: '安全策略', hint: '口令与登录风控', icon: 'policy', perm: 'security-policy:view' },
  { path: '/sessions', label: '在线会话', hint: '强制下线与会话审计', icon: 'session', perm: 'session:view' },
  { path: '/audit-logs', label: '审计日志', hint: '管理操作全留痕', icon: 'audit', perm: 'audit:view' },
];

const availableShortcuts = computed(() => shortcuts.filter((item) => can(item.perm)));

const number = (value: unknown) => Number(value || 0).toLocaleString('zh-CN');
const percent = (value: number, total: number) => (total > 0 ? Math.round((value / total) * 100) : 0);

const authTotal = computed(() =>
  stats.value ? stats.value.todayLoginSuccess + stats.value.todayLoginFailure : 0,
);
const successRate = computed(() => (stats.value ? percent(stats.value.todayLoginSuccess, authTotal.value) : 0));
const mfaRate = computed(() => (stats.value ? percent(stats.value.mfaEnrolledUsers, stats.value.userTotal) : 0));
const failureRate = computed(() => (stats.value ? percent(stats.value.todayLoginFailure, authTotal.value) : 0));

const posture = computed(() => {
  if (!stats.value) return { tone: 'is-info', label: '等待数据', note: '正在同步当前身份域指标' };
  if (stats.value.todayLoginFailure > stats.value.todayLoginSuccess) {
    return { tone: 'is-danger', label: '认证异常偏高', note: '今日失败次数高于成功,建议检查登录事件与来源 IP' };
  }
  if (failureRate.value >= 20) {
    return { tone: 'is-warn', label: '需要关注', note: '今日失败率偏高,可能存在撞库或口令喷洒' };
  }
  return { tone: 'is-success', label: '运行平稳', note: '认证成功率与 MFA 覆盖率处于健康区间' };
});

const userFoot = computed(() => {
  if (!stats.value) return '';
  return `${number(stats.value.userActive)} 正常 · ${number(stats.value.userDisabled)} 禁用 · ${number(stats.value.userLocked)} 锁定`;
});

const cards = computed(() => {
  const data = stats.value;
  if (!data) return [];
  return [
    {
      key: 'users',
      label: '账号总数',
      value: number(data.userTotal),
      foot: userFoot.value,
      icon: 'users' as IconName,
      tone: 'gold',
      progress: percent(data.userActive, data.userTotal || 1),
    },
    {
      key: 'sessions',
      label: '活跃会话',
      value: number(data.sessionActive),
      foot: '实时登录态,可直接强制下线',
      icon: 'session' as IconName,
      tone: 'info',
    },
    {
      key: 'clients',
      label: '接入客户端',
      value: number(data.clientTotal),
      foot: 'OAuth2 / OIDC 接入登记',
      icon: 'apps' as IconName,
      tone: 'gold',
    },
    {
      key: 'groups',
      label: '用户组',
      value: number(data.groupTotal),
      foot: '支持组继承角色与权限',
      icon: 'layers' as IconName,
      tone: 'info',
    },
    {
      key: 'loginSuccess',
      label: '今日登录成功',
      value: number(data.todayLoginSuccess),
      foot: `零点起累计 · 成功率 ${successRate.value}%`,
      icon: 'login' as IconName,
      tone: 'success',
    },
    {
      key: 'loginFailure',
      label: '今日登录失败',
      value: number(data.todayLoginFailure),
      foot: '含口令错误与锁定拒绝',
      icon: 'alert' as IconName,
      tone: 'danger',
    },
    {
      key: 'mfa',
      label: 'MFA 绑定账号',
      value: number(data.mfaEnrolledUsers),
      foot: `覆盖率 ${mfaRate.value}%`,
      icon: 'shield' as IconName,
      tone: 'gold',
      progress: mfaRate.value,
    },
    {
      key: 'mfaFailure',
      label: '今日 MFA 失败',
      value: number(data.todayMfaFailure),
      foot: `今日主动退出 ${number(data.todayLogout)} 次`,
      icon: 'zap' as IconName,
      tone: 'warn',
    },
  ];
});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const statsPromise = api<DashboardStats>('/api/v1/admin/dashboard/stats', { silent: true });
    const eventsPromise = can('login-event:view')
      ? api<any>('/api/v1/admin/login-events', { query: { pageNum: 1, pageSize: 8 }, silent: true }).catch(() => {
          eventsDegraded.value = true;
          return null;
        })
      : Promise.resolve(null);
    const [data, eventPage] = await Promise.all([statsPromise, eventsPromise]);
    stats.value = data;
    if (eventPage) {
      events.value = eventPage.records || [];
      eventsDegraded.value = false;
    } else {
      events.value = [];
    }
    refreshedAt.value = fmtTime(new Date().toISOString());
  } catch (err) {
    error.value = errorMessage(err);
  } finally {
    loading.value = false;
  }
}

function eventMeta(type: string) {
  return EVENTS[type] || { text: type || '—', tone: '' as const };
}

onMounted(() => {
  load();
  timer = window.setInterval(() => {
    clock.value = nowClock();
  }, 1000);
});

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
});
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="运营概览"
      :desc="`身份域 ${auth.realmKey} 的账号、会话与认证态势;统计口径为当前域实时数据。`"
    >
      <template #actions>
        <button class="btn" type="button" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="15" :class="{ spin: loading }" />
          <span>刷新</span>
        </button>
      </template>
    </PageHeader>

    <section class="dash-hero">
      <div class="dash-hero-copy">
        <div class="dash-hero-kicker">
          <span class="status-pulse" />
          <span>IDENTITY CONTROL PLANE · 实时</span>
        </div>
        <h2>
          身份域 <span class="dash-hero-realm">{{ auth.realmKey }}</span>
        </h2>
        <p>统一管理账号、组织、授权与应用接入;所有管理动作全程留痕。</p>
        <div class="dash-hero-meta">
          <span class="dash-hero-clock">{{ clock }}</span>
          <span class="dash-hero-sep" />
          <span class="badge" :class="posture.tone">{{ posture.label }}</span>
          <span class="dash-hero-note">{{ posture.note }}</span>
        </div>
      </div>
      <div class="dash-hero-art" aria-hidden="true">
        <span class="dash-hex dash-hex-a" />
        <span class="dash-hex dash-hex-b" />
        <span class="dash-hex dash-hex-c" />
        <span class="dash-orbit" />
        <span class="dash-core">
          <AppIcon name="hexagon" :size="30" />
        </span>
      </div>
    </section>

    <div v-if="error" class="load-error" style="margin-bottom: 16px">
      <div class="load-error-icon"><AppIcon name="alert" :size="20" /></div>
      <div class="load-error-copy">
        <strong>概览数据加载失败</strong>
        <span>{{ error }}</span>
      </div>
      <button class="btn btn-sm" type="button" @click="load">重试</button>
    </div>

    <div v-if="loading && !stats" class="dash-grid">
      <div v-for="index in 8" :key="index" class="dash-stat is-loading">
        <span class="skeleton" style="height: 12px; width: 46%" />
        <span class="skeleton" style="height: 30px; width: 60%" />
        <span class="skeleton" style="height: 10px; width: 72%" />
      </div>
    </div>

    <template v-else-if="stats">
      <div class="dash-grid">
        <article v-for="card in cards" :key="card.key" class="dash-stat" :class="`is-${card.tone}`">
          <div class="dash-stat-head">
            <span class="dash-stat-label">{{ card.label }}</span>
            <span class="dash-stat-icon"><AppIcon :name="card.icon" :size="16" /></span>
          </div>
          <div class="dash-stat-value">{{ card.value }}</div>
          <div class="dash-stat-foot">{{ card.foot }}</div>
          <div v-if="card.progress !== undefined" class="dash-stat-track">
            <span :style="{ width: `${Math.min(100, Math.max(0, card.progress))}%` }" />
          </div>
        </article>
      </div>

      <div class="dash-columns">
        <section class="card dash-panel">
          <div class="card-head">
            <div class="card-head-main">
              <div class="card-title">今日认证态势</div>
              <div class="card-sub">成功与失败对比用于识别撞库、口令喷洒等异常</div>
            </div>
            <span class="badge" :class="failureRate >= 20 ? 'is-warn' : 'is-success'">
              失败率 {{ failureRate }}%
            </span>
          </div>
          <div class="dash-panel-body">
            <div class="dash-ratio">
              <div class="dash-ratio-head">
                <span>登录成功</span>
                <span><b>{{ number(stats.todayLoginSuccess) }}</b> <em>/ {{ number(authTotal) }}</em></span>
              </div>
              <div class="dash-track">
                <span class="is-success" :style="{ width: `${successRate}%` }" />
              </div>
            </div>
            <div class="dash-ratio">
              <div class="dash-ratio-head">
                <span>登录失败</span>
                <span><b>{{ number(stats.todayLoginFailure) }}</b> <em>/ {{ number(authTotal) }}</em></span>
              </div>
              <div class="dash-track">
                <span class="is-danger" :style="{ width: `${failureRate}%` }" />
              </div>
            </div>
            <div class="dash-ratio">
              <div class="dash-ratio-head">
                <span>MFA 绑定率</span>
                <span><b>{{ number(stats.mfaEnrolledUsers) }}</b> <em>/ {{ number(stats.userTotal) }}</em></span>
              </div>
              <div class="dash-track">
                <span class="is-gold" :style="{ width: `${mfaRate}%` }" />
              </div>
            </div>

            <div class="dash-pulse">
              <div class="dash-pulse-item">
                <span class="dash-pulse-value">{{ number(stats.sessionActive) }}</span>
                <span class="dash-pulse-label">在线会话</span>
              </div>
              <div class="dash-pulse-item">
                <span class="dash-pulse-value">{{ number(stats.todayLogout) }}</span>
                <span class="dash-pulse-label">今日退出</span>
              </div>
              <div class="dash-pulse-item">
                <span class="dash-pulse-value">{{ number(stats.todayMfaFailure) }}</span>
                <span class="dash-pulse-label">MFA 失败</span>
              </div>
            </div>

            <div class="alert is-warn" style="margin-top: 14px">
              <AppIcon name="info" :size="15" />
              <span>失败率持续升高时,请到「登录事件」按来源 IP 溯源,并考虑收紧登录策略。</span>
            </div>
          </div>
        </section>

        <section class="card dash-panel">
          <div class="card-head">
            <div class="card-head-main">
              <div class="card-title">最近登录事件</div>
              <div class="card-sub">实时认证轨迹 · 最近 8 条</div>
            </div>
            <RouterLink v-if="can('login-event:view')" class="link-btn" to="/login-events">全部事件 →</RouterLink>
          </div>
          <div class="dash-panel-body">
            <div v-if="!can('login-event:view')" class="dash-empty-note">
              <AppIcon name="lock" :size="15" />
              <span>缺少 login-event:view 权限,无法查看认证轨迹。</span>
            </div>
            <div v-else-if="eventsDegraded" class="dash-empty-note">
              <AppIcon name="alert" :size="15" />
              <span>登录事件暂时不可用,统计数据不受影响。</span>
            </div>
            <EmptyState
              v-else-if="!events.length"
              title="暂无登录事件"
              hint="有登录行为后将在此呈现"
              icon="login"
            />
            <div v-else class="dash-events">
              <RouterLink
                v-for="event in events"
                :key="event.id"
                class="dash-event"
                :to="{ path: '/login-events', query: event.username ? { username: event.username } : {} }"
              >
                <span class="dash-event-dot" :class="eventMeta(event.eventType).tone" />
                <div class="dash-event-copy">
                  <div class="dash-event-title">
                    <span class="cell-strong">{{ event.username || '未知账号' }}</span>
                    <span class="badge" :class="eventMeta(event.eventType).tone">{{ eventMeta(event.eventType).text }}</span>
                  </div>
                  <div class="cell-sub">
                    {{ event.ip || '—' }}<template v-if="event.failReason"> · {{ event.failReason }}</template>
                  </div>
                </div>
                <span class="dash-event-time">{{ relTime(event.occurredTime) }}</span>
              </RouterLink>
            </div>
          </div>
        </section>
      </div>

      <section v-if="availableShortcuts.length" class="dash-shortcuts">
        <div class="dash-shortcut-head">
          <span class="card-title">常用操作</span>
          <span class="muted">按当前账号权限过滤</span>
        </div>
        <div class="dash-shortcut-grid">
          <RouterLink v-for="item in availableShortcuts" :key="item.path" class="dash-shortcut" :to="item.path">
            <span class="dash-shortcut-icon"><AppIcon :name="item.icon" :size="17" /></span>
            <span class="dash-shortcut-copy">
              <span class="dash-shortcut-label">{{ item.label }}</span>
              <span class="dash-shortcut-hint">{{ item.hint }}</span>
            </span>
            <AppIcon name="arrowRight" :size="15" class="dash-shortcut-arrow" />
          </RouterLink>
        </div>
      </section>

      <p class="dash-foot">
        数据时间 {{ refreshedAt || '—' }} · 统计范围:当前登录账号所属身份域
      </p>
    </template>
  </div>
</template>

<style scoped>
.dash-hero {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 26px 28px;
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--line);
  background:
    radial-gradient(circle at 88% 12%, rgba(242, 181, 46, 0.2), transparent 38%),
    linear-gradient(118deg, var(--surface) 0%, var(--surface-2) 58%, #f6eed9 100%);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.dash-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: var(--hive-pattern);
  background-size: 46px;
  opacity: 0.35;
  mask-image: linear-gradient(100deg, transparent 45%, #000 100%);
  pointer-events: none;
}

.dash-hero-copy {
  position: relative;
  z-index: 1;
  max-width: 720px;
}

.dash-hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(168, 115, 10, 0.24);
  background: rgba(255, 253, 248, 0.7);
  font-size: 9.5px;
  font-weight: 650;
  letter-spacing: 0.16em;
  color: var(--gold-deep);
}

.dash-hero h2 {
  margin: 14px 0 8px;
  font-size: clamp(22px, 2.4vw, 30px);
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--ink);
}

.dash-hero-realm {
  color: var(--gold-deep);
  background: linear-gradient(120deg, var(--gold-deep), #c98a10);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.dash-hero p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--muted);
}

.dash-hero-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 16px;
  font-size: 11px;
  color: var(--muted);
}

.dash-hero-clock {
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: 0.06em;
  color: var(--ink);
}

.dash-hero-sep {
  width: 1px;
  height: 12px;
  background: var(--line-strong);
}

.dash-hero-note {
  color: var(--faint);
}

.dash-hero-art {
  position: relative;
  width: 190px;
  height: 130px;
  flex-shrink: 0;
  z-index: 1;
}

.dash-core {
  position: absolute;
  inset: 50% auto auto 50%;
  transform: translate(-50%, -50%);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 68px;
  height: 68px;
  border-radius: 20px;
  color: var(--gold-deep);
  background: linear-gradient(150deg, #fff8e6, var(--gold-soft));
  border: 1px solid rgba(168, 115, 10, 0.28);
  box-shadow: 0 14px 34px rgba(140, 98, 8, 0.16);
}

.dash-orbit {
  position: absolute;
  inset: 8px 24px;
  border-radius: 50%;
  border: 1px dashed rgba(140, 98, 8, 0.3);
  transform: rotate(-14deg);
}

.dash-hex {
  position: absolute;
  width: 46px;
  height: 52px;
  background: rgba(242, 181, 46, 0.12);
  border: 1px solid rgba(168, 115, 10, 0.22);
  clip-path: polygon(50% 0, 100% 25%, 100% 75%, 50% 100%, 0 75%, 0 25%);
}

.dash-hex-a {
  top: 2px;
  left: 10px;
  animation: dash-float 7s ease-in-out infinite;
}

.dash-hex-b {
  right: 6px;
  top: 26px;
  width: 34px;
  height: 39px;
  animation: dash-float 9s ease-in-out infinite reverse;
}

.dash-hex-c {
  left: 42px;
  bottom: 0;
  width: 28px;
  height: 32px;
  animation: dash-float 8s ease-in-out infinite;
}

@keyframes dash-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-7px);
  }
}

.dash-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.dash-stat {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 17px 15px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-xs, 0 1px 2px rgba(16, 24, 40, 0.04));
  overflow: hidden;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.dash-stat::after {
  content: '';
  position: absolute;
  inset: 0 auto auto 0;
  width: 3px;
  height: 100%;
  background: transparent;
}

.dash-stat.is-gold::after {
  background: linear-gradient(180deg, var(--gold), rgba(242, 181, 46, 0.1));
}

.dash-stat.is-info::after {
  background: linear-gradient(180deg, var(--info), rgba(40, 100, 160, 0.1));
}

.dash-stat.is-success::after {
  background: linear-gradient(180deg, var(--success), rgba(47, 158, 110, 0.1));
}

.dash-stat.is-danger::after {
  background: linear-gradient(180deg, var(--danger), rgba(229, 72, 77, 0.1));
}

.dash-stat.is-warn::after {
  background: linear-gradient(180deg, var(--warn), rgba(217, 143, 17, 0.1));
}

.dash-stat:hover {
  transform: translateY(-2px);
  border-color: var(--line-strong);
  box-shadow: var(--shadow-sm);
}

.dash-stat.is-loading {
  gap: 12px;
}

.dash-stat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dash-stat-label {
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--muted);
}

.dash-stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: var(--gold-deep);
  background: var(--gold-wash);
}

.dash-stat.is-danger .dash-stat-icon {
  color: var(--danger);
  background: var(--danger-soft);
}

.dash-stat.is-success .dash-stat-icon {
  color: var(--success);
  background: var(--success-soft);
}

.dash-stat.is-info .dash-stat-icon {
  color: var(--info);
  background: var(--info-soft);
}

.dash-stat.is-warn .dash-stat-icon {
  color: var(--warn);
  background: var(--warn-soft);
}

.dash-stat-value {
  font-size: 27px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.dash-stat-foot {
  font-size: 10.5px;
  color: var(--faint);
  line-height: 1.5;
}

.dash-stat-track {
  height: 4px;
  border-radius: 999px;
  background: var(--surface-3);
  overflow: hidden;
}

.dash-stat-track span {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--gold), var(--gold-deep));
  transition: width 0.5s ease;
}

.dash-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 14px;
  margin-top: 14px;
}

.dash-panel {
  min-width: 0;
}

.dash-panel-body {
  padding: 16px 18px 18px;
}

.dash-ratio {
  margin-bottom: 14px;
}

.dash-ratio-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 11.5px;
  color: var(--muted);
}

.dash-ratio-head b {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--ink);
}

.dash-ratio-head em {
  font-style: normal;
  color: var(--faint);
}

.dash-track {
  height: 7px;
  border-radius: 999px;
  background: var(--surface-3);
  overflow: hidden;
}

.dash-track span {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width 0.5s ease;
}

.dash-track .is-success {
  background: linear-gradient(90deg, #6fd2a8, #2f9e6e);
}

.dash-track .is-danger {
  background: linear-gradient(90deg, #f4a2a4, #e5484d);
}

.dash-track .is-gold {
  background: linear-gradient(90deg, var(--gold-bright), #d99a12);
}

.dash-pulse {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}

.dash-pulse-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 9px;
  background: var(--surface-2);
  border: 1px solid var(--line-soft);
}

.dash-pulse-value {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 650;
  color: var(--ink);
}

.dash-pulse-label {
  font-size: 10px;
  color: var(--faint);
}

.dash-events {
  display: flex;
  flex-direction: column;
}

.dash-event {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 2px;
  border-bottom: 1px solid var(--line-soft);
  color: inherit;
  text-decoration: none;
  transition: background 0.14s ease;
}

.dash-event:last-child {
  border-bottom: none;
}

.dash-event:hover {
  background: var(--surface-2);
}

.dash-event-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--faint);
  flex-shrink: 0;
}

.dash-event-dot.is-success {
  background: var(--success);
  box-shadow: 0 0 0 3px var(--success-soft);
}

.dash-event-dot.is-danger {
  background: var(--danger);
  box-shadow: 0 0 0 3px var(--danger-soft);
}

.dash-event-dot.is-warn {
  background: var(--warn);
  box-shadow: 0 0 0 3px var(--warn-soft);
}

.dash-event-copy {
  min-width: 0;
  flex: 1;
}

.dash-event-title {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.dash-event-time {
  font-size: 10.5px;
  color: var(--faint);
  white-space: nowrap;
}

.dash-empty-note {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 9px;
  background: var(--surface-2);
  border: 1px dashed var(--line);
  font-size: 11.5px;
  color: var(--muted);
}

.dash-shortcuts {
  margin-top: 16px;
}

.dash-shortcut-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}

.dash-shortcut-head .muted {
  font-size: 10.5px;
}

.dash-shortcut-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.dash-shortcut {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 13px 14px;
  border-radius: 12px;
  border: 1px solid var(--line);
  background: var(--surface);
  color: inherit;
  text-decoration: none;
  transition: border-color 0.16s ease, transform 0.16s ease, box-shadow 0.16s ease;
}

.dash-shortcut:hover {
  transform: translateY(-2px);
  border-color: rgba(168, 115, 10, 0.34);
  box-shadow: var(--shadow-sm);
}

.dash-shortcut-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  color: var(--gold-deep);
  background: var(--gold-soft);
  flex-shrink: 0;
}

.dash-shortcut-copy {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.dash-shortcut-label {
  font-size: 12.5px;
  font-weight: 650;
  color: var(--ink);
}

.dash-shortcut-hint {
  font-size: 10.5px;
  color: var(--faint);
}

.dash-shortcut-arrow {
  margin-left: auto;
  color: var(--faint);
}

.dash-foot {
  margin: 18px 0 4px;
  font-size: 10.5px;
  color: var(--faint);
  letter-spacing: 0.02em;
}

@media (max-width: 1280px) {
  .dash-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .dash-columns {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 1100px) {
  .dash-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dash-shortcut-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .dash-hero {
    padding: 20px;
  }

  .dash-hero-art {
    display: none;
  }

  .dash-shortcut-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 560px) {
  .dash-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .dash-pulse {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .dash-stat-value {
    font-size: 24px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .dash-hex {
    animation: none;
  }
}
</style>
