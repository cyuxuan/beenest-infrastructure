<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseDrawer from '@/components/BaseDrawer.vue';
import { api, errorMessage } from '@/lib/api';
import { toast } from '@/lib/toast';
import { can } from '@/stores/auth';

interface BackfillCandidate {
  kind: string;
  id: number;
  code: string;
  name: string;
  platform: boolean;
  ownerAppCode?: string | null;
  strategy: string;
  reasons: string[];
  roleCodes: string[];
  realmRoleCodes: string[];
  sharedAppCodes: string[];
}

interface BackfillReport {
  appId: number;
  appCode: string;
  appName: string;
  tokenProfile: string;
  permissions: BackfillCandidate[];
  menus: BackfillCandidate[];
  movableCount: number;
  copyOnlyCount: number;
  sharedCount: number;
}

interface BackfillResult {
  movedPermissions: number;
  movedMenus: number;
  copiedPermissions: number;
  copiedMenus: number;
  relinkedGrants: number;
  conflicts: string[];
}

const props = defineProps<{ open: boolean; app: any }>();

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'imported'): void;
}>();

const report = ref<BackfillReport | null>(null);
const loading = ref(false);
const error = ref('');
const saving = ref(false);
const selected = ref<Set<string>>(new Set());
const strategy = ref<'MOVE' | 'COPY'>('MOVE');
const result = ref<BackfillResult | null>(null);

const canImport = computed(() => can('app-service:backfill'));

const title = computed(() => '权限回填');
const subtitle = computed(() =>
  props.app ? `${props.app.appName} · ${props.app.appCode}` : '',
);

function keyOf(candidate: BackfillCandidate): string {
  return `${candidate.kind}:${candidate.id}`;
}

const selectedCandidates = computed<BackfillCandidate[]>(() => {
  if (!report.value) return [];
  return [...report.value.permissions, ...report.value.menus].filter((candidate) =>
    selected.value.has(keyOf(candidate)),
  );
});

const selectedPermissionIds = computed(() =>
  selectedCandidates.value.filter((c) => c.kind === 'PERMISSION').map((c) => c.id),
);

const selectedMenuIds = computed(() =>
  selectedCandidates.value.filter((c) => c.kind === 'MENU').map((c) => c.id),
);

const recommendedStrategy = computed<'MOVE' | 'COPY'>(() => {
  const pool = selectedCandidates.value.length
    ? selectedCandidates.value
    : [...(report.value?.permissions ?? []), ...(report.value?.menus ?? [])];
  return pool.some((candidate) => candidate.strategy !== 'MOVE') ? 'COPY' : 'MOVE';
});

const moveBlocked = computed(() =>
  selectedCandidates.value.some((candidate) => candidate.strategy !== 'MOVE'),
);

const canRun = computed(
  () => canImport.value && !saving.value && selectedCandidates.value.length > 0,
);

watch(
  () => selected.value,
  () => {
    if (selectedCandidates.value.length) strategy.value = recommendedStrategy.value;
  },
);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    selected.value = new Set();
    strategy.value = 'MOVE';
    result.value = null;
    error.value = '';
    void loadReport();
  },
);

async function loadReport() {
  const appId = props.app?.id;
  if (!appId) return;
  loading.value = true;
  error.value = '';
  try {
    report.value = await api<BackfillReport>(`/api/v1/admin/apps/${appId}/backfill/report`, {
      silent: true,
    });
    strategy.value = recommendedStrategy.value;
  } catch (err) {
    report.value = null;
    error.value = errorMessage(err);
  } finally {
    loading.value = false;
  }
}

function toggle(candidate: BackfillCandidate) {
  const next = new Set(selected.value);
  const key = keyOf(candidate);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  selected.value = next;
}

function toggleKind(kind: string) {
  const rows = kind === 'PERMISSION' ? report.value?.permissions : report.value?.menus;
  if (!rows?.length) return;
  const allSelected = rows.every((row) => selected.value.has(keyOf(row)));
  const next = new Set(selected.value);
  for (const row of rows) {
    if (allSelected) next.delete(keyOf(row));
    else next.add(keyOf(row));
  }
  selected.value = next;
}

