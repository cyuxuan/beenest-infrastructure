import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildIdpCreatePayload, buildIdpUpdatePayload, collectIdpConfig } from './idpForm.ts';

/**
 * 提供方表单的提交体组装。
 * <p>表单里的配置项字段名带 config. 前缀,提交时要收拢成后端要的配置对象。
 * 这一步曾经漏掉过一次:新建走的是原始表单值,配置对象根本没发出去,
 * 后端拿到的 config 是空的,启用态直接被"必须配齐配置项"挡下。</p>
 */

test('配置项字段收拢成配置对象:空值不算', () => {
  const config = collectIdpConfig({
    alias: 'wx-mini',
    'config.clientId': 'wx-app-id',
    'config.clientSecret': 'wx-app-secret',
    'config.apiBaseUrl': '',
    'config.scope': undefined,
  });
  assert.deepEqual(config, { clientId: 'wx-app-id', clientSecret: 'wx-app-secret' });
});

test('新建请求体:别名、类型、展示名、启用状态与配置项都要发出去', () => {
  const payload = buildIdpCreatePayload({
    alias: 'wx-mini',
    displayName: '微信小程序',
    providerId: 'wechat-mini',
    enabled: true,
    'config.clientId': 'wx-app-id',
    'config.clientSecret': 'wx-app-secret',
  });
  assert.deepEqual(payload, {
    alias: 'wx-mini',
    displayName: '微信小程序',
    providerId: 'wechat-mini',
    enabled: true,
    config: { clientId: 'wx-app-id', clientSecret: 'wx-app-secret' },
  });
});

test('新建请求体:配置项一个没填也要把空的配置对象发出去,让后端点名缺哪一项', () => {
  const payload = buildIdpCreatePayload({
    alias: 'wx-mini',
    displayName: '微信小程序',
    providerId: 'wechat-mini',
    enabled: false,
  });
  assert.deepEqual(payload.config, {});
});

test('更新请求体:别名与类型不可改,不进请求体', () => {
  const payload = buildIdpUpdatePayload({
    alias: 'wx-mini',
    displayName: '微信小程序(改)',
    providerId: 'wechat-mini',
    enabled: false,
    'config.clientId': 'wx-app-id',
  });
  assert.deepEqual(payload, {
    displayName: '微信小程序(改)',
    enabled: false,
    config: { clientId: 'wx-app-id' },
  });
  assert.equal((payload as Record<string, unknown>).alias, undefined);
  assert.equal((payload as Record<string, unknown>).providerId, undefined);
});

test('更新请求体:一个配置项都没填时省略配置对象,表示保持原配置不动', () => {
  const payload = buildIdpUpdatePayload({ displayName: '改名', enabled: true });
  assert.deepEqual(payload, { displayName: '改名', enabled: true });
  assert.equal('config' in payload, false);
});
