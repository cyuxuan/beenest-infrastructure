import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  categoryCountOf,
  filterApps,
  fmtClock,
  fmtDate,
  greeting,
  groupByCategory,
  heroSubtitle,
  pushRecent,
  readRecent,
  recentOrder,
  summaryText,
} from './lib.ts';
import type { PortalApp } from './types.ts';

function app(code: string, name: string, category?: string): PortalApp {
  return { appCode: code, appName: name, category };
}

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => void map.set(key, value),
    removeItem: (key: string) => void map.delete(key),
    clear: () => map.clear(),
    key: (index: number) => [...map.keys()][index] ?? null,
    length: 0,
  } as unknown as Storage;
}

test('greeting 按小时分段', () => {
  assert.equal(greeting(new Date(2026, 0, 1, 2)), '夜深了');
  assert.equal(greeting(new Date(2026, 0, 1, 7)), '早上好');
  assert.equal(greeting(new Date(2026, 0, 1, 10)), '上午好');
  assert.equal(greeting(new Date(2026, 0, 1, 13)), '中午好');
  assert.equal(greeting(new Date(2026, 0, 1, 15)), '下午好');
  assert.equal(greeting(new Date(2026, 0, 1, 21)), '晚上好');
});

test('日期与时钟格式', () => {
  const date = new Date(2026, 8, 22, 9, 5, 3);
  assert.equal(fmtDate(date), '2026年9月22日 星期二');
  assert.equal(fmtClock(date), '09:05:03');
});

test('关键词过滤名称与编码', () => {
  const apps = [app('crm', '客户关系管理', '业务'), app('oa', '办公自动化', '业务')];
  assert.equal(filterApps(apps, '').length, 2);
  assert.equal(filterApps(apps, 'CRM').length, 1);
  assert.equal(filterApps(apps, '办公').length, 1);
  assert.equal(filterApps(apps, '无此应用').length, 0);
});

test('分组保持顺序且未分类归一', () => {
  const groups = groupByCategory([app('a', 'A', '业务'), app('b', 'B'), app('c', 'C', '业务')]);
  assert.deepEqual(groups.map(([name]) => name), ['业务', '未分类']);
  assert.equal(groups[0][1].length, 2);
});

test('分类计数去重', () => {
  assert.equal(categoryCountOf([app('a', 'A', '业务'), app('b', 'B', '业务'), app('c', 'C')]), 2);
});

test('摘要行按关键词切换', () => {
  assert.equal(summaryText(3, 2, '', 3), '共 3 个应用 · 2 个分类');
  assert.equal(summaryText(3, 2, 'crm', 1), '匹配 1 / 3 个应用');
});

test('副标题:空应用给开通提示', () => {
  assert.match(heroSubtitle(0, 0), /尚未为你开通应用/);
  assert.match(heroSubtitle(2, 1), /已为你接入 2 个应用/);
});

test('最近使用:置顶、去重、上限 6', () => {
  const store = memoryStorage();
  assert.deepEqual(readRecent(store), []);
  assert.deepEqual(pushRecent('crm', store), ['crm']);
  assert.deepEqual(pushRecent('oa', store), ['oa', 'crm']);
  assert.deepEqual(pushRecent('crm', store), ['crm', 'oa']);
  for (const code of ['a', 'b', 'c', 'd', 'e', 'f']) pushRecent(code, store);
  const many = pushRecent('g', store);
  assert.equal(many.length, 6);
  assert.equal(many[0], 'g');
  assert.ok(!many.includes('crm'));
});

test('最近使用:存储损坏或缺失时降级为空', () => {
  const store = memoryStorage();
  store.setItem('iam.portal.recent', 'not-json');
  assert.deepEqual(readRecent(store), []);
  assert.deepEqual(recentOrder([], 'crm'), ['crm']);
  assert.deepEqual(readRecent(null), []);
  assert.deepEqual(pushRecent('crm', null), ['crm']);
});