function kindSelected(kind: string): boolean {
  const rows = kind === 'PERMISSION' ? report.value?.permissions : report.value?.menus;
  if (!rows?.length) return false;
  return rows.every((row) => selected.value.has(keyOf(row)));
}

async function runImport() {
  const appId = props.app?.id;
  if (!appId || !canRun.value) return;
  saving.value = true;
  error.value = '';
  try {
    const outcome = await api<BackfillResult>(`/api/v1/admin/apps/${appId}/backfill/import`, {
      method: 'POST',
      silent: true,
      body: {
        permissionIds: selectedPermissionIds.value,
        menuIds: selectedMenuIds.value,
        strategy: strategy.value,
      },
    });
    result.value = outcome;
    selected.value = new Set();
    toast('权限回填已执行', 'success');
    emit('imported');
    await loadReport();
  } catch (err) {
    error.value = errorMessage(err);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <BaseDrawer
    :open="open"
    :title="title"
    :subtitle="subtitle"
    width="720px"
    @update:open="emit('update:open', $event)"
  >
    <div v-if="loading" class="backfill-empty">正在分析回填缺口…</div>
    <div v-else-if="error && !report" class="alert is-danger">
      <AppIcon name="alert" :size="15" />
      <span>{{ error }}</span>
    </div>
    <template v-else-if="report">
      <div class="backfill-summary">
        <div class="backfill-stat">
          <span class="backfill-stat-value">{{ report.movableCount }}</span>
          <span class="backfill-stat-label">可直接迁移</span>
        </div>
        <div class="backfill-stat">
          <span class="backfill-stat-value">{{ report.copyOnlyCount }}</span>
          <span class="backfill-stat-label">只能复制</span>
        </div>
        <div class="backfill-stat">
          <span class="backfill-stat-value">{{ report.sharedCount }}</span>
          <span class="backfill-stat-label">被其它应用共用</span>
        </div>
        <span class="badge" :class="report.tokenProfile === 'SCOPED' ? 'is-gold' : 'is-outline'">
          {{ report.tokenProfile || 'SCOPED' }}
        </span>
      </div>

      <div class="alert is-info" style="margin-top: 12px">
        <AppIcon name="info" :size="15" />
        <span>
          SCOPED 令牌只输出本应用命名空间的权限与菜单。下列资源是本应用正在使用、但仍挂在平台命名空间的,
          切换到 SCOPED 前需要先回填。
        </span>
      </div>

      <div v-if="error" class="alert is-danger" style="margin-top: 10px">
        <AppIcon name="alert" :size="15" />
        <span>{{ error }}</span>
      </div>

      <div v-if="!canImport" class="alert is-warn" style="margin-top: 10px">
        <AppIcon name="info" :size="15" />
        <span>当前账号只能查看回填报告;执行回填需要全局管理员的 <code class="cell-mono">app-service:backfill</code> 权限。</span>
      </div>

      <div v-if="result" class="alert is-success" style="margin-top: 10px">
        <AppIcon name="check" :size="15" />
        <span>
          回填完成:迁移权限 {{ result.movedPermissions }} 项、迁移菜单 {{ result.movedMenus }} 项、复制权限
          {{ result.copiedPermissions }} 项、复制菜单 {{ result.copiedMenus }} 项,改写授权引用
          {{ result.relinkedGrants }} 条。
        </span>
      </div>
      <ul v-if="result?.conflicts?.length" class="backfill-conflicts">
        <li v-for="(item, index) in result.conflicts" :key="index">{{ item }}</li>
      </ul>

      <div v-if="!report.permissions.length && !report.menus.length" class="backfill-empty">
        当前应用没有需要回填的资源,可以直接切换到 SCOPED 令牌档。
      </div>

      <template v-else>
        <div class="backfill-toolbar">
          <span class="backfill-toolbar-label">已选 {{ selectedCandidates.length }} 项</span>
          <div class="backfill-strategy">
            <label
              class="backfill-strategy-option"
              :class="{ 'is-active': strategy === 'MOVE' }"
              :title="moveBlocked ? '选中的资源由域角色持有,只能复制到应用' : '直接改写资源归属'"
            >
              <input v-model="strategy" type="radio" value="MOVE" :disabled="moveBlocked" />
              <span>归属迁移 MOVE</span>
            </label>
            <label class="backfill-strategy-option" :class="{ 'is-active': strategy === 'COPY' }">
              <input v-model="strategy" type="radio" value="COPY" />
              <span>复制到应用 COPY</span>
            </label>
          </div>
        </div>
        <p class="backfill-hint">
          建议策略:<strong>{{ recommendedStrategy === 'MOVE' ? '归属迁移' : '复制到应用' }}</strong>。
          归属迁移直接改写资源归属,仅适用于没有被域角色持有的资源;域角色持有的平台资源一旦迁进单个应用,
          其它应用就再也拿不到它,这类资源只能复制到应用——平台原件保留,应用下建副本,授权引用改指向副本。
        </p>

        <div class="section-title">
          权限候选
          <span class="count-chip">{{ report.permissions.length }}</span>
          <button class="link-btn" type="button" @click="toggleKind('PERMISSION')">
            {{ kindSelected('PERMISSION') ? '取消全选' : '全选' }}
          </button>
        </div>
        <div v-if="!report.permissions.length" class="backfill-empty">本应用没有需要回填的权限。</div>
        <ul v-else class="backfill-list">
          <li
            v-for="candidate in report.permissions"
            :key="keyOf(candidate)"
            class="backfill-row"
            :class="{ 'is-checked': selected.has(keyOf(candidate)) }"
          >
            <label class="backfill-row-main">
              <input
                class="checkbox"
                type="checkbox"
                :checked="selected.has(keyOf(candidate))"
                @change="toggle(candidate)"
              />
              <span class="backfill-row-copy">
                <span class="backfill-row-title">
                  <span class="cell-mono">{{ candidate.code }}</span>
                  <span class="backfill-row-name">{{ candidate.name }}</span>
                </span>
                <span class="backfill-row-meta">
                  <span
                    class="tag"
                    :class="candidate.strategy === 'MOVE' ? 'is-gold' : 'is-danger'"
                  >
                    {{ candidate.strategy === 'MOVE' ? '可迁移' : '只能复制' }}
                  </span>
                  <span v-for="reason in candidate.reasons" :key="reason" class="tag">{{ reason }}</span>
                  <span v-if="candidate.roleCodes.length" class="tag is-gold">
                    应用角色:{{ candidate.roleCodes.join('、') }}
                  </span>
                  <span v-if="candidate.realmRoleCodes.length" class="tag">
                    域角色:{{ candidate.realmRoleCodes.join('、') }}
                  </span>
                  <span v-if="candidate.sharedAppCodes.length" class="tag is-danger">
                    共用:{{ candidate.sharedAppCodes.join('、') }}
                  </span>
                </span>
              </span>
            </label>
            <span class="badge" :class="candidate.platform ? 'is-gold' : 'is-info'">
              {{ candidate.platform ? '平台级' : `属 ${candidate.ownerAppCode || '其它应用'}` }}
            </span>
          </li>
        </ul>

        <div class="section-title">
          菜单候选
          <span class="count-chip">{{ report.menus.length }}</span>
          <button class="link-btn" type="button" @click="toggleKind('MENU')">
            {{ kindSelected('MENU') ? '取消全选' : '全选' }}
          </button>
        </div>
        <div v-if="!report.menus.length" class="backfill-empty">本应用没有需要回填的菜单。</div>
        <ul v-else class="backfill-list">
          <li
            v-for="candidate in report.menus"
            :key="keyOf(candidate)"
            class="backfill-row"
            :class="{ 'is-checked': selected.has(keyOf(candidate)) }"
          >
            <label class="backfill-row-main">
              <input
                class="checkbox"
                type="checkbox"
                :checked="selected.has(keyOf(candidate))"
                @change="toggle(candidate)"
              />
              <span class="backfill-row-copy">
                <span class="backfill-row-title">
                  <span class="cell-mono">{{ candidate.code }}</span>
                  <span class="backfill-row-name">{{ candidate.name }}</span>
                </span>
                <span class="backfill-row-meta">
                  <span
                    class="tag"
                    :class="candidate.strategy === 'MOVE' ? 'is-gold' : 'is-danger'"
                  >
                    {{ candidate.strategy === 'MOVE' ? '可迁移' : '只能复制' }}
                  </span>
                  <span v-for="reason in candidate.reasons" :key="reason" class="tag">{{ reason }}</span>
                  <span v-if="candidate.sharedAppCodes.length" class="tag is-danger">
                    共用:{{ candidate.sharedAppCodes.join('、') }}
                  </span>
                </span>
              </span>
            </label>
            <span class="badge" :class="candidate.platform ? 'is-gold' : 'is-info'">
              {{ candidate.platform ? '平台级' : `属 ${candidate.ownerAppCode || '其它应用'}` }}
            </span>
          </li>
        </ul>
      </template>
    </template>

    <template #footer>
      <button class="btn btn-ghost" type="button" @click="emit('update:open', false)">关闭</button>
      <button class="btn btn-ghost" type="button" :disabled="loading" @click="loadReport">
        <AppIcon name="refresh" :size="14" :class="{ spin: loading }" />
        <span>刷新报告</span>
      </button>
      <button v-if="canImport" class="btn btn-primary" type="button" :disabled="!canRun" @click="runImport">
        <span v-if="saving" class="btn-spinner" />
        <span>{{ saving ? '回填中…' : '执行回填' }}</span>
      </button>
    </template>
  </BaseDrawer>
</template>

<style scoped>
.backfill-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.backfill-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface-2);
}

