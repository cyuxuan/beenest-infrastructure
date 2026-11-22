import { reactive } from 'vue';
import type { ManagedApp, Me } from '../lib/types';

interface AuthState {
  me: Me | null;
  perms: Set<string>;
  realmKey: string;
  managedApps: ManagedApp[];
}

export const auth = reactive<AuthState>({
  me: null,
  perms: new Set<string>(),
  realmKey: 'default',
  managedApps: [],
});

export function can(perm?: string): boolean {
  // 未声明权限码的入口(如仅按应用可见性判定)不参与权限过滤;通配符不放行
  if (!perm) return true;
  return auth.perms.has(perm);
}

export function canAny(perms?: string[]): boolean {
  return (perms || []).some((perm) => can(perm));
}

export function applyMe(me: Me) {
  auth.me = me;
  auth.perms = new Set(me.authorities || []);
  auth.realmKey = me.realmKey || 'default';
}

export function applyManagedApps(apps: ManagedApp[]) {
  auth.managedApps = apps || [];
}
