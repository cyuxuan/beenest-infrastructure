/**
 * Palantir 用户管理 Tab 交互逻辑
 * 依赖：jQuery（Palantir 页面内置）、Palantir actuatorEndpoints
 * 注意：脚本在 jQuery 之前加载，所以使用延迟初始化模式
 * 后端响应格式：R<T> = { code: 200, message: "success", data: {...} }
 */
(function () {
  'use strict';

  var API = {
    list: '/cas/actuator/casUsers',
    create: '/cas/actuator/casUsers/create',
    addRole: '/cas/actuator/casUsers/addRole',
    removeRole: '/cas/actuator/casUsers/removeRole'
  };

  /** 判断 R<T> 响应是否成功 */
  function isOk(resp) { return resp && resp.code === 200; }

  /** 获取 R<T> 的 data 字段 */
  function getData(resp) { return resp && resp.data; }

  /** 渲染用户管理 Tab */
  function render() {
    var $container = jQuery('#beenest-account-mgmt');
    $container.html(
      '<div class="pal-tab-header">' +
        '<h3>用户管理</h3>' +
      '</div>' +
      '<div class="pal-toolbar">' +
        '<input type="text" class="pal-search-input" id="pal-user-search" placeholder="搜索用户名/手机号…">' +
        '<select class="pal-select" id="pal-role-filter">' +
          '<option value="">全部角色</option>' +
          '<option value="ADMIN">ADMIN</option>' +
          '<option value="USER">USER</option>' +
          '<option value="PILOT">PILOT</option>' +
          '<option value="CUSTOMER">CUSTOMER</option>' +
        '</select>' +
        '<button class="pal-btn pal-btn-primary" id="pal-user-search-btn">搜索</button>' +
        '<button class="pal-btn pal-btn-success" id="pal-user-add-btn">添加用户</button>' +
      '</div>' +
      '<div class="pal-table-container">' +
        '<table class="pal-table" id="pal-user-table">' +
          '<thead><tr>' +
            '<th>ID</th><th>用户名</th><th>手机号</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th>' +
          '</tr></thead>' +
          '<tbody></tbody>' +
        '</table>' +
      '</div>' +
      '<div class="pal-pagination" id="pal-user-pagination"></div>' +
      '<div class="pal-dialog" id="pal-role-dialog" style="display:none"></div>' +
      '<div class="pal-dialog" id="pal-add-user-dialog" style="display:none"></div>'
    );
    bindEvents();
    loadUsers(1);
  }

  function bindEvents() {
    jQuery(document)
      .off('click.pal-user', '#pal-user-search-btn')
      .on('click.pal-user', '#pal-user-search-btn', function () { loadUsers(1); })
      .off('keypress.pal-user', '#pal-user-search')
      .on('keypress.pal-user', '#pal-user-search', function (e) {
        if (e.which === 13) loadUsers(1);
      })
      .off('change.pal-user', '#pal-role-filter')
      .on('change.pal-user', '#pal-role-filter', function () { loadUsers(1); })
      .off('click.pal-user', '#pal-user-add-btn')
      .on('click.pal-user', '#pal-user-add-btn', openAddUserDialog)
      .off('click.pal-user', '.pal-add-role-btn')
      .on('click.pal-user', '.pal-add-role-btn', function () {
        openRoleDialog(jQuery(this).data('user-id'), jQuery(this).data('username'), 'add');
      })
      .off('click.pal-user', '.pal-remove-role-btn')
      .on('click.pal-user', '.pal-remove-role-btn', function () {
        openRoleDialog(jQuery(this).data('user-id'), jQuery(this).data('username'), 'remove');
      })
      .off('click.pal-user', '.pal-page-btn')
      .on('click.pal-user', '.pal-page-btn', function () { loadUsers(jQuery(this).data('page')); })
      .off('click.pal-user', '#pal-role-submit')
      .on('click.pal-user', '#pal-role-submit', submitRoleChange)
      .off('click.pal-user', '#pal-role-cancel')
      .on('click.pal-user', '#pal-role-cancel', closeRoleDialog)
      .off('click.pal-user', '#pal-add-user-submit')
      .on('click.pal-user', '#pal-add-user-submit', submitAddUser)
      .off('click.pal-user', '#pal-add-user-cancel')
      .on('click.pal-user', '#pal-add-user-cancel', closeAddUserDialog);
  }

  function loadUsers(page) {
    var search = jQuery('#pal-user-search').val() || '';
    var role = jQuery('#pal-role-filter').val() || '';
    var $tbody = jQuery('#pal-user-table tbody');

    $tbody.html('<tr><td colspan="7" class="pal-loading">加载中…</td></tr>');

    jQuery.ajax({
      url: API.list,
      method: 'GET',
      data: { page: page - 1, size: 20, query: search, status: null },
      traditional: true
    }).done(function (resp) {
      var d = getData(resp);
      if (!isOk(resp) || !d) {
        $tbody.html('<tr><td colspan="7" class="pal-empty-state">加载失败: ' + esc(resp.message || '未知错误') + '</td></tr>');
        return;
      }

      var users = d.users || [];
      var total = d.total || 0;
      var totalPages = Math.max(1, Math.ceil(total / 20));

      if (users.length === 0) {
        $tbody.html('<tr><td colspan="7" class="pal-empty-state">暂无用户数据</td></tr>');
        jQuery('#pal-user-pagination').empty();
        return;
      }

      var html = '';
      jQuery.each(users, function (_, u) {
        // 后端返回的 roles 已经是数组（UserDetailVO.roles = List<String>）
        var rolesArr = Array.isArray(u.roles) ? u.roles : [];
        var roleBadges = rolesArr.map(function (r) {
          return '<span class="pal-badge pal-badge-' + roleBadgeClass(r) + '">' + esc(r) + '</span>';
        }).join(' ');

        var statusBadge = u.status === 1
          ? '<span class="pal-badge pal-badge-success">正常</span>'
          : (u.status === 2 ? '<span class="pal-badge pal-badge-danger">锁定</span>'
          : '<span class="pal-badge pal-badge-warning">禁用</span>');

        html += '<tr>' +
          '<td>' + esc(u.userId) + '</td>' +
          '<td>' + esc(u.username) + '</td>' +
          '<td>' + esc(u.phone || '-') + '</td>' +
          '<td>' + roleBadges + '</td>' +
          '<td>' + statusBadge + '</td>' +
          '<td>' + esc(u.createdTime || '-') + '</td>' +
          '<td>' +
            '<button class="pal-btn pal-btn-sm pal-btn-success pal-add-role-btn" data-user-id="' + esc(u.userId) + '" data-username="' + esc(u.username) + '">添加角色</button> ' +
            '<button class="pal-btn pal-btn-sm pal-btn-danger pal-remove-role-btn" data-user-id="' + esc(u.userId) + '" data-username="' + esc(u.username) + '">移除角色</button>' +
          '</td>' +
        '</tr>';
      });
      $tbody.html(html);
      renderPagination(page, totalPages);
    }).fail(function (xhr) {
      $tbody.html('<tr><td colspan="7" class="pal-empty-state">加载失败: ' + (xhr.statusText || '未知错误') + '</td></tr>');
    });
  }

  function renderPagination(current, total) {
    if (total <= 1) { jQuery('#pal-user-pagination').empty(); return; }
    var html = '';
    if (current > 1) html += '<button class="pal-btn pal-btn-sm pal-page-btn" data-page="' + (current - 1) + '">上一页</button>';
    html += ' 第 ' + current + ' / ' + total + ' 页 ';
    if (current < total) html += '<button class="pal-btn pal-btn-sm pal-page-btn" data-page="' + (current + 1) + '">下一页</button>';
    jQuery('#pal-user-pagination').html(html);
  }

  function roleBadgeClass(role) {
    switch ((role || '').toUpperCase()) {
      case 'ADMIN': return 'danger';
      case 'PILOT': return 'info';
      case 'CUSTOMER': return 'warning';
      default: return 'success';
    }
  }

  /** 添加用户对话框 */
  function openAddUserDialog() {
    var $dialog = jQuery('#pal-add-user-dialog');
    $dialog.html(
      '<div class="pal-dialog-overlay"></div>' +
      '<div class="pal-dialog-content">' +
        '<h4>添加用户</h4>' +
        '<div class="pal-form-group">' +
          '<label>用户名 <span style="color:var(--pal-danger)">*</span></label>' +
          '<input type="text" class="pal-input" id="pal-add-username" placeholder="登录用户名">' +
        '</div>' +
        '<div class="pal-form-group">' +
          '<label>密码 <span style="color:var(--pal-danger)">*</span></label>' +
          '<input type="password" class="pal-input" id="pal-add-password" placeholder="登录密码">' +
        '</div>' +
        '<div class="pal-form-row">' +
          '<div class="pal-form-group">' +
            '<label>姓</label>' +
            '<input type="text" class="pal-input" id="pal-add-lastname" placeholder="姓">' +
          '</div>' +
          '<div class="pal-form-group">' +
            '<label>名</label>' +
            '<input type="text" class="pal-input" id="pal-add-firstname" placeholder="名">' +
          '</div>' +
        '</div>' +
        '<div class="pal-form-row">' +
          '<div class="pal-form-group">' +
            '<label>邮箱</label>' +
            '<input type="email" class="pal-input" id="pal-add-email" placeholder="user@example.com">' +
          '</div>' +
          '<div class="pal-form-group">' +
            '<label>手机号</label>' +
            '<input type="tel" class="pal-input" id="pal-add-phone" placeholder="13800138000">' +
          '</div>' +
        '</div>' +
        '<div class="pal-form-group">' +
          '<label>用户类型</label>' +
          '<select class="pal-input" id="pal-add-usertype">' +
            '<option value="CUSTOMER">客户 (CUSTOMER)</option>' +
            '<option value="PILOT">飞手 (PILOT)</option>' +
          '</select>' +
        '</div>' +
        '<div id="pal-add-user-result"></div>' +
        '<div class="pal-dialog-actions">' +
          '<button class="pal-btn" id="pal-add-user-cancel">取消</button>' +
          '<button class="pal-btn pal-btn-primary" id="pal-add-user-submit">创建用户</button>' +
        '</div>' +
      '</div>'
    ).show();
  }

  function closeAddUserDialog() {
    jQuery('#pal-add-user-dialog').hide().empty();
  }

  function submitAddUser() {
    var username = jQuery('#pal-add-username').val().trim();
    var password = jQuery('#pal-add-password').val();
    var firstName = jQuery('#pal-add-firstname').val().trim();
    var lastName = jQuery('#pal-add-lastname').val().trim();
    var email = jQuery('#pal-add-email').val().trim();
    var phone = jQuery('#pal-add-phone').val().trim();
    var userType = jQuery('#pal-add-usertype').val();
    var $result = jQuery('#pal-add-user-result');

    if (!username) { $result.html('<div class="pal-result-msg pal-error">请输入用户名</div>'); return; }
    if (!password) { $result.html('<div class="pal-result-msg pal-error">请输入密码</div>'); return; }
    if (password.length < 6) { $result.html('<div class="pal-result-msg pal-error">密码至少6位</div>'); return; }

    jQuery('#pal-add-user-submit').prop('disabled', true).text('创建中…');

    var data = { username: username, password: password, userType: userType };
    if (firstName) data.firstName = firstName;
    if (lastName) data.lastName = lastName;
    if (email) data.email = email;
    if (phone) data.phone = phone;

    jQuery.ajax({
      url: API.create,
      method: 'POST',
      data: JSON.stringify(data),
      contentType: 'application/json'
    }).done(function (resp) {
      var d = getData(resp);
      if (isOk(resp) && d) {
        $result.html('<div class="pal-result-msg pal-success">' + esc(d.message || '用户创建成功') + ' (ID: ' + esc(d.userId || '') + ')</div>');
        setTimeout(function () { closeAddUserDialog(); loadUsers(1); }, 1200);
      } else {
        $result.html('<div class="pal-result-msg pal-error">' + esc(resp.message || (d && d.message) || '创建失败') + '</div>');
        jQuery('#pal-add-user-submit').prop('disabled', false).text('创建用户');
      }
    }).fail(function (xhr) {
      var msg = '创建失败';
      try { var r = JSON.parse(xhr.responseText); msg = r.message || msg; } catch(e) { msg = xhr.statusText || msg; }
      $result.html('<div class="pal-result-msg pal-error">' + esc(msg) + '</div>');
      jQuery('#pal-add-user-submit').prop('disabled', false).text('创建用户');
    });
  }

  function openRoleDialog(userId, username, mode) {
    var title = mode === 'add' ? '添加角色' : '移除角色';
    var btnClass = mode === 'add' ? 'pal-btn-success' : 'pal-btn-danger';
    var btnText = mode === 'add' ? '确认添加' : '确认移除';

    var $dialog = jQuery('#pal-role-dialog');
    $dialog.html(
      '<div class="pal-dialog-overlay"></div>' +
      '<div class="pal-dialog-content">' +
        '<h4>' + title + ' - ' + esc(username) + '</h4>' +
        '<div class="pal-form-group">' +
          '<label>角色名称</label>' +
          '<input type="text" class="pal-input" id="pal-role-name" placeholder="如：ADMIN, PILOT, CUSTOMER">' +
        '</div>' +
        '<div id="pal-role-result"></div>' +
        '<div class="pal-dialog-actions">' +
          '<button class="pal-btn" id="pal-role-cancel">取消</button>' +
          '<button class="pal-btn ' + btnClass + '" id="pal-role-submit" data-user-id="' + userId + '" data-mode="' + mode + '">' + btnText + '</button>' +
        '</div>' +
      '</div>'
    ).show();
  }

  function closeRoleDialog() {
    jQuery('#pal-role-dialog').hide().empty();
  }

  function submitRoleChange() {
    var userId = jQuery('#pal-role-submit').data('userId');
    var mode = jQuery('#pal-role-submit').data('mode');
    var roleName = jQuery('#pal-role-name').val().trim().toUpperCase();
    var $result = jQuery('#pal-role-result');

    if (!roleName) { $result.html('<div class="pal-result-msg pal-error">请输入角色名称</div>'); return; }

    var url = mode === 'add' ? API.addRole : API.removeRole;
    jQuery.ajax({
      url: url,
      method: 'POST',
      data: JSON.stringify({ userId: userId, role: roleName }),
      contentType: 'application/json'
    }).done(function (resp) {
      if (isOk(resp)) {
        $result.html('<div class="pal-result-msg pal-success">操作成功</div>');
        setTimeout(function () { closeRoleDialog(); loadUsers(1); }, 1000);
      } else {
        $result.html('<div class="pal-result-msg pal-error">' + esc(resp.message || '操作失败') + '</div>');
      }
    }).fail(function (xhr) {
      var msg = '操作失败';
      try { var r = JSON.parse(xhr.responseText); msg = r.message || msg; } catch(e) { msg = xhr.statusText || msg; }
      $result.html('<div class="pal-result-msg pal-error">' + esc(msg) + '</div>');
    });
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // 暴露给 Palantir tab 切换时调用
  window.loadAccountTab = function () {
    var $c = jQuery('#beenest-account-mgmt');
    if ($c.children().length === 0) { render(); }
    else { loadUsers(1); }
  };

})();