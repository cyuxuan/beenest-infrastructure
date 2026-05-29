# CAS 注册页面企业级 UI 重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 beenest-cas 创建自定义注册模板，实现与登录页视觉统一的双栏分屏企业级 UI，保留 CAS 原生邮箱激活流程。

**Architecture:** 通过 CAS 模板覆盖机制，在 `templates/acct-mgmt/` 下放置同名模板文件 `casAccountSignupView.html` 替换 CAS 默认注册视图。双栏分屏布局与登录页一致（61.8% 品牌面板 + 38.2% 表单面板），复用 `beenest-login.css` 的 MD3 设计系统。同时自定义 `casAccountSignupViewSentInfo.html` 和 `casAccountSignupViewComplete.html` 保持视觉一致性。前端交互逻辑独立为 `beenest-register.js`。

**Tech Stack:** Thymeleaf (layout dialect), CSS3 (MD3 design tokens, CSS custom properties), Vanilla JS (ES5+ 兼容), CAS Account Registration Webflow

---

## 文件结构

| 操作 | 文件路径 | 职责 |
|------|---------|------|
| 新建 | `templates/acct-mgmt/casAccountSignupView.html` | 注册表单页面（双栏分屏） |
| 新建 | `templates/acct-mgmt/casAccountSignupViewSentInfo.html` | 激活邮件发送成功提示页 |
| 新建 | `templates/acct-mgmt/casAccountSignupViewComplete.html` | 邮箱激活后密码设置页 |
| 新建 | `static/js/beenest-register.js` | 前端表单校验、密码强度、用户类型卡片交互 |
| 修改 | `static/css/beenest-login.css` | 追加注册页专用样式 |

---

### Task 1: 创建注册表单页面模板

**Files:**
- Create: `beenest-cas/beenest-cas-service/src/main/resources/templates/acct-mgmt/casAccountSignupView.html`

- [ ] **Step 1: 创建 casAccountSignupView.html**

创建注册表单页面，使用 `body.beenest-login` 双栏分屏布局。左侧品牌面板与登录页完全一致（mesh gradient 动画 + 品牌标语 + "已有账号？去登录" 链接），右侧注册表单面板使用 MD3 风格。

