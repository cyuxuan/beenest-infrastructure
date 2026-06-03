/**
 * Palantir 服务授权 Tab 交互逻辑
 * 依赖：jQuery（Palantir 页面内置）、Palantir actuatorEndpoints
 * 注意：脚本在 jQuery 之前加载，所以使用延迟初始化模式
 * 后端响应格式：R<T> = { code: 200, message: "success", data: {...} }
 */
(function () {
  'use strict';

  var API = {
    services: '/cas/actuator/serviceAuthorization',
    grant: '/cas/actuator/serviceAuthorization/grant',
    revoke: '/cas/actuator/serviceAuthorization/revoke'
  };

  var selectedService = null;

  /** 判断 R<T> 响应是否成功 */
  function isOk(resp) { return resp && resp.code === 200; }

  /** 获取 R<T> 的 data 字段 */
  function getData(resp) { return resp && resp.data; }

  /** 渲染服务授权 Tab */
  function render() {
    var $container = jQuery('#beenest-service-auth');
    $container.html(
      '<div class="pal-tab-header">' +
        '<h3>服务授权</h3>' +
      '</div>' +
      '<div class="pal-split-layout">' +
        // 左栏：服务列表
        '<div class="pal-split-left">' +
          '<h4>已注册服务</h4>' +
          '<input type="text" class="pal-search-input" id="pal-svc-search" placeholder="搜索服务…" style="max-width:100%;margin-bottom:10px">' +
          '<div class="pal-card-list" id="pal-svc-list"></div>' +
        '</div>' +
        // 右栏：已授权用户
        '<div class="pal-split-right">' +
          '<div id="pal-svc-detail">' +
            '<div class="pal-empty-state">请选择左侧服务查看授权用户</div>' +
          '</div>' +
        '</div>' +
      '</div>' +
      '<div class="pal-dialog" id="pal-auth-dialog" style="display:none"></div>'
    );
    bindEvents();
    loadServices();
  }

  function bindEvents() {
    jQuery(document)
      .off('input.pal-auth', '#pal-svc-search')
      .on('input.pal-auth', '#pal-svc-search', filterServices)
      .off('click.pal-auth', '.pal-svc-card')
      .on('click.pal-auth', '.pal-svc-card', function () {
        selectedService = { id: jQuery(this).data('svc-id'), name: jQuery(this).data('svc-name') };
        jQuery('.pal-svc-card').removeClass('active');
        jQuery(this).addClass('active');
        loadServiceUsers();
      })
      .off('click.pal-auth', '.pal-grant-btn')
      .on('click.pal-auth', '.pal-grant-btn', function () {
        openAuthDialog(selectedService.id, selectedService.name, 'grant');
      })
      .off('click.pal-auth', '.pal-revoke-user-btn')
      .on('click.pal-auth', '.pal-revoke-user-btn', function () {
        var userId = jQuery(this).data('user-id');
        var username = jQuery(this).data('username');
        doRevoke(selectedService.id, userId, username);
      })
      .off('click.pal-auth', '#pal-auth-submit')
      .on('click.pal-auth', '#pal-auth-submit', submitAuthChange)
      .off('click.pal-auth', '#pal-auth-cancel')
      .on('click.pal-auth', '#pal-auth-cancel', closeAuthDialog);
  }

  function loadServices() {
    var $list = jQuery('#pal-svc-list');
    $list.html('<div class="pal-loading">加载中…</div>');

    jQuery.ajax({
      url: API.services,
      method: 'GET'
    }).done(function (resp) {
      // R<List<ServiceInfoVO>> 响应，data 直接是数组
      var svcs = getData(resp);
      if (!isOk(resp) || !Array.isArray(svcs)) {
        $list.html('<div class="pal-empty-state">加载失败</div>');
        return;
      }
      if (svcs.length === 0) {
        $list.html('<div class="pal-empty-state">暂无注册服务</div>');
        return;
      }
      var html = '';
      jQuery.each(svcs, function (_, s) {
        var roleHint = s.requiredRole ? ' [' + esc(s.requiredRole) + ']' : ' [开放访问]';
        html += '<div class="pal-card-item pal-svc-card" data-svc-id="' + esc(s.id) + '" data-svc-name="' + esc(s.name) + '">' +
          '<div class="pal-card-item-name">' + esc(s.name) + roleHint + '</div>' +
          '<div class="pal-card-item-detail">ID: ' + esc(s.id) + '</div>' +
        '</div>';
      });
      $list.html(html);
    }).fail(function (xhr) {
      $list.html('<div class="pal-empty-state">加载失败</div>');
    });
  }

  function filterServices() {
    var q = (jQuery('#pal-svc-search').val() || '').toLowerCase();
    jQuery('.pal-svc-card').each(function () {
      var name = (jQuery(this).data('svc-name') || '').toLowerCase();
      jQuery(this).toggle(name.indexOf(q) >= 0);
    });
  }

  function loadServiceUsers() {
    if (!selectedService) return;
    var $detail = jQuery('#pal-svc-detail');

    $detail.html(
      '<h4>' + esc(selectedService.name) + ' - 授权用户</h4>' +
      '<div class="pal-toolbar">' +
        '<button class="pal-btn pal-btn-primary pal-grant-btn">授权用户</button>' +
      '</div>' +
      '<div class="pal-table-container">' +
        '<table class="pal-table" id="pal-svc-user-table">' +
          '<thead><tr><th>ID</th><th>用户名</th><th>手机号</th><th>角色</th><th>操作</th></tr></thead>' +
          '<tbody><tr><td colspan="5" class="pal-loading">加载中…</td></tr></tbody>' +
        '</table>' +
      '</div>'
    );

    jQuery.ajax({
      url: API.services + '/' + selectedService.id + '/users',
      method: 'GET'
    }).done(function (resp) {
      // R<ServiceUsersVO> 响应
      var d = getData(resp);
      if (!isOk(resp) || !d) {
        jQuery('#pal-svc-user-table tbody').html('<tr><td colspan="5" class="pal-empty-state">加载失败</td></tr>');
        return;
      }

      // 开放访问的服务无需授权
      if (d.openAccess) {
        jQuery('#pal-svc-user-table tbody').html('<tr><td colspan="5" class="pal-empty-state">该服务为开放访问，所有已认证用户均可使用</td></tr>');
        return;
      }

      var users = d.users || [];
      var $tbody = jQuery('#pal-svc-user-table tbody');

      if (users.length === 0) {
        $tbody.html('<tr><td colspan="5" class="pal-empty-state">暂无授权用户</td></tr>');
        return;
      }

      var html = '';
      jQuery.each(users, function (_, u) {
        // roles 是 List<String>
        var rolesArr = Array.isArray(u.roles) ? u.roles : [];
        var roleBadges = rolesArr.map(function (r) {
          return '<span class="pal-badge pal-badge-' + roleBadgeClass(r) + '">' + esc(r) + '</span>';
        }).join(' ');

        html += '<tr>' +
          '<td>' + esc(u.userId) + '</td>' +
          '<td>' + esc(u.username) + '</td>' +
          '<td>' + esc(u.phone || '-') + '</td>' +
          '<td>' + roleBadges + '</td>' +
          '<td><button class="pal-btn pal-btn-sm pal-btn-danger pal-revoke-user-btn" data-user-id="' + esc(u.userId) + '" data-username="' + esc(u.username) + '">撤销</button></td>' +
        '</tr>';
      });
      $tbody.html(html);
    }).fail(function () {
      jQuery('#pal-svc-user-table tbody').html('<tr><td colspan="5" class="pal-empty-state">加载失败</td></tr>');
    });
  }

  function roleBadgeClass(role) {
    switch ((role || '').toUpperCase()) {
      case 'ADMIN': return 'danger';
      case 'PILOT': return 'info';
      case 'CUSTOMER': return 'warning';
      default: return 'success';
    }
  }

  function openAuthDialog(serviceId, serviceName, mode) {
    var $dialog = jQuery('#pal-auth-dialog');
    $dialog.html(
      '<div class="pal-dialog-overlay"></div>' +
      '<div class="pal-dialog-content">' +
        '<h4>授权用户 - ' + esc(serviceName) + '</h4>' +
        '<div class="pal-form-group">' +
          '<label>搜索用户</label>' +
          '<input type="text" class="pal-input" id="pal-auth-user-search" placeholder="输入用户名或手机号搜索">' +
        '</div>' +
        '<div class="pal-search-results" id="pal-auth-search-results"></div>' +
        '<div class="pal-form-group" id="pal-auth-selected" style="display:none">' +
          '<label>已选择用户</label>' +
          '<div id="pal-auth-selected-user" style="font-size:13px;color:var(--pal-text-0)"></div>' +
        '</div>' +
        '<div id="pal-auth-result"></div>' +
        '<div class="pal-dialog-actions">' +
          '<button class="pal-btn" id="pal-auth-cancel">取消</button>' +
          '<button class="pal-btn pal-btn-primary" id="pal-auth-submit" data-svc-id="' + serviceId + '" disabled>确认授权</button>' +
        '</div>' +
      '</div>'
    ).show();

    // 搜索用户（带防抖）
    var searchTimer = null;
    jQuery(document).off('input.pal-auth-search').on('input.pal-auth-search', '#pal-auth-user-search', function () {
      var q = jQuery(this).val().trim();
      clearTimeout(searchTimer);
      if (q.length < 2) { jQuery('#pal-auth-search-results').empty(); return; }
      searchTimer = setTimeout(function () {
        jQuery.ajax({
          url: API.services + '/searchUsers',
          method: 'GET',
          data: { q: q }
        }).done(function (resp) {
          // R<List<UserDetailVO>> 响应，data 直接是数组
          var users = getData(resp);
          if (!isOk(resp) || !Array.isArray(users) || users.length === 0) {
            jQuery('#pal-auth-search-results').html('<div style="padding:8px;color:var(--pal-text-2)">无匹配用户</div>');
            return;
          }
          var html = '';
          jQuery.each(users, function (_, u) {
            html += '<div class="pal-search-result-item" data-user-id="' + esc(u.userId) + '" data-username="' + esc(u.username) + '">' +
              esc(u.username) + (u.phone ? ' (' + esc(u.phone) + ')' : '') +
            '</div>';
          });
          jQuery('#pal-auth-search-results').html(html);
        }).fail(function () {
          jQuery('#pal-auth-search-results').html('<div style="padding:8px;color:var(--pal-text-2)">搜索失败</div>');
        });
      }, 300);
    });

    // 选择用户
    jQuery(document).off('click.pal-auth-select').on('click.pal-auth-select', '.pal-search-result-item', function () {
      var userId = jQuery(this).data('userId');
      var username = jQuery(this).data('username');
      jQuery('#pal-auth-selected').show();
      jQuery('#pal-auth-selected-user').text(username + ' (ID: ' + userId + ')');
      jQuery('#pal-auth-submit').prop('disabled', false).data('userId', userId);
      jQuery('#pal-auth-search-results').empty();
      jQuery('#pal-auth-user-search').val('');
    });
  }

  function closeAuthDialog() {
    jQuery('#pal-auth-dialog').hide().empty();
    jQuery(document).off('input.pal-auth-search click.pal-auth-select');
  }

  function submitAuthChange() {
    var serviceId = jQuery('#pal-auth-submit').data('svcId');
    var userId = jQuery('#pal-auth-submit').data('userId');
    var $result = jQuery('#pal-auth-result');

    if (!userId) { $result.html('<div class="pal-result-msg pal-error">请选择用户</div>'); return; }

    jQuery.ajax({
      url: API.grant,
      method: 'POST',
      data: JSON.stringify({ serviceId: Number(serviceId), userId: userId }),
      contentType: 'application/json'
    }).done(function (resp) {
      if (isOk(resp)) {
        $result.html('<div class="pal-result-msg pal-success">授权成功</div>');
        setTimeout(function () { closeAuthDialog(); loadServiceUsers(); }, 1000);
      } else {
        $result.html('<div class="pal-result-msg pal-error">' + esc(resp.message || '授权失败') + '</div>');
      }
    }).fail(function (xhr) {
      var msg = '授权失败';
      try { var r = JSON.parse(xhr.responseText); msg = r.message || msg; } catch(e) { msg = xhr.statusText || msg; }
      $result.html('<div class="pal-result-msg pal-error">' + esc(msg) + '</div>');
    });
  }

  function doRevoke(serviceId, userId, username) {
    if (!confirm('确认撤销用户 ' + username + ' 的授权？')) return;

    jQuery.ajax({
      url: API.revoke,
      method: 'POST',
      data: JSON.stringify({ serviceId: Number(serviceId), userId: userId }),
      contentType: 'application/json'
    }).done(function (resp) {
      if (isOk(resp)) {
        loadServiceUsers();
      } else {
        alert(resp.message || '撤销失败');
      }
    }).fail(function (xhr) {
      var msg = '撤销失败';
      try { var r = JSON.parse(xhr.responseText); msg = r.message || msg; } catch(e) { msg = xhr.statusText || msg; }
      alert(msg);
    });
  }

  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // 暴露给 Palantir tab 切换时调用
  window.loadAuthorizationTab = function () {
    var $c = jQuery('#beenest-service-auth');
    if ($c.children().length === 0) { render(); }
    else { loadServices(); }
  };

})();
