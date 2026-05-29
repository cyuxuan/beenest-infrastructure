# CAS 注册页面企业级 UI 重构设计

## 概述

为 beenest-cas 的 CAS 原生 account-registration 模块创建自定义注册模板（`casRegisterView.html`），实现与登录页视觉统一的双栏分屏布局，保留 CAS 原生邮箱激活流程，满足企业级 UI 设计美学和生产投入要求。

## 设计决策

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 页面布局 | 双栏分屏（61.8% 品牌 + 38.2% 表单） | 与登录页视觉统一，用户体验连贯 |
| 注册流程 | 单页表单 + CAS 原生邮箱激活 | 简洁高效，CAS 原生流程不受影响 |
| 密码输入 | 密码 + 确认密码 + 实时强度指示器 | 企业级安全标准 |
| 验证码 | 依赖 CAS 原生邮箱激活机制 | 保持原生 CAS 流程 |
| 实现方案 | 自定义模板覆盖（方案 A） | CAS 模板覆盖机制天生支持，不触碰已稳定的登录页 |

## 页面架构

双栏分屏布局，与登录页 `casLoginView.html` 结构一致：

```
┌─────────────────────────────────────────────────────────┐
│  casRegisterView.html (body.beenest-login)              │
│  ┌──────────────────────┬────────────────────────────┐  │
│  │   品牌面板 (61.8%)    │    注册表单面板 (38.2%)     │  │
│  │                      │                            │  │
│  │  ● mesh gradient 动画 │   标题：创建新账号          │  │
│  │  ● 品牌标识/标语      │   副标题：加入数界探索平台   │  │
│  │  ● "已有账号？去登录" │   用户名                    │  │
│  │                      │   姓（firstName）            │  │
│  │                      │   名（lastName）             │  │
│  │                      │   邹箱                       │  │
│  │                      │   用户类型（卡片式单选）       │  │
│  │                      │   手机号（可选）              │  │
│  │                      │   密码 + 强度指示器           │  │
│  │                      │   确认密码                    │  │
│  │                      │   同意服务条款 checkbox       │  │
│  │                      │   [注 册] 按钮               │  │
│  └──────────────────────┴────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

## 表单字段

保留 CAS 原生 `account-registration.json` 的全部字段（含 password）：

| 字段 | CAS 字段名 | 类型 | 必填 | 前端校验规则 |
|------|-----------|------|------|-------------|
| 用户名 | `username` | text | 是 | 4-20位，字母开头，仅字母数字下划线 |
| 姓 | `firstName` | text | 是 | 1-15位，中文或英文 |
| 名 | `lastName` | text | 是 | 1-15位，中文或英文 |
| 邹箱 | `email` | email | 是 | 合法邮箱格式（接收激活链接） |
| 用户类型 | `userType` | select | 是 | CUSTOMER（客户）/ PILOT（飞手） |
| 手机号 | `phone` | tel | 否 | 11位中国手机号 `^1[3-9]\d{9}$` |
| 密码 | `password` | password | 是 | 8-64位，含字母+数字，实时强度指示 |

**密码交互**：密码输入框 + 确认密码输入框 + 实时强度指示器（弱/中/强，颜色渐变红→黄→绿）+ 密码规则提示文字 + 显示/隐藏密码切换按钮。

**用户类型选择器**：不使用原生 `<select>`，改为卡片式单选组件——两个并排卡片（客户 / 飞手），选中态带渐变边框 + 图标，更符合企业级美学。

## 视觉风格

延续登录页 Quantum Aurora 设计系统：

- **右侧面板背景**：深色 `#0d1117`，卡片容器 `#30363d` + subtle glass effect
- **输入框**：MD3 outlined 风格，聚焦时 `#00d4aa` 描边 + 浮动标签动画
- **密码强度指示器**：三段式进度条，弱（红 `#ef4444`）→ 中（黄 `#f59e0b`）→ 强（绿 `#00d4aa`）
- **主按钮**：渐变 `#00d4aa → #00b4d8`，hover 微光扫过效果
- **品牌面板**：与登录页完全相同的 mesh gradient + 标语动画
- **服务条款**：底部 checkbox + 可点击链接文字
- **错误状态**：输入框红色描边 `#dc2626` + 下方红色提示文字
- **链接**："已有账号？去登录" 在左侧品牌面板底部，风格与登录页的 "注册账号" 链接对称

## 数据流

```
用户填写表单 → CAS AccountRegistration Webflow (_eventId=signup)
  → BeenestAccountRegistrationRequestValidator (初始校验, requirePassword=false)
  → CAS 发送激活邮件
  → 用户填写密码 (webflow 继续到最终阶段)
  → BeenestAccountRegistrationProvisioner (最终校验 requirePassword=true, 写入 cas_user)
  → CAS 邹箱激活确认
  → 账号激活完成
```

CAS 原生 account-registration webflow 是多阶段的：初始注册 → 邹箱激活 → 密码设置/确认。自定义模板仅覆盖初始注册阶段的视图 (`casRegisterView`)，后续激活和密码设置阶段仍由 CAS 原生模板处理。

## 前端交互逻辑

### 表单校验（beenest-register.js）

- **即时校验**：每个字段失焦时触发（blur event）
- **用户名**：4-20位，字母开头，仅字母数字下划线，实时查重（debounce 300ms，调用 CAS API）
- **密码强度**：实时计算，规则：长度 ≥ 8、含大写字母、含小写字母、含数字、含特殊字符，满足 1 条=弱，3 条=中，5 条=强
- **确认密码**：与密码一致性实时比对
- **邮箱**：正则校验格式
- **手机号**：正则校验 `^1[3-9]\d{9}$`
- **提交前**：全字段再次校验，有错误阻止提交并滚动到第一个错误字段

### 用户类型卡片选择器

- 两个并排卡片 `<div>`，点击选中，hidden input 同步值
- 选中态：渐变边框 `border: 2px solid; border-image: linear-gradient(...)` + 背景微亮
- 未选中态：默认灰色边框 + 暗背景
- 图标：客户用无人机 SVG，飞手用飞行员 SVG

### 密码显示/隐藏

- 切换按钮在输入框右侧（eye/eye-off SVG icon）
- 点击切换 `type="password"` ↔ `type="text"`

### 返回登录

- 左侧品牌面板底部 "已有账号？去登录 →" 链接
- 点击跳转 `/cas/login`

## 错误处理

- **前端即时校验**：字段下方红色提示文字，输入框红色描边
- **CAS 服务端错误**：复用 CAS webflow 的 `#fields.hasErrors('*')` 错误消息机制，以 `md-alert md-alert-error` 组件展示
- **重复检测**：用户名/邮箱/手机号重复由 `BeenestAccountRegistrationRequestValidator` 处理，服务端返回错误后前端展示

## 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `templates/account-registration/casRegisterView.html` | **新建** | 双栏分屏注册页模板 |
| `static/css/beenest-login.css` | **修改** | 追加注册页专用样式（`.register-*` 作用域） |
| `static/js/beenest-register.js` | **新建** | 前端表单校验、密码强度、用户类型卡片交互 |
| `account-registration/account-registration.json` | **不变** | 保留全部原生字段（含 password） |

## 不做的事

- 不修改 `BeenestAccountRegistrationProvisioner` 或 `BeenestAccountRegistrationRequestValidator`
- 不修改 `account-registration.json`
- 不重构 `casLoginView.html`
- 不提取 fragment（两个页面的左侧面板 HTML 重复量很小，不值得引入 fragment 抽象）
- 不实现短信验证码（依赖 CAS 原生邮箱激活机制）
- 不修改 CAS webflow 配置