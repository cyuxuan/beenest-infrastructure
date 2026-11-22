import type { Ref, VNode } from 'vue';
import type { IconName } from './icons';

export interface PageResult<T> {
  pageNum: number;
  pageSize: number;
  total: number;
  pages: number;
  records: T[];
}

export interface Me {
  userId: number;
  realmId: number;
  realmKey: string;
  username: string;
  nickname: string;
  authorities: string[];
  attributes: Record<string, string>;
}

export interface ManagedApp {
  id: number;
  appCode: string;
  appName: string;
  authorizationMode: string;
  tokenProfile: string;
  enabled: boolean;
}

export interface ApiOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  query?: Record<string, unknown>;
  body?: unknown;
  silent?: boolean;
  signal?: AbortSignal;
}

export interface FilterOption {
  value: string | number;
  label: string;
}

export interface FilterDef {
  name: string;
  label: string;
  type?: 'text' | 'select' | 'datetime';
  options?: FilterOption[];
  placeholder?: string;
  width?: string;
}

export type FieldType =
  | 'text'
  | 'password'
  | 'email'
  | 'number'
  | 'textarea'
  | 'select'
  | 'switch'
  | 'tags'
  | 'multiselect'
  | 'kv'
  | 'section'
  | 'static';

/** 提供方配置项声明(与后端 ProviderConfigProperty 对应)。 */
export interface ProviderConfigProperty {
  name: string;
  label: string;
  type: 'STRING' | 'TEXT' | 'PASSWORD' | 'NUMBER' | 'BOOLEAN' | 'URL';
  required: boolean;
  secret: boolean;
  defaultValue?: string | null;
  helpText?: string | null;
}

/** 提供方类型元数据(与后端 ProviderTypeInfo 对应)。 */
export interface ProviderTypeInfo {
  providerId: string;
  name: string;
  /** 登录形态:REDIRECT 浏览器跳转 / CREDENTIAL 凭据直传 */
  loginModes: string[];
  /** 该类型在标准令牌端点上的自定义授权类型(无则为空) */
  grantType?: string | null;
  configProperties: ProviderConfigProperty[];
}

export interface FieldDef {
  name: string;
  label: string;
  type?: FieldType;
  required?: boolean;
  placeholder?: string;
  help?: string;
  full?: boolean;
  span?: 1 | 2;
  disabled?: boolean;
  mono?: boolean;
  rows?: number;
  /** 支持静态数组或 ref(异步拉取的选项源,如主题清单) */
  options?: FilterOption[] | Ref<FilterOption[]>;
  onText?: string;
  offText?: string;
  autocomplete?: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  defaultValue?: unknown;
  /** 编辑态留空时是否从提交体中省略(默认保留空串,以便服务端清空字段) */
  omitWhenEmpty?: boolean;
  /** 创建态留空时也提交空串 */
  keepEmptyOnCreate?: boolean;
  min?: number;
  max?: number;
  step?: number;
}

export type CellRender<T> = (row: T) => VNode | string | number | null;

export interface ColumnDef<T> {
  key?: keyof T & string;
  label: string;
  width?: string;
  align?: 'left' | 'right' | 'center';
  className?: string;
  render?: CellRender<T>;
}

export interface RowAction<T> {
  label: string;
  perm?: string;
  danger?: boolean;
  hidden?: (row: T) => boolean;
  onClick: (row: T, ctx?: ResourceContext<T>) => unknown;
}

export interface BatchAction<T> {
  label: string;
  perm?: string;
  danger?: boolean;
  onClick: (ids: number[], ctx: ResourceContext<T>) => unknown;
}

export interface ResourceContext<T> {
  reload: () => Promise<void>;
  openEditor: (row?: T) => void;
  remove: (row: T) => void;
}

export interface ResourceForm<T> {
  title: string;
  createTitle?: string;
  editTitle?: string;
  subtitle?: string;
  hint?: string;
  /**
   * 表单字段。
   * <p>写成函数时按当前取值重算:动态表单靠它实现"改一个选项,其余字段跟着换"
   * (比如选了提供方类型,配置项换成该类型声明的字段)。</p>
   */
  fields:
    | FieldDef[]
    | ((isEdit: boolean, row?: T, values?: Record<string, any>) => FieldDef[] | Promise<FieldDef[]>);
  editFields?:
    | FieldDef[]
    | ((isEdit: boolean, row?: T, values?: Record<string, any>) => FieldDef[] | Promise<FieldDef[]>);
  /** 初始值:写成函数时按打开表单的时刻求值(如默认类型取决于当时已加载的扩展包)。 */
  defaults?: Record<string, unknown> | (() => Record<string, unknown>);
  fromRow?: (row: T) => Record<string, unknown>;
  toCreate?: (values: Record<string, any>) => unknown;
  /** 创建成功后的回调:用于一次性展示服务端生成且不可再读取的凭据(如客户端密钥)。 */
  onCreated?: (created: any) => void;
  toUpdate?: (values: Record<string, any>, row: T) => unknown;
  beforeSubmit?: (values: Record<string, any>, isEdit: boolean, row?: T) => string | void | Promise<string | void>;
  validate?: (values: Record<string, any>) => string | void;
}

export interface HeaderAction {
  label: string;
  icon?: IconName;
  perm?: string;
  primary?: boolean;
  /**
   * 点击回调。
   * <p>{@code filters} 是当前已生效的筛选条件:导出类动作要按同一批条件取数,
   * 否则"看到的"与"导出的"不是同一批数据。</p>
   */
  onClick: (ctx: { reload: () => Promise<void>; filters: Record<string, string> }) => unknown;
}

export interface ResourceConfig<T = Record<string, any>> {
  title: string;
  desc: string;
  endpoint: string;
  permCreate?: string | false;
  permDelete?: string;
  createLabel?: string;
  deleteName?: string;
  rowKey?: string;
  rowName?: (row: T) => string;
  filters?: FilterDef[];
  initialFilters?: Record<string, string> | (() => Record<string, string>);
  pageSize?: number;
  paged?: boolean;
  columns: ColumnDef<T>[];
  rowActions?: (row: T, ctx: ResourceContext<T>) => RowAction<T>[];
  batchActions?: BatchAction<T>[];
  rowClick?: (row: T) => void;
  form?: ResourceForm<T>;
  headerActions?: HeaderAction[];
  cardTitle?: string;
  emptyText?: string;
  pageSizeOptions?: number[];
  rowActionsLabel?: string;
  /**
   * 响应整形:把接口返回的数据转成表格行。
   * <p>树形数据(父子嵌套)用它摊平成带层级的行,普通列表不需要。</p>
   */
  transform?: (raw: any) => any[];
  /**
   * 是否在前端再做一次筛选。
   * <p>树形场景要关掉:命中节点的后端已经连同祖先一起返回,
   * 前端再按同一条件过滤一遍,会把祖先筛掉,层级就断了。</p>
   */
  clientFilter?: boolean;
}

export interface ToastItem {
  id: number;
  message: string;
  type: 'info' | 'success' | 'error' | 'warn';
}

export interface ConfirmOptions {
  title?: string;
  message: string;
  detail?: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
}