```html
<!DOCTYPE html>
<html xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{layout}">

<head>
  <title>Beenest SSO - 注册</title>
</head>

<!--
  全视口分屏注册页：左侧动画 mesh gradient 品牌面板 + 右侧 MD3 注册表单面板。
  body.beenest-login 触发浅色企业主题，与登录页视觉统一。
-->
<body class="beenest-login">

  <div layout:fragment="content">

    <div class="split-layout">

      <!-- ========== 左侧品牌面板（61.8%） ========== -->
      <div class="split-left">
        <div class="mesh-gradient">
          <div class="orb orb-1"></div>
          <div class="orb orb-2"></div>
          <div class="orb orb-3"></div>
          <div class="orb orb-4"></div>
        </div>
        <div class="mesh-grid"></div>

        <div class="split-left-content">
          <div class="split-brand">
            <div class="split-brand-icon">
              <svg viewBox="0 0 32 32" fill="none">
                <rect width="32" height="32" rx="8" fill="currentColor" opacity="0.15"/>
                <path d="M16 6L8 12v10l8 4 8-4V12L16 6z" fill="currentColor" opacity="0.9"/>
              </svg>
            </div>
            <div class="split-brand-text">
              <span class="split-brand-name">数界探索</span>
              <span class="split-brand-tagline">Digital Boundary Exploration</span>
            </div>
          </div>

          <div class="split-hero">
            <h2 class="split-hero-title">开启智能服务之旅<br/>注册数界探索平台</h2>
            <p class="split-hero-subtitle">创建账号，探索低空经济新边界，让无人机服务触手可及</p>
          </div>

          <div class="split-features">
            <div class="split-feature">
              <div class="split-feature-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
              </div>
              <div>
                <div class="split-feature-title">一站式账号管理</div>
                <div class="split-feature-desc">统一身份认证，安全便捷登录所有服务</div>
              </div>
            </div>
            <div class="split-feature">
              <div class="split-feature-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
              </div>
              <div>
                <div class="split-feature-title">企业级安全保障</div>
                <div class="split-feature-desc">多重加密防护，数据安全有保障</div>
              </div>
            </div>
            <div class="split-feature">
              <div class="split-feature-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="10"/><path d="M8 12l2 2 4-4"/></svg>
              </div>
              <div>
                <div class="split-feature-title">快速激活使用</div>
                <div class="split-feature-desc">邮箱验证即可启用，即刻体验平台服务</div>
              </div>
            </div>
          </div>

          <!-- 返回登录链接 -->
          <div class="split-back-link">
            <a th:href="@{/login}">
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12.5 5l-5 5 5 5"/></svg>
              已有账号？去登录
            </a>
          </div>
        </div>
      </div>

      <!-- ========== 右侧注册表单面板（38.2%） ========== -->
      <div class="split-right">
        <div class="split-right-inner">

          <!-- CAS 服务端错误消息 -->
          <div class="md-alert md-alert-error"
               th:if="${flowRequestContext.messageContext.hasErrorMessages()}" role="alert">
            <svg class="md-alert-icon" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/></svg>
            <span>
              <p th:each="message : ${flowRequestContext.messageContext.allMessages}"
                 th:utext="${message.text}" style="margin:0"></p>
            </span>
          </div>

          <div class="md-form-header">
            <h2 class="md-form-title">创建新账号</h2>
            <p class="md-form-subtitle">加入数界探索平台，开启智能服务之旅</p>
          </div>

          <form method="post" id="fm1" novalidate>

            <!-- ===== 用户名 ===== -->
            <div class="md-field register-field">
              <input type="text" id="username" name="username"
                     placeholder=" " required
                     autocapitalize="none" spellcheck="false"
                     autocomplete="username"
                     pattern="^[a-zA-Z][a-zA-Z0-9_]{3,19}$" />
              <label for="username">用户名</label>
              <div class="md-field-error" id="username-error"></div>
            </div>

            <!-- ===== 姓名（姓 + 名 并排） ===== -->
            <div class="register-name-row">
              <div class="md-field register-field">
                <input type="text" id="firstName" name="firstName"
                       placeholder=" " required
                       autocapitalize="none" spellcheck="false"
                       pattern=".{1,15}" />
                <label for="firstName">姓</label>
                <div class="md-field-error" id="firstName-error"></div>
              </div>
              <div class="md-field register-field">
                <input type="text" id="lastName" name="lastName"
                       placeholder=" " required
                       autocapitalize="none" spellcheck="false"
                       pattern=".{1,15}" />
                <label for="lastName">名</label>
                <div class="md-field-error" id="lastName-error"></div>
              </div>
            </div>

            <!-- ===== 邮箱 ===== -->
            <div class="md-field register-field">
              <input type="email" id="email" name="email"
                     placeholder=" " required
                     autocomplete="email"
                     pattern="^\S+@\S+\.\S+$" />
              <label for="email">邮箱</label>
              <div class="md-field-error" id="email-error"></div>
            </div>
            <p class="md-hint register-hint">用于接收账号激活邮件，请填写真实邮箱</p>

            <!-- ===== 用户类型（卡片式单选） ===== -->
            <div class="register-type-selector">
              <label class="register-type-label">用户类型</label>
              <div class="register-type-cards">
                <div class="register-type-card" data-value="CUSTOMER" onclick="selectUserType('CUSTOMER')">
                  <div class="register-type-card-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                      <path d="M12 2L2 7l10 5 10-5-10-5z"/>
                      <path d="M2 17l10 5 10-5"/>
                      <path d="M2 12l10 5 10-5"/>
                    </svg>
                  </div>
                  <div class="register-type-card-title">客户</div>
                  <div class="register-type-card-desc">预约无人机服务</div>
                </div>
                <div class="register-type-card" data-value="PILOT" onclick="selectUserType('PILOT')">
                  <div class="register-type-card-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                      <circle cx="12" cy="8" r="4"/>
                      <path d="M6 21v-2a6 6 0 0112 0v2"/>
                      <path d="M12 2v2"/>
                    </svg>
                  </div>
                  <div class="register-type-card-title">飞手</div>
                  <div class="register-type-card-desc">提供飞行服务</div>
                </div>
              </div>
              <input type="hidden" id="userType" name="userType" value="" required />
              <div class="md-field-error" id="userType-error"></div>
            </div>

            <!-- ===== 手机号（可选） ===== -->
            <div class="md-field register-field">
              <input type="tel" id="phone" name="phone"
                     placeholder=" "
                     autocomplete="tel"
                     pattern="^1[3-9]\d{9}$" />
              <label for="phone">手机号（可选）</label>
              <div class="md-field-error" id="phone-error"></div>
            </div>

            <!-- ===== 密码 ===== -->
            <div class="md-field register-field register-field-password">
              <input type="password" id="password" name="password"
                     placeholder=" " required
                     autocomplete="new-password"
                     oninput="updatePasswordStrength()" />
              <label for="password">密码</label>
              <button type="button" class="register-pwd-toggle" onclick="togglePasswordVisibility('password', this)" title="显示/隐藏密码">
                <svg class="pwd-icon-eye" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M1 10s3-6 9-6 9 6 9 6-3 6-9 6-9-6-9-6z"/><circle cx="10" cy="10" r="3"/></svg>
                <svg class="pwd-icon-eye-off" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" style="display:none"><path d="M3.53 2.47l14 14M1 10s3-6 9-6c1.6 0 3 .3 4.2.8M19 10s-3 6-9 6c-1.6 0-3-.3-4.2-.8M7.07 7.07A3 3 0 0112.93 12.93"/></svg>
              </button>
              <div class="md-field-error" id="password-error"></div>
            </div>

            <!-- 密码强度指示器 -->
            <div class="register-pwd-strength" id="pwdStrength">
              <div class="register-pwd-strength-bar">
                <div class="register-pwd-strength-segment" id="pwdSeg1"></div>
                <div class="register-pwd-strength-segment" id="pwdSeg2"></div>
                <div class="register-pwd-strength-segment" id="pwdSeg3"></div>
              </div>
              <span class="register-pwd-strength-text" id="pwdStrengthText"></span>
            </div>
            <div class="register-pwd-rules" id="pwdRules">
              <span data-rule="length">8-64 位字符</span>
              <span data-rule="upper">含大写字母</span>
              <span data-rule="lower">含小写字母</span>
              <span data-rule="digit">含数字</span>
              <span data-rule="special">含特殊字符</span>
            </div>

            <!-- ===== 确认密码 ===== -->
            <div class="md-field register-field register-field-password">
              <input type="password" id="confirmedPassword" name="confirmedPassword"
                     placeholder=" " required
                     autocomplete="new-password"
                     oninput="checkPasswordMatch()" />
              <label for="confirmedPassword">确认密码</label>
              <button type="button" class="register-pwd-toggle" onclick="togglePasswordVisibility('confirmedPassword', this)" title="显示/隐藏密码">
                <svg class="pwd-icon-eye" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M1 10s3-6 9-6 9 6 9 6-3 6-9 6-9-6-9-6z"/><circle cx="10" cy="10" r="3"/></svg>
                <svg class="pwd-icon-eye-off" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" style="display:none"><path d="M3.53 2.47l14 14M1 10s3-6 9-6c1.6 0 3 .3 4.2.8M19 10s-3 6-9 6c-1.6 0-3-.3-4.2-.8M7.07 7.07A3 3 0 0112.93 12.93"/></svg>
              </button>
              <div class="md-field-error" id="confirmedPassword-error"></div>
            </div>

            <!-- ===== 服务条款 ===== -->
            <div class="md-option register-terms">
              <label class="md-checkbox">
                <input type="checkbox" id="agreeTerms" name="agreeTerms" value="true" required />
                <span class="md-checkbox-box"></span>
                <span>我已阅读并同意<a href="#" target="_blank">服务条款</a>和<a href="#" target="_blank">隐私政策</a></span>
              </label>
            </div>

            <!-- Hidden CAS form fields -->
            <div class="md-hidden">
              <input type="hidden" name="execution" th:value="${flowExecutionKey}" />
              <input type="hidden" name="_eventId" value="submit" />
            </div>

            <!-- MD3 Filled Button -->
            <button type="submit" class="md-btn md-btn-filled" id="submitBtn">
              <span class="md-btn-text">注 册</span>
            </button>
          </form>

          <div class="md-links">
            <a th:href="@{/login}">已有账号？去登录</a>
          </div>

          <p class="md-terms">
            注册即表示您同意<a href="#">服务条款</a>和<a href="#">隐私政策</a>
          </p>

          <footer class="md-footer">
            <span>&copy; 2024–2026 深圳数界探索科技有限公司</span>
          </footer>

        </div>
      </div>

    </div>

    <!-- ===== 注册页面内联脚本 ===== -->
    <script th:src="@{/js/beenest-register.js}"></script>
  </div>

</body>
</html>
```

