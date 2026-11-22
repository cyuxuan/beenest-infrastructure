import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  LOGIN_METHOD_OPTIONS,
  normalizeAllowedLoginTypes,
  buildClientAdmissionPayload,
} from './clientAdmission.ts';

/**
 * 客户端准入字段的表单组装。
 * <p>白名单是"择一"语义:留空表示不限制,不能发成 [""] 或 [null],
 * 后端拿到空串与空数组的行为不同,发错会让"不限制"变成"谁都登不进去"。</p>
 */

test('登录方式选项覆盖全部六个第一因素', () => {
  assert.deepEqual(
    LOGIN_METHOD_OPTIONS.map((o) => o.value).sort(),
    ['LDAP', 'OIDC', 'PASSWORD', 'SAML', 'SMS', 'WECHAT'],
  );
});

test('归一化:去掉空值与重复,保持选项顺序', () => {
  assert.deepEqual(normalizeAllowedLoginTypes(['SMS', '', 'PASSWORD', 'SMS']), ['SMS', 'PASSWORD']);
});

test('归一化:全空表示不限制,得到空数组', () => {
  assert.deepEqual(normalizeAllowedLoginTypes([]), []);
  assert.deepEqual(normalizeAllowedLoginTypes(undefined), []);
});

test('提交体:白名单为空时发空数组,强制 MFA 发布尔', () => {
  const payload = buildClientAdmissionPayload({ allowedLoginTypes: [], requireMfa: true });
  assert.deepEqual(payload, { allowedLoginTypes: [], requireMfa: true });
});

test('提交体:未知选项被丢弃,不原样发给后端', () => {
  const payload = buildClientAdmissionPayload({
    allowedLoginTypes: ['SMS', 'HACK'],
    requireMfa: false,
  });
  assert.deepEqual(payload.allowedLoginTypes, ['SMS']);
});
