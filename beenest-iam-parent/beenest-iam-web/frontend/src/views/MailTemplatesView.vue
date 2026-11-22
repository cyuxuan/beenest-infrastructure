<script setup lang="ts">
import { computed, h, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseModal from '@/components/BaseModal.vue';
import DataTable from '@/components/DataTable.vue';
import PageHeader from '@/components/PageHeader.vue';
import SkeletonTable from '@/components/SkeletonTable.vue';
import { api, errorMessage } from '@/lib/api';
import { confirmDialog } from '@/lib/confirm';
import { toast } from '@/lib/toast';
import { badge, tags } from '@/lib/ui';
import { auth, can } from '@/stores/auth';

interface TemplateRow {
  code: string;
  name: string;
  overridden: boolean;
}

const VARIABLES: Record<string, string[]> = {
  'password-reset': ['username', 'resetUrl', 'ttlMinutes', 'appName', 'year'],
  'email-verification': ['username', 'verifyUrl', 'ttlMinutes', 'appName', 'year'],
  'security-alert': ['alertTitle', 'alertSummary', 'occurredAt', 'username', 'appName', 'year'],
  'account-opened': ['username', 'appName', 'year'],
};

const REQUIRED: Record<string, string[]> = {
  'password-reset': ['resetUrl'],
  'email-verification': ['verifyUrl'],
};

/** 说明文案里的占位符示例:直接写进模板会被 Vue 当成插值,只能预先拼好 */
const PLACEHOLDER_EXAMPLE = '{{变量名}}';
const RESET_URL_PLACEHOLDER = '{{resetUrl}}';
const VERIFY_URL_PLACEHOLDER = '{{verifyUrl}}';

const loading = ref(true);
const loadError = ref('');
const rows = ref<TemplateRow[]>([]);

const editorOpen = ref(false);
const editorBusy = ref(false);
const editorLoading = ref(false);
const editorError = ref('');
const current = ref<TemplateRow | null>(null);
const content = ref('');

const canUpdate = computed(() => can('mail-config:update'));

const currentVariables = computed(() => (current.value ? VARIABLES[current.value.code] || [] : []));
const currentVariableLabels = computed(() => currentVariables.value.map((variable) => `{{${variable}}}`));
const currentRequired = computed(() => (current.value ? REQUIRED[current.value.code] || [] : []));

const columns = [
  {
    label: '模板',
    render: (row: TemplateRow) =>
      h('div', { class: 'cell-stack' }, [
        h('div', { class: 'cell-strong' }, row.name),
        h('div', { class: 'cell-sub' }, row.code),
      ]),
  },
  {
    label: '可用变量',
    render: (row: TemplateRow) => tags(VARIABLES[row.code] || [], 6, '无变量'),
  },
  {
    label: '版本',
    width: '100px',
    render: (row: TemplateRow) =>
      row.overridden ? badge('本域自定义', 'is-gold') : badge('内置模板', '', true),
  },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    rows.value = (await api<TemplateRow[]>('/api/v1/admin/mail-config/templates', { silent: true })) || [];
  } catch (error) {
    loadError.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

async function openEditor(row: TemplateRow) {
  current.value = row;
  content.value = '';
  editorError.value = '';
  editorOpen.value = true;
  editorLoading.value = true;
  try {
    const detail = await api<{ content: string }>(
      `/api/v1/admin/mail-config/templates/${encodeURIComponent(row.code)}`,
      { silent: true },
    );
    content.value = detail?.content || '';
  } catch (error) {
    editorError.value = errorMessage(error);
  } finally {
    editorLoading.value = false;
  }
}

function validate(): string | void {
  if (!content.value.trim()) return '模板内容不能为空';
  for (const variable of currentRequired.value) {
    if (!content.value.includes(`{{${variable}}}`)) {
      return `模板必须保留占位符 {{${variable}}},否则通知里的链接会丢失`;
    }
  }
}

async function save() {
  const row = current.value;
  if (!row) return;
  const check = validate();
  if (check) {
    editorError.value = check;
    return;
  }
  editorBusy.value = true;
  editorError.value = '';
  try {
    await api(`/api/v1/admin/mail-config/templates/${encodeURIComponent(row.code)}`, {
      method: 'PUT',
      body: { content: content.value },
      silent: true,
    });
    toast(`${row.name}已更新为本域自定义版本`, 'success');
    editorOpen.value = false;
    await load();
  } catch (error) {
    editorError.value = errorMessage(error);
  } finally {
    editorBusy.value = false;
  }
}

async function reset(row: TemplateRow) {
  const ok = await confirmDialog({
    title: '恢复内置模板',
    message: `确认删除 <strong>${row.name}</strong> 的本域自定义版本,恢复为内置模板吗?恢复后立即生效。`,
    confirmText: '恢复默认',
    danger: true,
  });
  if (!ok) return;
  try {
    await api(`/api/v1/admin/mail-config/templates/${encodeURIComponent(row.code)}`, {
      method: 'DELETE',
    });
    toast('已恢复内置模板', 'success');
    await load();
  } catch {
    /* 统一提示 */
  }
}

const rowActions = (row: TemplateRow) => {
  const actions: any[] = [];
  if (canUpdate.value) {
    actions.push({ label: '编辑', onClick: () => openEditor(row) });
    if (row.overridden) {
      actions.push({ label: '恢复默认', danger: true, onClick: () => reset(row) });
    }
  }
  return actions;
};

void load();
</script>

<template>
  <div class="view-inner">
    <PageHeader
      title="邮件模板"
      desc="按身份域覆盖内置邮件模板的品牌与措辞;没有自定义版本的模板使用内置版本。"
    >
      <template #meta>
        <span class="policy-realm">当前域 {{ auth.realmKey }}</span>
      </template>
    </PageHeader>

    <div v-if="loading" class="card template-loading">
      <SkeletonTable :rows="4" :columns="3" />
    </div>

    <div v-else-if="loadError" class="card policy-error">
      <AppIcon name="alert" :size="22" />
      <div>
        <strong>模板加载失败</strong>
        <p>{{ loadError }}</p>
      </div>
      <button class="btn btn-sm" type="button" @click="load">重试</button>
    </div>

    <section v-else class="card template-card">
      <DataTable
        :columns="columns"
        :rows="rows"
        row-key="code"
        :row-actions="rowActions"
        actions-label=""
        empty-text="没有可覆盖的邮件模板"
      />
    </section>

    <div class="alert is-info template-note">
      <span>
        模板使用 <code class="cell-mono">{{ PLACEHOLDER_EXAMPLE }}</code> 作为占位符,变量值会自动做 HTML 转义;
        {{ RESET_URL_PLACEHOLDER }} 与 {{ VERIFY_URL_PLACEHOLDER }} 是找回口令与邮箱验证的入口链接,覆盖时必须保留。
      </span>
    </div>

    <BaseModal
      v-model:open="editorOpen"
      size="xl"
      :title="current ? `编辑模板 · ${current.name}` : '编辑模板'"
      subtitle="保存后立即生效;变量会在发送时按实际值替换"
    >
      <div v-if="editorLoading" class="template-loading-inline">
        <span class="btn-spinner" />
        <span>正在读取模板…</span>
      </div>

      <template v-else>
        <div class="template-vars">
          <span class="template-vars-label">可用变量</span>
          <code v-for="label in currentVariableLabels" :key="label" class="template-var">{{ label }}</code>
        </div>
        <textarea
          v-model="content"
          class="textarea template-editor"
          spellcheck="false"
          placeholder="<!doctype html>…"
        />
        <div v-if="editorError" class="alert is-danger template-error">{{ editorError }}</div>
      </template>

      <template #footer="{ close }">
        <button class="btn btn-ghost" type="button" @click="close">取消</button>
        <button class="btn btn-primary" type="button" :disabled="editorBusy || editorLoading" @click="save">
          <span v-if="editorBusy" class="btn-spinner" />
          <span>保存</span>
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

.template-loading,
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

.template-card {
  overflow: hidden;
}

.template-note {
  margin-top: 16px;
  line-height: 1.7;
}

.template-vars {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}

.template-vars-label {
  color: var(--muted);
  font-size: 11.5px;
}

.template-var {
  padding: 1px 7px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface-2);
  color: var(--ink);
  font-family: var(--font-mono);
  font-size: 10.5px;
}

.template-editor {
  min-height: 380px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
}

.template-error {
  margin-top: 10px;
}

.template-loading-inline {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 40px 0;
  color: var(--muted);
  font-size: 13px;
}
</style>