- [ ] **Step 2: 提交模板文件**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure
git add beenest-cas/beenest-cas-service/src/main/resources/templates/acct-mgmt/casAccountSignupView.html
git commit -m "feat(cas): 创建注册表单页面模板 - 双栏分屏布局"
```

---

### Task 2: 创建激活邮件发送提示页模板

**Files:**
- Create: `beenest-cas/beenest-cas-service/src/main/resources/templates/acct-mgmt/casAccountSignupViewSentInfo.html`

- [ ] **Step 1: 创建 casAccountSignupViewSentInfo.html**

注册提交成功后，CAS 发送激活邮件的提示页面。使用 `body.beenest-login` 双栏分屏布局保持视觉统一，右侧显示成功提示。

```html
<!DOCTYPE html>
<html xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{layout}">

<head>
  <title>Beenest SSO - 注册成功</title>
</head>

<body class="beenest-login">

  <div layout:fragment="content">

    <div class="split-layout">

      <!-- ========== 左侧品牌面板（61.8%） ========== -->
      <div class="split-left">
        <div class="mesh-gradient">
          <div class="orb orb-1"></div>
          <div class="orb orb-2"></div>
          <div class="orb orb-3"></div>
          <div class="orb orb-4"></div>
        </div>
        <div class="mesh-grid"></div>

        <div class="split-left-content">
          <div class="split-brand">
            <div class="split-brand-icon">
              <svg viewBox="0 0 32 32" fill="none">
                <rect width="32" height="32" rx="8" fill="currentColor" opacity="0.15"/>
                <path d="M16 6L8 12v10l8 4 8-4V12L16 6z" fill="currentColor" opacity="0.9"/>
              </svg>
            </div>
            <div class="split-brand-text">
              <span class="split-brand-name">数界探索</span>
              <span class="split-brand-tagline">Digital Boundary Exploration</span>
            </div>
          </div>

          <div class="split-hero">
            <h2 class="split-hero-title">注册申请已提交<br/>请查收激活邮件</h2>
            <p class="split-hero-subtitle">我们已向您填写的邮箱发送了一封激活邮件，请在 24 小时内完成激活</p>
          </div>

          <div class="split-back-link">
            <a th:href="@{/login}">
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12.5 5l-5 5 5 5"/></svg>
              返回登录
            </a>
          </div>
        </div>
      </div>

      <!-- ========== 右侧成功提示面板（38.2%） ========== -->
      <div class="split-right">
        <div class="split-right-inner">

          <div class="register-success">
            <div class="register-success-icon">
              <svg viewBox="0 0 64 64" fill="none">
                <circle cx="32" cy="32" r="30" stroke="currentColor" stroke-width="2" opacity="0.15"/>
                <circle cx="32" cy="32" r="30" fill="currentColor" opacity="0.06"/>
                <path d="M20 32l8 8 16-16" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <h2 class="md-form-title">注册申请已提交</h2>
            <p class="register-success-text">
              我们已向您填写的邮箱发送了一封激活邮件。<br/>
              请在 <strong>24 小时内</strong>点击邮件中的激活链接完成注册。
            </p>
            <div class="register-success-tips">
              <div class="register-success-tip">
                <svg viewBox="0 0 20 20" fill="currentColor" opacity="0.5"><path d="M10 18a8 8 0 100-16 8 8 0 000 16zM9 7a1 1 0 012 0v4a1 1 0 01-2 0V7zm1 8a1 1 0 100-2 1 1 0 000 2z"/></svg>
                <span>如未收到邮件，请检查垃圾邮件文件夹</span>
              </div>
              <div class="register-success-tip">
                <svg viewBox="0 0 20 20" fill="currentColor" opacity="0.5"><path d="M10 18a8 8 0 100-16 8 8 0 000 16zM9 7a1 1 0 012 0v4a1 1 0 01-2 0V7zm1 8a1 1 0 100-2 1 1 0 000 2z"/></svg>
                <span>激活链接 24 小时内有效，过期需重新注册</span>
              </div>
            </div>
            <a th:href="@{/login}" class="md-btn md-btn-filled register-success-btn">
              <span class="md-btn-text">前往登录</span>
            </a>
          </div>

          <footer class="md-footer">
            <span>&copy; 2024–2026 深圳数界探索科技有限公司</span>
          </footer>

        </div>
      </div>

    </div>

  </div>

