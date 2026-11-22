import { toast } from './toast';

export function fmtTime(value: unknown): string {
  if (!value) return '—';
  return String(value).replace('T', ' ').replace(/\.\d+$/, '').slice(0, 16);
}

export function fmtDate(value: unknown): string {
  if (!value) return '—';
  return String(value).slice(0, 10);
}

export function relTime(value: unknown): string {
  if (!value) return '—';
  const time = new Date(String(value).replace(' ', 'T'));
  if (Number.isNaN(time.getTime())) return fmtTime(value);
  const diff = Date.now() - time.getTime();
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  if (diff < 2_592_000_000) return `${Math.floor(diff / 86_400_000)} 天前`;
  return String(fmtTime(value)).slice(0, 10);
}

export function nowLocalInput(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

export function nowClock(): string {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date());
}

/** 仅放行 http/https,避免 javascript: 等伪协议被直接放进 href。 */
export function safeHttpUrl(value: unknown): string | null {
  if (!value) return null;
  try {
    const url = new URL(String(value), location.origin);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null;
  } catch {
    return null;
  }
}

export function initial(value: unknown): string {
  return String(value || '?').trim().slice(0, 1).toUpperCase() || '?';
}

export function percent(value: number, total: number): number {
  if (!total || total <= 0) return 0;
  return Math.min(100, Math.max(0, Math.round((value / total) * 100)));
}

export function shortId(value: unknown, length = 12): string {
  const text = String(value ?? '');
  return text.length > length ? `${text.slice(0, length)}…` : text || '—';
}

/**
 * 复制文本到剪贴板,返回是否写入成功。
 * <p>安全上下文使用 Clipboard API;非安全上下文或权限被拒绝时退回 execCommand。
 * 回退输入框带 data-focus-exempt,浮层焦点陷阱放行该输入框,避免焦点被抢走导致复制空内容。</p>
 *
 * @param text    待复制文本
 * @param message 复制成功后的提示文案
 * @return 剪贴板确认写入时为 true
 */
export async function copyText(text: string, message = '已复制到剪贴板'): Promise<boolean> {
  if (!text) {
    toast('没有可复制的内容', 'warn', 2400);
    return false;
  }
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      toast(message, 'success', 1800);
      return true;
    } catch {
      // 剪贴板权限被拒绝或文档失焦,继续走 execCommand 回退
    }
  }
  if (copyBySelection(text)) {
    toast(message, 'success', 1800);
    return true;
  }
  toast('复制失败,请手动选中内容后复制', 'warn', 3200);
  return false;
}

/**
 * 用临时只读文本框选中文本并执行 execCommand 复制。
 * <p>文本框固定在视口内一角且透明,保留渲染状态;复制结束后焦点还给原元素。</p>
 *
 * @param text 待复制文本
 * @return execCommand 报告复制成功时为 true
 */
function copyBySelection(text: string): boolean {
  const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const area = document.createElement('textarea');
  area.value = text;
  area.readOnly = true;
  area.setAttribute('data-focus-exempt', '');
  area.style.position = 'fixed';
  area.style.top = '0';
  area.style.left = '0';
  area.style.width = '1px';
  area.style.height = '1px';
  area.style.padding = '0';
  area.style.border = '0';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.focus();
  area.setSelectionRange(0, text.length);
  let copied = false;
  try {
    copied = document.execCommand('copy');
  } catch {
    copied = false;
  }
  area.remove();
  if (previous?.isConnected) previous.focus({ preventScroll: true });
  return copied;
}

export function debounce<T extends (...args: any[]) => void>(fn: T, wait = 300): T {
  let timer: number | undefined;
  return ((...args: Parameters<T>) => {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => fn(...args), wait);
  }) as T;
}
