import type { ApiOptions, ProviderTypeInfo } from './types';
import { notifyError } from './toast';

export class ApiError extends Error {
  status: number;
  code?: number;

  constructor(message: string, status: number, code?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

const CSRF_HEADER = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-XSRF-TOKEN';

function csrfToken(): string {
  const meta = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
  if (meta) return meta;
  const matched = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return matched ? decodeURIComponent(matched[1]) : '';
}

function redirectToLogin() {
  const back = encodeURIComponent(location.pathname + location.search + location.hash);
  // 用 replace 而不是 assign,避免用户回退后反复触发失效跳转
  location.replace(`/login?return_to=${back}`);
}

export function buildQuery(query?: Record<string, unknown>): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}

export async function api<T = unknown>(path: string, options: ApiOptions = {}): Promise<T> {
  const { method = 'GET', query, body, silent = false, signal } = options;
  const url = path + buildQuery(query);
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = csrfToken();
  if (token) headers[CSRF_HEADER] = token;

  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers,
      credentials: 'same-origin',
      signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (error) {
    if ((error as Error).name === 'AbortError') throw error;
    const message = '网络异常,请检查连接后重试';
    if (!silent) notifyError(message);
    throw new ApiError(message, 0, -1);
  }

  if (response.status === 401) {
    redirectToLogin();
    throw new ApiError('登录状态已失效', 401, 401);
  }

  const text = await response.text();
  let payload: any = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok || (payload && payload.code !== 0)) {
    const message = payload?.message || `请求失败(HTTP ${response.status})`;
    if (!silent) notifyError(message);
    throw new ApiError(message, response.status, payload?.code);
  }

  return (payload ? payload.data : null) as T;
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/**
 * 已注册的身份提供方类型及其配置项声明。
 * <p>类型由容器里的扩展包自报,新增扩展包后这里多一项,控制台据此渲染
 * 类型下拉与配置表单,不必为新类型改前端。</p>
 */
export async function providerTypes(): Promise<ProviderTypeInfo[]> {
  return (await api<ProviderTypeInfo[]>('/api/v1/admin/identity-providers/provider-types')) || [];
}

/**
 * 下载文件响应(导出场景)。
 * <p>导出接口返回的是文件而不是 Result JSON,不能走 api() 的统一解包;
 * 但鉴权头、401 跳登录、错误提示要与 api() 保持一致,否则会出现
 * "导出按钮点了没反应"这类无从判断原因的问题。</p>
 */
export async function download(path: string, options: ApiOptions = {}): Promise<{ blob: Blob; filename: string }> {
  const { query, silent = false } = options;
  const url = path + buildQuery(query);
  const headers: Record<string, string> = { Accept: '*/*' };
  const token = csrfToken();
  if (token) headers[CSRF_HEADER] = token;

  let response: Response;
  try {
    response = await fetch(url, { method: 'GET', headers, credentials: 'same-origin' });
  } catch (error) {
    const message = '网络异常,请检查连接后重试';
    if (!silent) notifyError(message);
    throw new ApiError(message, 0, -1);
  }

  if (response.status === 401) {
    redirectToLogin();
    throw new ApiError('登录状态已失效', 401, 401);
  }
  if (!response.ok) {
    // 失败响应体是统一的 Result JSON,尽量取出可读消息
    let message = `导出失败(HTTP ${response.status})`;
    try {
      const text = await response.text();
      const payload = text ? JSON.parse(text) : null;
      if (payload?.message) message = payload.message;
    } catch {
      // 响应体不是 JSON,保留状态码文案
    }
    if (!silent) notifyError(message);
    throw new ApiError(message, response.status);
  }

  const disposition = response.headers.get('Content-Disposition') || '';
  const matched = disposition.match(/filename="?([^";]+)"?/);
  return { blob: await response.blob(), filename: matched ? matched[1] : 'export.csv' };
}

/**
 * 触发浏览器下载。
 * <p>用临时 anchor + objectURL,下载完立即释放:
 * 留着 objectURL 会让 Blob 一直驻留内存,导出几次就吃掉不少。</p>
 */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error ?? '未知错误');
}