</body>
</html>
```

- [ ] **Step 2: 提交模板文件**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/templates/acct-mgmt/casAccountSignupViewSentInfo.html
git commit -m "feat(cas): 创建注册激活邮件提示页模板"
```

---

### Task 3: 创建邮箱激活后密码设置页模板

**Files:**
- Create: `beenest-cas/beenest-cas-service/src/main/resources/templates/acct-mgmt/casAccountSignupViewComplete.html`

- [ ] **Step 1: 创建 casAccountSignupViewComplete.html**

用户通过邮件激活链接进入的密码设置页面。使用 `body.beenest-login` 双栏分屏布局。复用 CAS 原生的密码强度检测（zxcvbn.js + passwordMeter.js），但用 MD3 风格重新渲染表单。

```html
<!DOCTYPE html>
<html xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{layout}">

<head>
  <title>Beenest SSO - 设置密码</title>
  <script type="text/javascript" th:src="@{#{webjars.zxcvbn.js}}"></script>
  <script type="text/javascript" th:src="@{/js/passwordMeter.js}"></script>
</head>

<body class="beenest-login">

  <div layout:fragment="content">

    <div class="split-layout">

      <!-- ========== 左侧品牌面板（61.8%） ========== -->
      <div class="split-left">
        <div class="mesh-gradient">
          <div class="orb orb-1"></div>
          <div class="orb orb-2"></div>
          <div class="orb orb-3"></div>
          <div class="orb orb-4"></div>
        </div>
        <div class="mesh-grid"></div>

        <div class="split-left-content">
          <div class="split-brand">
            <div class="split-brand-icon">
              <svg viewBox="0 0 32 32" fill="none">
                <rect width="32" height="32" rx="8" fill="currentColor" opacity="0.15"/>
                <path d="M16 6L8 12v10l8 4 8-4V12L16 6z" fill="currentColor" opacity="0.9"/>
              </svg>
            </div>
            <div class="split-brand-text">
              <span class="split-brand-name">数界探索</span>
              <span class="split-brand-tagline">Digital Boundary Exploration</span>
            </div>
          </div>

          <div class="split-hero">
            <h2 class="split-hero-title">邮箱验证成功<br/>请设置登录密码</h2>
            <p class="split-hero-subtitle">设置安全密码后即可完成注册，开始使用平台服务</p>
          </div>

          <div class="split-back-link">
            <a th:href="@{/login}">
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12.5 5l-5 5 5 5"/></svg>
              返回登录
            </a>
          </div>
        </div>
      </div>

      <!-- ========== 右侧密码设置面板（38.2%） ========== -->
      <div class="split-right">
        <div class="split-right-inner">

          <!-- CAS 服务端错误消息 -->
          <div class="md-alert md-alert-error"
               th:if="${flowRequestContext.messageContext.hasErrorMessages()}" role="alert">
            <svg class="md-alert-icon" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"/></svg>
            <span>
              <p th:each="message : ${flowRequestContext.messageContext.allMessages}"
                 th:utext="${message.text}" style="margin:0"></p>
            </span>
          </div>

          <div class="md-form-header">
            <h2 class="md-form-title">设置登录密码</h2>
            <p class="md-form-subtitle">邮箱验证成功，请设置您的登录密码</p>
          </div>

          <form method="post" id="fm1">
            <script th:inline="javascript">
              /*<![CDATA[*/
              let policyPattern = /*[[${passwordPolicyPattern}]]*/;
              let passwordStrengthI18n = {
                0: /*[[#{screen.pm.password.strength.0}]]*/,
                1: /*[[#{screen.pm.password.strength.1}]]*/,
                2: /*[[#{screen.pm.password.strength.2}]]*/,
                3: /*[[#{screen.pm.password.strength.3}]]*/,
                4: /*[[#{screen.pm.password.strength.4}]]*/
              };
              let passwordMinimumStrength = 0;
              /*]]>*/
            </script>

            <!-- ===== 密码 ===== -->
            <div class="md-field register-field register-field-password">
              <input type="password" id="password" name="password"
                     placeholder=" " required
                     autocapitalize="none" spellcheck="false"
                     th:attr="pattern=${passwordPolicyPattern}"
                     autocomplete="new-password" />
              <label for="password">密码</label>
              <button type="button" class="register-pwd-toggle" onclick="togglePasswordVisibility('password', this)" title="显示/隐藏密码">
                <svg class="pwd-icon-eye" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M1 10s3-6 9-6 9 6 9 6-3 6-9 6-9-6-9-6z"/><circle cx="10" cy="10" r="3"/></svg>
                <svg class="pwd-icon-eye-off" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" style="display:none"><path d="M3.53 2.47l14 14M1 10s3-6 9-6c1.6 0 3 .3 4.2.8M19 10s-3 6-9 6c-1.6 0-3-.3-4.2-.8M7.07 7.07A3 3 0 0112.93 12.93"/></svg>
              </button>
              <div class="md-field-error" id="password-error"></div>
            </div>

            <!-- CAS 原生密码强度指示器（由 passwordMeter.js 驱动） -->
            <div class="register-pwd-strength" id="pwdStrength">
              <div class="register-pwd-strength-bar">
                <div class="register-pwd-strength-segment" id="pwdSeg1"></div>
                <div class="register-pwd-strength-segment" id="pwdSeg2"></div>
                <div class="register-pwd-strength-segment" id="pwdSeg3"></div>
              </div>
              <span class="register-pwd-strength-text" id="password-strength-icon"></span>
            </div>
            <div id="strengthProgressBar" role="progressbar" class="d-none">
              <div id="progress-strength-indicator"></div>
            </div>
            <div class="register-pwd-strength-msg" id="password-strength-msg" style="display:none;">
              <span id="password-strength-warning"></span>
              <span id="password-strength-suggestions"></span>
            </div>
            <div id="password-policy-violation-msg" style="display:none;">
              <span>密码不符合安全策略要求</span>
            </div>

            <!-- ===== 确认密码 ===== -->
            <div class="md-field register-field register-field-password">
              <input type="password" id="confirmedPassword" name="confirmedPassword"
                     placeholder=" " required
                     autocapitalize="none" spellcheck="false"
                     th:attr="pattern=${passwordPolicyPattern}"
                     autocomplete="new-password" />
              <label for="confirmedPassword">确认密码</label>
              <button type="button" class="register-pwd-toggle" onclick="togglePasswordVisibility('confirmedPassword', this)" title="显示/隐藏密码">
                <svg class="pwd-icon-eye" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M1 10s3-6 9-6 9 6 9 6-3 6-9 6-9-6-9-6z"/><circle cx="10" cy="10" r="3"/></svg>
                <svg class="pwd-icon-eye-off" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" style="display:none"><path d="M3.53 2.47l14 14M1 10s3-6 9-6c1.6 0 3 .3 4.2.8M19 10s-3 6-9 6c-1.6 0-3-.3-4.2-.8M7.07 7.07A3 3 0 0112.93 12.93"/></svg>
              </button>
              <div class="md-field-error" id="confirmedPassword-error"></div>
            </div>
            <div id="password-confirm-mismatch-msg" style="display:none;">
              <span>两次输入的密码不一致</span>
            </div>

            <!-- 安全问题（CAS 原生支持） -->
            <section th:if="${accountRegistrationSecurityQuestionsCount gt 0}">
              <div th:each="count : ${#numbers.sequence(1, accountRegistrationSecurityQuestionsCount)}" class="md-field register-field" style="margin-top:1.25rem">
                <input type="text"
                       th:id="${'securityquestion' + count}"
                       th:name="${'securityquestion' + count}"
                       placeholder=" " size="50"
                       autocapitalize="none" spellcheck="false" />
                <label th:for="${'securityquestion' + count}" th:utext="#{${'screen.acct.label.security.question.' + count}}">安全问题</label>
              </div>
              <div th:each="count : ${#numbers.sequence(1, accountRegistrationSecurityQuestionsCount)}" class="md-field register-field" style="margin-top:1.25rem">
                <input type="password"
                       th:id="${'securityanswer' + count}"
                       th:name="${'securityanswer' + count}"
                       placeholder=" " required
                       autocapitalize="none" spellcheck="false"
                       autocomplete="off" />
                <label th:for="${'securityanswer' + count}" th:utext="#{screen.acct.label.security.answer}">安全答案</label>
              </div>
            </section>

            <!-- Hidden CAS form fields -->
            <div class="md-hidden">
              <input type="hidden" name="execution" th:value="${flowExecutionKey}" />
              <input type="hidden" name="_eventId" value="submit" />
            </div>

            <!-- MD3 Filled Button -->
            <button type="submit" class="md-btn md-btn-filled" id="submitBtn">
              <span class="md-btn-text">完成注册</span>
            </button>
          </form>

          <footer class="md-footer">
            <span>&copy; 2024–2026 深圳数界探索科技有限公司</span>
          </footer>

        </div>
      </div>

    </div>

    <script th:src="@{/js/beenest-register.js}"></script>
  </div>

</body>
</html>
```

