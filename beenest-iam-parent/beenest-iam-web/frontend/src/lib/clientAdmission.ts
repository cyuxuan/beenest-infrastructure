/**
 * 客户端准入策略的表单组装。
 * <p>白名单是"择一"语义:留空表示不限制(沿用域级可用方式)。
 * 提交前必须归一化——空串、null、重复项都要清掉,
 * 否则后端会把 [""] 当成一个非法登录方式而拒绝保存。</p>
 */

/** 全部第一因素选项(与后端 LoginMethod 词表一一对应)。 */
export const LOGIN_METHOD_OPTIONS = [
  { value: 'PASSWORD', label: '账号口令' },
  { value: 'LDAP', label: '域目录(LDAP/AD)' },
  { value: 'SMS', label: '短信验证码' },
  { value: 'WECHAT', label: '微信(小程序/扫码)' },
  { value: 'OIDC', label: '外部 OIDC' },
  { value: 'SAML', label: '外部 SAML' },
];

/** 表单取值。 */
export type ClientAdmissionValues = {
  allowedLoginTypes?: unknown;
  requireMfa?: unknown;
};

/** 准入字段的提交体。 */
export interface ClientAdmissionPayload {
  allowedLoginTypes: string[];
  requireMfa: boolean;
}

/** 合法登录方式代码集合。 */
const VALID_CODES = new Set(LOGIN_METHOD_OPTIONS.map((o) => o.value));

/**
 * 归一化白名单:只保留合法代码,去重,保持出现顺序。
 * <p>顺序保留而不是排序:控制台里管理员勾选的顺序本身就是他的表达,
 * 排序会让回显与提交对不上。</p>
 */
export function normalizeAllowedLoginTypes(values: unknown): string[] {
  if (!Array.isArray(values)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  (values as unknown[]).forEach((raw) => {
    const code = String(raw ?? '').trim().toUpperCase();
    if (!VALID_CODES.has(code) || seen.has(code)) return;
    seen.add(code);
    out.push(code);
  });
  return out;
}

/** 组装准入字段的提交体。 */
export function buildClientAdmissionPayload(values: ClientAdmissionValues): ClientAdmissionPayload {
  return {
    allowedLoginTypes: normalizeAllowedLoginTypes(values.allowedLoginTypes),
    requireMfa: Boolean(values.requireMfa),
  };
}
