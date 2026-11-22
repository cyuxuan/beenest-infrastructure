/** 当前登录用户(门户只取展示所需字段)。 */
export interface PortalMe {
  username: string;
  nickname?: string | null;
  realmKey?: string | null;
  authorities?: string[];
}

/** 门户应用卡片,字段与 launchpad 接口契约一致。 */
export interface PortalApp {
  appCode: string;
  appName: string;
  description?: string | null;
  iconUrl?: string | null;
  ssoMode?: string | null;
  /** 是否可进入:缺入口地址或应用已停用时为 false */
  launchable?: boolean;
  /** 不可进入的原因,可直接展示 */
  launchHint?: string | null;
  category?: string | null;
  menuMode?: string | null;
}