.backfill-stat-value {
  font-family: var(--font-mono);
  font-size: 17px;
  font-weight: 700;
  color: var(--ink);
}

.backfill-stat-label {
  font-size: 11px;
  color: var(--muted);
}

.backfill-summary > .badge {
  margin-left: auto;
}

.backfill-empty {
  padding: 22px 16px;
  border: 1px dashed var(--line-strong);
  border-radius: 11px;
  background: var(--surface-2);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.backfill-conflicts {
  margin: 10px 0 0;
  padding: 10px 12px 10px 26px;
  border: 1px solid rgba(190, 60, 60, 0.28);
  border-radius: 11px;
  background: rgba(190, 60, 60, 0.05);
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--danger, #b03a3a);
}

.backfill-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
}

.backfill-toolbar-label {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink-2);
}

.backfill-strategy {
  display: flex;
  gap: 6px;
}

.backfill-strategy-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 11px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface-2);
  font-size: 11.5px;
  color: var(--muted);
  cursor: pointer;
}

.backfill-strategy-option.is-active {
  border-color: rgba(168, 115, 10, 0.32);
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-weight: 650;
}

.backfill-strategy-option input {
  margin: 0;
  accent-color: var(--gold-deep);
}

.backfill-strategy-option:has(input:disabled) {
  opacity: 0.5;
  cursor: not-allowed;
}

.backfill-hint {
  margin: 8px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--muted);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 18px 0 10px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-2);
}

.count-chip {
  min-width: 20px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--gold-soft);
  color: var(--gold-deep);
  font-size: 10.5px;
  font-weight: 700;
  text-align: center;
}

.section-title .link-btn {
  margin-left: auto;
}

.backfill-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.backfill-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: var(--surface);
}

.backfill-row.is-checked {
  border-color: rgba(168, 115, 10, 0.3);
  background: linear-gradient(135deg, var(--gold-soft), var(--surface));
}

.backfill-row-main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.backfill-row-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.backfill-row-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12.5px;
  font-weight: 650;
  color: var(--ink);
}

.backfill-row-name {
  font-weight: 500;
  color: var(--ink-2);
}

.backfill-row-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.backfill-row-meta .tag.is-gold {
  border-color: rgba(168, 115, 10, 0.24);
  background: var(--gold-soft);
  color: var(--gold-deep);
}

.backfill-row-meta .tag.is-danger {
  border-color: rgba(192, 74, 82, 0.24);
  background: var(--danger-soft);
  color: var(--danger);
}
</style>
