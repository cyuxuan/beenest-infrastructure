/**
 * 身份提供方表单的提交体组装。
 * <p>控制台的提供方表单是动态的:配置项字段由后端按提供方类型下发,
 * 字段名带 `config.` 前缀,提交时要收拢成后端要的配置对象。
 * 这里把组装逻辑单独拎出来,不掺界面:提交体错了,界面看起来一切正常,
 * 后端却收到一个空的配置对象。</p>
 */

/** 配置项字段的前缀。 */
const CONFIG_PREFIX = 'config.';

/** 提供方表单的取值。 */
export type IdpFormValues = Record<string, any>;

/** 新建提供方的请求体。 */
export interface IdpCreatePayload {
  alias: string;
  providerId: string;
  displayName: string;
  enabled: boolean;
  config: Record<string, string>;
}

/** 更新提供方的请求体。 */
export interface IdpUpdatePayload {
  displayName: string;
  enabled: boolean;
  config?: Record<string, string>;
}

/**
 * 收集表单里的配置项字段,收拢成配置对象。
 * <p>空值不算:配置项是只写不读的,编辑态留空表示保持原值,
 * 把空串发过去会被当成"把这个配置清空"。</p>
 */
export function collectIdpConfig(values: IdpFormValues): Record<string, string> {
  const config: Record<string, string> = {};
  Object.entries(values || {}).forEach(([key, value]) => {
    if (!key.startsWith(CONFIG_PREFIX)) return;
    if (value === undefined || value === null || value === '') return;
    config[key.slice(CONFIG_PREFIX.length)] = String(value);
  });
  return config;
}

/**
 * 新建提供方的请求体。
 * <p>配置对象即使为空也要发出去:启用态缺配置项时,后端要能点名缺哪一项,
 * 而不是收到一个没有 config 字段的请求体后给出含糊的提示。</p>
 */
export function buildIdpCreatePayload(values: IdpFormValues): IdpCreatePayload {
  return {
    alias: String(values?.alias ?? '').trim(),
    providerId: String(values?.providerId ?? '').trim(),
    displayName: String(values?.displayName ?? '').trim(),
    enabled: Boolean(values?.enabled),
    config: collectIdpConfig(values),
  };
}

/**
 * 更新提供方的请求体。
 * <p>别名与提供方类型创建后不可改,后端的更新接口也不收这两个字段,
 * 带上只会让人以为类型能改。配置项一个都没填时省略配置对象,
 * 表示保持原配置不动(填了就是整体覆盖)。</p>
 */
export function buildIdpUpdatePayload(values: IdpFormValues): IdpUpdatePayload {
  const payload: IdpUpdatePayload = {
    displayName: String(values?.displayName ?? '').trim(),
    enabled: Boolean(values?.enabled),
  };
  const config = collectIdpConfig(values);
  if (Object.keys(config).length) {
    payload.config = config;
  }
  return payload;
}