- [ ] **Step 2: 提交模板文件**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/templates/acct-mgmt/casAccountSignupViewComplete.html
git commit -m "feat(cas): 创建邮箱激活后密码设置页模板"
```

---

### Task 4: 创建注册页前端交互脚本

**Files:**
- Create: `beenest-cas/beenest-cas-service/src/main/resources/static/js/beenest-register.js`

- [ ] **Step 1: 创建 beenest-register.js**

包含：用户类型卡片选择器、密码强度实时检测、密码显示/隐藏切换、确认密码一致性检查、表单提交前全字段校验。

```javascript
/**
 * Beenest CAS 注册页前端交互逻辑。
 * 用户类型卡片选择、密码强度检测、表单校验。
 */

/* ========== 用户类型卡片选择器 ========== */
function selectUserType(value) {
  var cards = document.querySelectorAll('.register-type-card');
  cards.forEach(function(card) {
    var isSelected = card.dataset.value === value;
    card.classList.toggle('is-selected', isSelected);
  });
  var input = document.getElementById('userType');
  if (input) {
    input.value = value;
    clearFieldError('userType');
  }
}

/* ========== 密码显示/隐藏切换 ========== */
function togglePasswordVisibility(fieldId, btn) {
  var input = document.getElementById(fieldId);
  if (!input) return;
  var eyeIcon = btn.querySelector('.pwd-icon-eye');
  var eyeOffIcon = btn.querySelector('.pwd-icon-eye-off');
  if (input.type === 'password') {
    input.type = 'text';
    if (eyeIcon) eyeIcon.style.display = 'none';
    if (eyeOffIcon) eyeOffIcon.style.display = 'block';
  } else {
    input.type = 'password';
    if (eyeIcon) eyeIcon.style.display = 'block';
    if (eyeOffIcon) eyeOffIcon.style.display = 'none';
  }
}

