import type { PortalApp } from './types';

const RECENT_KEY = 'iam.portal.recent';
const RECENT_MAX = 6;
const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];

export function greeting(date: Date): string {
  const hour = date.getHours();
  if (hour < 6) return '夜深了';
  if (hour < 9) return '早上好';
  if (hour < 12) return '上午好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

export function fmtDate(date: Date): string {
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${WEEKDAYS[date.getDay()]}`;
}

export function fmtClock(date: Date): string {
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
}

function storage(): Storage | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** 最近使用排序:置顶、去重、上限 6(纯计算,便于测试)。 */
export function recentOrder(current: string[], appCode: string): string[] {
  return [appCode, ...current.filter((code) => code !== appCode)].slice(0, RECENT_MAX);
}

/** 读取本机最近使用;存储不可用或内容损坏时静默降级为空。 */
export function readRecent(store: Storage | null = storage()): string[] {
  if (!store) return [];
  try {
    const parsed = JSON.parse(store.getItem(RECENT_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

/** 记录一次启动并返回新序;写失败不影响启动流程。 */
export function pushRecent(appCode: string, store: Storage | null = storage()): string[] {
  const next = recentOrder(readRecent(store), appCode);
  try {
    store?.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    // 本地偏好不可用(隐私模式/配额满)不影响门户使用
  }
  return next;
}

/** 关键词过滤:应用名称或编码,大小写不敏感。 */
export function filterApps(apps: PortalApp[], keyword: string): PortalApp[] {
  const text = keyword.trim().toLowerCase();
  if (!text) return apps;
  return apps.filter(
    (item) =>
      (item.appName || '').toLowerCase().includes(text) ||
      (item.appCode || '').toLowerCase().includes(text),
  );
}

/** 按分类分组,保持接口返回顺序;无分类归入"未分类"。 */
export function groupByCategory(apps: PortalApp[]): Array<[string, PortalApp[]]> {
  const groups = new Map<string, PortalApp[]>();
  for (const item of apps) {
    const key = item.category || '未分类';
    const bucket = groups.get(key);
    if (bucket) bucket.push(item);
    else groups.set(key, [item]);
  }
  return [...groups.entries()];
}

export function categoryCountOf(apps: PortalApp[]): number {
  return new Set(apps.map((item) => item.category || '未分类')).size;
}

/** 摘要行:有关键词时报匹配数,否则报总数与分类数。 */
export function summaryText(total: number, categoryCount: number, keyword: string, matched: number): string {
  return keyword.trim() ? `匹配 ${matched} / ${total} 个应用` : `共 ${total} 个应用 · ${categoryCount} 个分类`;
}

/** 英雄区副标题:空应用时引导联系管理员。 */
export function heroSubtitle(total: number, categoryCount: number): string {
  if (!total) return '当前身份域尚未为你开通应用，请联系管理员分配访问权限。';
  const suffix = categoryCount ? `，覆盖 ${categoryCount} 个业务分类` : '';
  return `已为你接入 ${total} 个应用${suffix}。点击卡片即可完成安全单点登录。`;
}
