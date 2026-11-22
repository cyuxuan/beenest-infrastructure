import type { IconName } from '@/lib/icons';
import { auth, can } from '@/stores/auth';

export interface NavItem {
  path: string;
  label: string;
  icon: IconName;
  perm?: string;
  desc: string;
  group: string;
  /** 仅当前账号至少管理一个应用时可见 */
  requiresManagedApp?: boolean;
  load: () => Promise<{ default: any }>;
}

export interface NavGroup {
  key: string;
  label: string;
  hint: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'overview',
    label: '概览',
    hint: '态势与入口',
    items: [
      {
        path: '/dashboard',
        label: '运营概览',
        icon: 'grid',
        perm: 'dashboard:view',
        desc: '账号、会话、客户端存量与今日认证态势',
        group: '概览',
        load: () => import('@/views/DashboardView.vue'),
      },
    ],
  },
  {
    key: 'workspace',
    label: '应用工作台',
    hint: '应用自治 · 独立交付',
    items: [
      {
        path: '/workspace',
        label: '应用管理',
        icon: 'apps',
        desc: '应用管理员维护本应用菜单、角色、权限与角色映射',
        group: '应用工作台',
        requiresManagedApp: true,
        load: () => import('@/views/AppWorkspaceView.vue'),
      },
    ],
  },
  {
    key: 'identity',
    label: '身份与访问',
    hint: '账号 · 组织 · 授权',
    items: [
      {
        path: '/users',
        label: '用户',
        icon: 'users',
        perm: 'user:view',
        desc: '开户、资料、角色、口令与全端下线',
        group: '身份与访问',
        load: () => import('@/views/UsersView.vue'),
      },
      {
        path: '/groups',
        label: '组织架构',
        icon: 'layers',
        perm: 'group:view',
        desc: '部门与用户组、部门负责人、成员任职与组继承角色',
        group: '身份与访问',
        load: () => import('@/views/GroupsView.vue'),
      },
      {
        path: '/positions',
        label: '岗位',
        icon: 'hexagon',
        perm: 'position:view',
        desc: '职务字典与岗位默认角色,任职即继承',
        group: '身份与访问',
        load: () => import('@/views/PositionsView.vue'),
      },
      {
        path: '/roles',
        label: '角色',
        icon: 'shield',
        perm: 'role:view',
        desc: '域角色 / 客户端角色、权限与菜单授予',
        group: '身份与访问',
        load: () => import('@/views/RolesView.vue'),
      },
      {
        path: '/permissions',
        label: '权限',
        icon: 'key',
        perm: 'permission:view',
        desc: '平台预置的原子权限码清单',
        group: '身份与访问',
        load: () => import('@/views/PermissionsView.vue'),
      },
    ],
  },
  {
    key: 'protocol',
    label: '应用与协议',
    hint: '客户端 · 联邦 · 门户',
    items: [
      {
        path: '/clients',
        label: '客户端',
        icon: 'apps',
        perm: 'client:view',
        desc: 'OAuth2 / OIDC 客户端登记与密钥生命周期',
        group: '应用与协议',
        load: () => import('@/views/ClientsView.vue'),
      },
      {
        path: '/client-scopes',
        label: '客户端作用域',
        icon: 'scope',
        perm: 'client-scope:view',
        desc: '授权边界的原子作用域清单',
        group: '应用与协议',
        load: () => import('@/views/ClientScopesView.vue'),
      },
      {
        path: '/protocol-mappers',
        label: '协议映射',
        icon: 'mapping',
        perm: 'protocol-mapper:view',
        desc: '用户属性到令牌 claim 的映射',
        group: '应用与协议',
        load: () => import('@/views/ProtocolMappersView.vue'),
      },
      {
        path: '/identity-providers',
        label: '身份提供方',
        icon: 'idp',
        perm: 'idp:view',
        desc: '外部 IdP 联邦接入与属性映射',
        group: '应用与协议',
        load: () => import('@/views/IdentityProvidersView.vue'),
      },
      {
        path: '/saml-apps',
        label: 'SAML 应用',
        icon: 'key',
        perm: 'saml-sp:view',
        desc: '本平台作为 IdP 为存量应用签发断言',
        group: '应用与协议',
        load: () => import('@/views/SamlServiceProvidersView.vue'),
      },
      {
        path: '/application-services',
        label: '应用门户',
        icon: 'globe',
        perm: 'app-service:view',
        desc: '启动台应用条目、可见性与授权名单',
        group: '应用与协议',
        load: () => import('@/views/ApplicationServicesView.vue'),
      },
      {
        path: '/menus',
        label: '应用菜单',
        icon: 'menu',
        perm: 'menu:view',
        desc: '按应用维护菜单树与挂载权限',
        group: '应用与协议',
        load: () => import('@/views/MenusView.vue'),
      },
    ],
  },
  {
    key: 'governance',
    label: '治理与策略',
    hint: '租户 · 口令 · 风控',
    items: [
      {
        path: '/realms',
        label: '身份域',
        icon: 'realm',
        perm: 'realm:view',
        desc: '多租户隔离、会话策略与签名密钥',
        group: '治理与策略',
        load: () => import('@/views/RealmsView.vue'),
      },
      {
        path: '/security-policies',
        label: '安全策略',
        icon: 'policy',
        perm: 'security-policy:view',
        desc: '口令复杂度与登录风控',
        group: '治理与策略',
        load: () => import('@/views/SecurityPoliciesView.vue'),
      },
      {
        path: '/ldap',
        label: 'LDAP 用户联合',
        icon: 'users',
        perm: 'ldap-config:view',
        desc: '企业目录兜底认证与 JIT 建号',
        group: '治理与策略',
        load: () => import('@/views/LdapView.vue'),
      },
      {
        path: '/mail-config',
        label: '发信配置',
        icon: 'inbox',
        perm: 'mail-config:view',
        desc: '域级 SMTP 通道与测试发送',
        group: '治理与策略',
        load: () => import('@/views/MailConfigView.vue'),
      },
      {
        path: '/mail-templates',
        label: '邮件模板',
        icon: 'consent',
        perm: 'mail-config:view',
        desc: '找回口令、邮箱验证等通知的措辞与品牌覆盖',
        group: '治理与策略',
        load: () => import('@/views/MailTemplatesView.vue'),
      },
      {
        path: '/attribute-defs',
        label: '扩展属性',
        icon: 'attr',
        perm: 'attribute-def:view',
        desc: '用户模型的元数据契约',
        group: '治理与策略',
        load: () => import('@/views/AttributeDefsView.vue'),
      },
    ],
  },
  {
    key: 'monitor',
    label: '运行监控',
    hint: '会话 · 令牌 · 合规',
    items: [
      {
        path: '/sessions',
        label: '在线会话',
        icon: 'session',
        perm: 'session:view',
        desc: '实时登录态、强制下线与登出广播',
        group: '运行监控',
        load: () => import('@/views/SessionsView.vue'),
      },
      {
        path: '/tokens',
        label: '令牌',
        icon: 'token',
        perm: 'token:view',
        desc: '按账号查看授权并即时吊销',
        group: '运行监控',
        load: () => import('@/views/TokensView.vue'),
      },
      {
        path: '/consents',
        label: '授权同意',
        icon: 'consent',
        perm: 'consent:view',
        desc: '用户对客户端 scope 的同意记录',
        group: '运行监控',
        load: () => import('@/views/ConsentsView.vue'),
      },
      {
        path: '/notification-logs',
        label: '通知投递',
        icon: 'inbox',
        perm: 'notification:view',
        desc: '口令找回与安全提醒的投递结果与失败原因',
        group: '运行监控',
        load: () => import('@/views/NotificationLogsView.vue'),
      },
      {
        path: '/login-events',
        label: '登录事件',
        icon: 'login',
        perm: 'login-event:view',
        desc: '认证轨迹与来源 IP 溯源',
        group: '运行监控',
        load: () => import('@/views/LoginEventsView.vue'),
      },
      {
        path: '/audit-logs',
        label: '审计日志',
        icon: 'audit',
        perm: 'audit:view',
        desc: '管理操作全留痕与合规取证',
        group: '运行监控',
        load: () => import('@/views/AuditLogsView.vue'),
      },
    ],
  },
];

export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

export function navItemByPath(path: string): NavItem | undefined {
  return NAV_ITEMS.find((item) => item.path === path);
}

export function navGroupByPath(path: string): NavGroup | undefined {
  return NAV_GROUPS.find((group) => group.items.some((item) => item.path === path));
}

export function canAccessNav(item: NavItem): boolean {
  if (item.requiresManagedApp && auth.managedApps.length === 0) return false;
  return can(item.perm);
}

export function firstAccessiblePath(): string | null {
  const item = NAV_ITEMS.find((entry) => canAccessNav(entry));
  return item?.path || null;
}