/* ========== 密码强度实时检测 ========== */
function updatePasswordStrength() {
  var pwd = document.getElementById('password');
  if (!pwd) return;
  var value = pwd.value;
  var score = 0;

  var rules = {
    length: value.length >= 8 && value.length <= 64,
    upper: /[A-Z]/.test(value),
    lower: /[a-z]/.test(value),
    digit: /\d/.test(value),
    special: /[^A-Za-z0-9]/.test(value)
  };

  var metCount = 0;
  for (var key in rules) {
    if (rules[key]) metCount++;
  }

  if (metCount <= 1) score = 1;
  else if (metCount <= 3) score = 2;
  else if (metCount >= 4) score = 3;

  if (value.length === 0) score = 0;

  var seg1 = document.getElementById('pwdSeg1');
  var seg2 = document.getElementById('pwdSeg2');
  var seg3 = document.getElementById('pwdSeg3');
  var text = document.getElementById('pwdStrengthText');

  var colors = { 0: '', 1: '#ef4444', 2: '#f59e0b', 3: '#00d4aa' };
  var labels = { 0: '', 1: '弱', 2: '中', 3: '强' };

  if (seg1) seg1.style.background = score >= 1 ? colors[score] : '';
  if (seg2) seg2.style.background = score >= 2 ? colors[score] : '';
  if (seg3) seg3.style.background = score >= 3 ? colors[score] : '';
  if (text) {
    text.textContent = labels[score];
    text.style.color = colors[score] || 'inherit';
  }

  var ruleEls = document.querySelectorAll('[data-rule]');
  ruleEls.forEach(function(el) {
    var rule = el.dataset.rule;
    el.classList.toggle('is-met', rules[rule] || false);
  });

  if (value.length > 0) {
    clearFieldError('password');
  }
}

/* ========== 确认密码一致性检查 ========== */
function checkPasswordMatch() {
  var pwd = document.getElementById('password');
  var confirmed = document.getElementById('confirmedPassword');
  if (!pwd || !confirmed) return;

  if (confirmed.value.length > 0 && pwd.value !== confirmed.value) {
    showFieldError('confirmedPassword', '两次输入的密码不一致');
  } else {
    clearFieldError('confirmedPassword');
  }
}

/* ========== 字段错误显示/清除 ========== */
function showFieldError(fieldId, message) {
  var errorEl = document.getElementById(fieldId + '-error');
  var fieldEl = document.getElementById(fieldId);
  if (errorEl) {
    errorEl.textContent = message;
    errorEl.style.display = 'block';
  }
  if (fieldEl && fieldEl.closest('.md-field')) {
    fieldEl.closest('.md-field').classList.add('is-error');
  }
}

function clearFieldError(fieldId) {
  var errorEl = document.getElementById(fieldId + '-error');
  var fieldEl = document.getElementById(fieldId);
  if (errorEl) {
    errorEl.textContent = '';
    errorEl.style.display = 'none';
  }
  if (fieldEl && fieldEl.closest('.md-field')) {
    fieldEl.closest('.md-field').classList.remove('is-error');
  }
}

