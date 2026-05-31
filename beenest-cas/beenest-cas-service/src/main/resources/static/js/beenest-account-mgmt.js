/**
 * Palantir 用户管理 Tab 交互逻辑
 * 依赖：jQuery（Palantir 页面内置）、Palantir actuatorEndpoints
 * 注意：脚本在 jQuery 之前加载，所以使用延迟初始化模式
 */
(function () {
  'use strict';

  var API = {
    list: '/cas/actuator/casUsers',
    addRole: '/cas/actuator/casUsers/addRole',
    removeRole: '/cas/actuator/casUsers/removeRole'
  };

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
      '<div class="pal-dialog" id="pal-role-dialog" style="display:none"></div>'
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
      .on('click.pal-user', '#pal-role-cancel', closeRoleDialog);
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
      var users = (resp.users || []);
      var total = resp.total || 0;
      var totalPages = Math.max(1, Math.ceil(total / 20));

      if (users.length === 0) {
        $tbody.html('<tr><td colspan="7" class="pal-empty-state">暂无用户数据</td></tr>');
        jQuery('#pal-user-pagination').empty();
        return;
      }

      var html = '';
      jQuery.each(users, function (_, u) {
        // roles 可能是逗号分隔字符串或数组
        var rolesArr = Array.isArray(u.roles) ? u.roles
          : (typeof u.roles === 'string' && u.roles ? u.roles.split(',').map(function(r){return r.trim();}) : []);
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
    }).done(function () {
      $result.html('<div class="pal-result-msg pal-success">操作成功</div>');
      setTimeout(function () { closeRoleDialog(); loadUsers(1); }, 1000);
    }).fail(function (xhr) {
      $result.html('<div class="pal-result-msg pal-error">操作失败: ' + (xhr.responseText || xhr.statusText || '未知错误') + '</div>');
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
