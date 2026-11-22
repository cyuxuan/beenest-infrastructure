import { h, type VNode } from 'vue';
import { safeHttpUrl, fmtTime, relTime } from './format';

export type Tone = 'is-success' | 'is-danger' | 'is-warn' | 'is-info' | 'is-gold' | '';

export function badge(text: unknown, tone: Tone = '', outline = false): VNode {
  return h('span', { class: ['badge', tone, outline ? 'is-outline' : ''] }, String(text ?? '—'));
}

export function dotBadge(text: unknown, tone: Tone = ''): VNode {
  return h('span', { class: ['dot-badge', tone] }, [h('i', { class: 'dot' }), String(text ?? '—')]);
}

export function strongSub(primary: unknown, secondary?: unknown): VNode {
  return h('div', { class: 'cell-stack' }, [
    h('div', { class: 'cell-strong' }, String(primary ?? '—')),
    h('div', { class: 'cell-sub' }, String(secondary ?? '—')),
  ]);
}

export function monoSub(primary: unknown, secondary?: unknown): VNode {
  return h('div', { class: 'cell-stack' }, [
    h('div', { class: 'cell-mono' }, String(primary ?? '—')),
    h('div', { class: 'cell-sub' }, String(secondary ?? '—')),
  ]);
}

export function mono(value: unknown): VNode {
  return h('span', { class: 'cell-mono' }, String(value ?? '—'));
}

export function muted(value: unknown): VNode {
  return h('span', { class: 'muted' }, String(value ?? '—'));
}

export function tags(values: readonly unknown[] | null | undefined, max = 3, mutedText = '—'): VNode {
  const list = values || [];
  if (!list.length) return h('span', { class: 'muted' }, mutedText);
  const shown = list.slice(0, max);
  return h('span', { class: 'tag-row' }, [
    ...shown.map((value) => h('span', { class: 'tag', key: String(value) }, String(value))),
    list.length > max ? h('span', { class: 'tag-more' }, `+${list.length - max}`) : null,
  ]);
}

export function timeCell(value: unknown, relative = false): VNode {
  if (relative) {
    return h('div', { class: 'cell-stack' }, [
      h('div', null, fmtTime(value)),
      h('div', { class: 'cell-sub' }, relTime(value)),
    ]);
  }
  return h('span', { class: 'muted nowrap' }, fmtTime(value));
}

export function externalLink(url: unknown, text?: string, maxWidth = '240px'): VNode {
  const href = safeHttpUrl(url);
  if (!href) return h('span', { class: 'muted' }, String(text || url || '—'));
  return h(
    'a',
    {
      class: 'link-inline cell-mono truncate',
      href,
      target: '_blank',
      rel: 'noopener noreferrer',
      style: { maxWidth },
      title: href,
    },
    [text || href, h('span', { class: 'link-ext', 'aria-hidden': 'true' }, '↗')],
  );
}

export function dash(): VNode {
  return h('span', { class: 'muted' }, '—');
}

export function keyValueRow(label: string, value: unknown, monoValue = false): VNode {
  return h('div', { class: 'kv-row' }, [
    h('span', { class: 'kv-key' }, label),
    h('span', { class: ['kv-val', monoValue ? 'cell-mono' : ''] }, String(value ?? '—')),
  ]);
}

export function sectionTitle(title: string, hint?: string): VNode {
  return h('div', { class: 'form-section' }, [
    h('div', { class: 'form-section-title' }, title),
    hint ? h('div', { class: 'form-section-hint' }, hint) : null,
  ]);
}