/* ========== 表单提交前全字段校验 ========== */
document.addEventListener('DOMContentLoaded', function() {
  var form = document.getElementById('fm1');
  if (!form) return;

  /* 失焦即时校验 */
  var usernameEl = document.getElementById('username');
  if (usernameEl) {
    usernameEl.addEventListener('blur', function() {
      var val = this.value.trim();
      if (!val) { showFieldError('username', '请输入用户名'); return; }
      if (!/^[a-zA-Z][a-zA-Z0-9_]{3,19}$/.test(val)) {
        showFieldError('username', '4-20位，字母开头，仅字母数字下划线');
      } else {
        clearFieldError('username');
      }
    });
  }

  var emailEl = document.getElementById('email');
  if (emailEl) {
    emailEl.addEventListener('blur', function() {
      var val = this.value.trim();
      if (!val) { showFieldError('email', '请输入邮箱'); return; }
      if (!/^\S+@\S+\.\S+$/.test(val)) {
        showFieldError('email', '请输入有效的邮箱地址');
      } else {
        clearFieldError('email');
      }
    });
  }

  var phoneEl = document.getElementById('phone');
  if (phoneEl) {
    phoneEl.addEventListener('blur', function() {
      var val = this.value.trim();
      if (val && !/^1[3-9]\d{9}$/.test(val)) {
        showFieldError('phone', '请输入有效的手机号');
      } else {
        clearFieldError('phone');
      }
    });
  }

  /* 提交校验 */
  form.addEventListener('submit', function(e) {
    var hasError = false;

    /* 用户名 */
    var username = document.getElementById('username');
    if (username && !username.value.trim()) {
      showFieldError('username', '请输入用户名');
      hasError = true;
    }

    /* 姓名字段（仅在注册表单中存在） */
    var firstName = document.getElementById('firstName');
    if (firstName && !firstName.value.trim()) {
      showFieldError('firstName', '请输入姓');
      hasError = true;
    }
    var lastName = document.getElementById('lastName');
    if (lastName && !lastName.value.trim()) {
      showFieldError('lastName', '请输入名');
      hasError = true;
    }

    /* 邮箱 */
    var email = document.getElementById('email');
    if (email && !email.value.trim()) {
      showFieldError('email', '请输入邮箱');
      hasError = true;
    }

    /* 用户类型 */
    var userType = document.getElementById('userType');
    if (userType && !userType.value) {
      showFieldError('userType', '请选择用户类型');
      hasError = true;
    }

    /* 密码 */
    var password = document.getElementById('password');
    if (password && !password.value) {
      showFieldError('password', '请输入密码');
      hasError = true;
    }

    /* 确认密码 */
    var confirmed = document.getElementById('confirmedPassword');
    if (confirmed && password && confirmed.value !== password.value) {
      showFieldError('confirmedPassword', '两次输入的密码不一致');
      hasError = true;
    }

    /* 服务条款（仅在注册表单中存在） */
    var agreeTerms = document.getElementById('agreeTerms');
    if (agreeTerms && !agreeTerms.checked) {
      showFieldError('agreeTerms', '请同意服务条款');
      hasError = true;
    }

    if (hasError) {
      e.preventDefault();
      var firstError = form.querySelector('.is-error input, .is-error select');
      if (firstError) firstError.focus();
    } else {
      /* 按钮加载状态 */
      var btn = document.getElementById('submitBtn');
      if (btn) {
        btn.disabled = true;
        var btnText = btn.querySelector('.md-btn-text');
        if (btnText) btnText.textContent = '提交中...';
      }
    }
  });

  /* 初始化：确保用户类型卡片状态同步 */
  var userTypeInput = document.getElementById('userType');
  if (userTypeInput && userTypeInput.value) {
    selectUserType(userTypeInput.value);
  }
});
```

- [ ] **Step 2: 提交脚本文件**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/static/js/beenest-register.js
git commit -m "feat(cas): 创建注册页前端交互脚本"
```

---

### Task 5: 追加注册页专用 CSS 样式

**Files:**
- Modify: `beenest-cas/beenest-cas-service/src/main/resources/static/css/beenest-login.css`

- [ ] **Step 1: 在 beenest-login.css 末尾追加注册页样式**

在文件末尾（`/* --- 无障碍：减少动画 --- */` 块之后、`body.beenest-cas` 块之前）追加以下样式：

