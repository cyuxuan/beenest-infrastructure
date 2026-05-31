/**
 * Beenest CAS 注册页前端交互逻辑。
 * 用户类型卡片选择、密码强度检测、密码显示/隐藏切换、表单校验。
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

  var score = 0;
  if (value.length === 0) score = 0;
  else if (metCount <= 1) score = 1;
  else if (metCount <= 3) score = 2;
  else if (metCount >= 4) score = 3;

  var colors = { 0: '', 1: '#ef4444', 2: '#f59e0b', 3: '#00d4aa' };
  var labels = { 0: '', 1: '弱', 2: '中', 3: '强' };

  var seg1 = document.getElementById('pwdSeg1');
  var seg2 = document.getElementById('pwdSeg2');
  var seg3 = document.getElementById('pwdSeg3');
  var text = document.getElementById('pwdStrengthText');

  if (seg1) seg1.style.background = score >= 1 ? colors[score] : '';
  if (seg2) seg2.style.background = score >= 2 ? colors[score] : '';
  if (seg3) seg3.style.background = score >= 3 ? colors[score] : '';
  if (text) {
    text.textContent = labels[score];
    text.style.color = colors[score] || 'inherit';
  }

  /* 密码规则提示 */
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

/* ========== 表单交互初始化 ========== */
document.addEventListener('DOMContentLoaded', function() {
  var form = document.getElementById('fm1');
  if (!form) return;

  /* 失焦即时校验：用户名 */
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

  /* 失焦即时校验：姓 */
  var firstNameEl = document.getElementById('firstName');
  if (firstNameEl) {
    firstNameEl.addEventListener('blur', function() {
      if (!this.value.trim()) {
        showFieldError('firstName', '请输入姓');
      } else {
        clearFieldError('firstName');
      }
    });
  }

  /* 失焦即时校验：名 */
  var lastNameEl = document.getElementById('lastName');
  if (lastNameEl) {
    lastNameEl.addEventListener('blur', function() {
      if (!this.value.trim()) {
        showFieldError('lastName', '请输入名');
      } else {
        clearFieldError('lastName');
      }
    });
  }

  /* 失焦即时校验：邮箱 */
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

  /* 失焦即时校验：手机号 */
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

  /* 提交前全字段校验 */
  form.addEventListener('submit', function(e) {
    var hasError = false;

    /* 用户名 */
    var username = document.getElementById('username');
    if (username && !username.value.trim()) {
      showFieldError('username', '请输入用户名');
      hasError = true;
    }

    /* 姓 */
    var firstName = document.getElementById('firstName');
    if (firstName && !firstName.value.trim()) {
      showFieldError('firstName', '请输入姓');
      hasError = true;
    }

    /* 名 */
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

    /* 服务条款 */
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