```css
/* ============================================================
   Register Page · Split-Layout 专用样式
   ============================================================ */

/* --- 姓名并排行 --- */
.register-name-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

/* --- 字段错误状态 --- */
.md-field.is-error input,
.md-field.is-error select {
  border-color: var(--md-error) !important;
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.08) !important;
}

.md-field.is-error label {
  color: var(--md-error) !important;
}

.md-field-error {
  display: none;
  font-size: 0.75rem;
  color: var(--md-error);
  margin-top: 0.25rem;
  padding-left: 0.25rem;
  line-height: 1.3;
}

/* --- 注册提示文字 --- */
.register-hint {
  margin-top: -0.5rem;
  margin-bottom: 0.5rem;
}

/* --- 用户类型卡片选择器 --- */
.register-type-selector {
  margin: 0;
}

.register-type-label {
  display: block;
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--md-on-surface-variant);
  margin-bottom: 0.5rem;
  padding-left: 0.25rem;
}

.register-type-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
}

.register-type-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 1rem 0.75rem;
  border: 2px solid var(--md-outline);
  border-radius: 12px;
  background: var(--md-surface-dim);
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease;
  user-select: none;
}

.register-type-card:hover {
  border-color: var(--md-on-surface-variant);
  background: var(--md-surface-container);
}

.register-type-card.is-selected {
  border-color: var(--md-primary);
  border-image: none;
  background: rgba(8, 145, 178, 0.04);
  box-shadow: 0 0 0 3px rgba(8, 145, 178, 0.08);
}

.register-type-card-icon {
  width: 2rem;
  height: 2rem;
  color: var(--md-on-surface-variant);
  margin-bottom: 0.5rem;
  transition: color 0.2s ease;
}

.register-type-card.is-selected .register-type-card-icon {
  color: var(--md-primary);
}

.register-type-card-icon svg {
  width: 100%;
  height: 100%;
}

.register-type-card-title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--md-on-surface);
  margin-bottom: 0.15rem;
}

.register-type-card-desc {
  font-size: 0.75rem;
  color: var(--md-on-surface-variant);
}

/* --- 密码字段（含 toggle 按钮） --- */
.register-field-password {
  position: relative;
}

.register-field-password input {
  padding-right: 2.75rem;
}

.register-pwd-toggle {
  position: absolute;
  right: 0.5rem;
  top: 50%;
  transform: translateY(-50%);
  width: 2rem;
  height: 2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  color: var(--md-on-surface-variant);
  cursor: pointer;
  border-radius: 50%;
  transition: color 0.15s ease, background 0.15s ease;
  z-index: 2;
  padding: 0;
}

.register-pwd-toggle:hover {
  color: var(--md-primary);
  background: rgba(8, 145, 178, 0.06);
}

.register-pwd-toggle svg {
  width: 1.1rem;
  height: 1.1rem;
}

/* --- 密码强度指示器 --- */
.register-pwd-strength {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.35rem;
}

.register-pwd-strength-bar {
  display: flex;
  gap: 0.25rem;
  flex: 1;
  height: 4px;
}

.register-pwd-strength-segment {
  flex: 1;
  height: 100%;
  border-radius: 2px;
  background: var(--md-outline);
  transition: background 0.2s ease;
}

.register-pwd-strength-text {
  font-size: 0.75rem;
  font-weight: 500;
  min-width: 1.5rem;
  text-align: right;
}

/* --- 密码规则提示 --- */
.register-pwd-rules {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem 0.75rem;
  margin-top: 0.35rem;
  margin-bottom: 0.25rem;
}

.register-pwd-rules span {
  font-size: 0.72rem;
  color: var(--md-on-surface-variant);
  transition: color 0.2s ease;
}

.register-pwd-rules span.is-met {
  color: #00d4aa;
  font-weight: 500;
}

.register-pwd-rules span.is-met::before {
  content: "\2713 ";
}

/* --- 密码强度消息（密码设置页用） --- */
.register-pwd-strength-msg {
  font-size: 0.8rem;
  color: var(--md-warning);
  margin-top: 0.25rem;
}

/* --- 服务条款 --- */
.register-terms {
  margin-top: 0.25rem;
}

.register-terms a {
  color: var(--md-primary);
  text-decoration: none;
}

.register-terms a:hover {
  text-decoration: underline;
}

/* --- 左侧返回登录链接 --- */
.split-back-link {
  margin-top: 2.5rem;
}

.split-back-link a {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  color: rgba(6, 182, 212, 0.8);
  font-size: 0.88rem;
  font-weight: 500;
  text-decoration: none;
  transition: color 0.2s ease;
}

.split-back-link a:hover {
  color: rgba(6, 182, 212, 1);
}

.split-back-link a svg {
  width: 1rem;
  height: 1rem;
}

/* --- 注册成功提示（激活邮件页） --- */
.register-success {
  text-align: center;
  padding: 1rem 0;
}

.register-success-icon {
  width: 4rem;
  height: 4rem;
  color: #00d4aa;
  margin: 0 auto 1.25rem;
}

.register-success-icon svg {
  width: 100%;
  height: 100%;
}

.register-success-text {
  font-size: 0.92rem;
  color: var(--md-on-surface-variant);
  line-height: 1.6;
  margin: 0.75rem 0 1.5rem;
}

.register-success-tips {
  text-align: left;
  margin: 0 auto 1.5rem;
  max-width: 320px;
}

.register-success-tip {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  font-size: 0.8rem;
  color: var(--md-on-surface-variant);
  margin-bottom: 0.5rem;
}

.register-success-tip svg {
  flex-shrink: 0;
  width: 1rem;
  height: 1rem;
  margin-top: 0.1rem;
}

.register-success-btn {
  display: inline-flex;
  text-decoration: none;
  width: auto;
  padding: 0 2rem;
  margin-top: 0;
}

/* --- 注册页响应式 --- */
@media (max-width: 768px) {
  .register-name-row {
    grid-template-columns: 1fr;
    gap: 1.25rem;
  }

  .register-type-cards {
    grid-template-columns: 1fr 1fr;
    gap: 0.5rem;
  }

  .register-type-card {
    padding: 0.75rem 0.5rem;
  }

  .register-pwd-rules {
    font-size: 0.68rem;
  }
}
```

- [ ] **Step 2: 提交样式文件**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/static/css/beenest-login.css
git commit -m "feat(cas): 追加注册页专用 CSS 样式"
```

---

### Task 6: 编译验证与功能测试

- [ ] **Step 1: 离线编译 CAS WAR**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas
./gradlew :beenest-cas-service:clean :beenest-cas-service:build --offline --no-daemon -x test
```

预期：BUILD SUCCESSFUL，WAR 生成于 `beenest-cas-service/build/libs/cas.war`

- [ ] **Step 2: 重启 CAS 容器**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure
docker compose up -d beenest-cas
```

- [ ] **Step 3: 查看启动日志确认无错误**

```bash
docker logs beenest-cas --tail 50 -f
```

预期：CAS 启动成功，无模板解析错误

- [ ] **Step 4: 浏览器验证注册页面**

访问 `http://localhost:8081/cas/login`，点击 "注册账号" 链接，验证：
- 双栏分屏布局正确渲染
- 左侧 mesh gradient 动画正常运行
- 右侧表单所有字段正确显示（用户名、姓、名、邮箱、用户类型卡片、手机号、密码、确认密码）
- 用户类型卡片点击切换正常
- 密码强度指示器实时更新
- 密码显示/隐藏切换正常
- 确认密码一致性检查正常
- 表单提交校验正常（空字段、格式错误）
- 服务条款 checkbox 正常

- [ ] **Step 5: 提交最终验证**

```bash
git add -A
git commit -m "feat(cas): 注册页面企业级 UI 重构完成"
```
