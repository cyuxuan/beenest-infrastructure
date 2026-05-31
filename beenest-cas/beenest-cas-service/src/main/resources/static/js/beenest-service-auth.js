/**
 * Palantir 服务授权 Tab 前端交互。
 * <p>
 * 通过 actuatorEndpoints.serviceAuthorization 调用 ServiceAuthorizationEndpoint API。
 */
(function () {
    'use strict';

    window.loadServices = loadServices;
    window.selectService = selectService;
    window.grantAccess = grantAccess;
    window.revokeAccess = revokeAccess;
    window.showGrantDialog = showGrantDialog;
    window.hideGrantDialog = hideGrantDialog;
    window.searchUsersForGrant = searchUsersForGrant;

    let selectedServiceId = null;
    let selectedServiceRole = null;

    /**
     * 加载应用列表
     */
    function loadServices() {
        fetch('/actuator/serviceAuthorization', {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function (services) {
                renderServiceList(services);
            })
            .catch(function (e) {
                var container = document.getElementById('serviceListContainer');
                container.textContent = '加载失败: ' + e.message;
            });
    }

    function renderServiceList(services) {
        var container = document.getElementById('serviceListContainer');
        container.textContent = '';
        services.forEach(function (svc) {
            var card = document.createElement('div');
            card.className = 'pal-card-item';
            card.dataset.serviceId = svc.id;

            var nameEl = document.createElement('div');
            nameEl.className = 'pal-card-item-name';
            nameEl.textContent = svc.name;
            card.appendChild(nameEl);

            var roleEl = document.createElement('div');
            roleEl.className = 'pal-card-item-detail';
            roleEl.textContent = '角色: ' + (svc.requiredRole || '开放访问');
            card.appendChild(roleEl);

            var idEl = document.createElement('div');
            idEl.className = 'pal-card-item-detail';
            idEl.textContent = 'ID: ' + svc.id + ' | ' + (svc.serviceId || '');
            card.appendChild(idEl);

            card.addEventListener('click', function () { selectService(svc.id); });
            container.appendChild(card);
        });
    }

    /**
     * 选择应用，加载其用户列表
     */
    function selectService(serviceId) {
        selectedServiceId = serviceId;
        document.getElementById('noServiceSelected').classList.add('d-none');
        document.getElementById('serviceUserPanel').classList.remove('d-none');

        fetch('/actuator/serviceAuthorization/' + serviceId, {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                selectedServiceRole = data.requiredRole;
                var nameEl = document.getElementById('serviceUserName');
                nameEl.textContent = data.name + ' — 授权用户';
                var infoEl = document.getElementById('serviceUserInfo');
                infoEl.textContent = 'Service ID: ' + data.serviceId + ' | 角色要求: ' + (data.requiredRole || '开放访问');
                renderServiceUsers(data.users || [], data.openAccess);
            })
            .catch(function (e) {
                alert('加载失败: ' + e.message);
            });
    }

    function renderServiceUsers(users, openAccess) {
        var tbody = document.getElementById('serviceUserListBody');
        tbody.textContent = '';
        if (openAccess) {
            var td = document.createElement('td');
            td.colSpan = 6;
            td.textContent = '该服务开放访问，所有已认证用户均可使用';
            tbody.appendChild(document.createElement('tr').appendChild(td));
            return;
        }
        if (users.length === 0) {
            var td = document.createElement('td');
            td.colSpan = 6;
            td.textContent = '暂无授权用户';
            tbody.appendChild(document.createElement('tr').appendChild(td));
            return;
        }
        users.forEach(function (u) {
            var tr = document.createElement('tr');
            tr.appendChild(createTd(u.username || '-'));
            tr.appendChild(createTd(u.nickname || '-'));
            tr.appendChild(createTd(u.userType || '-'));
            tr.appendChild(createTd(u.status === 1 ? '正常' : u.status === 2 ? '锁定' : '禁用'));
            tr.appendChild(createTd(u.roles || '-'));
            // 操作
            var td = document.createElement('td');
            var btn = document.createElement('button');
            btn.className = 'pal-btn pal-btn-sm pal-btn-danger';
            btn.textContent = '移除';
            btn.addEventListener('click', function () { revokeAccess(u.userId); });
            td.appendChild(btn);
            tr.appendChild(td);
            tbody.appendChild(tr);
        });
    }

    function createTd(text) {
        var td = document.createElement('td');
        td.textContent = text;
        return td;
    }

    function revokeAccess(userId) {
        if (!confirm('确认移除该用户对此服务的访问权限？')) return;
        fetch('/actuator/serviceAuthorization/' + selectedServiceId + '?userId=' + encodeURIComponent(userId), {
            method: 'DELETE',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function () { selectService(selectedServiceId); })
            .catch(function (e) { alert('操作失败: ' + e.message); });
    }

    function showGrantDialog() {
        if (!selectedServiceId) { alert('请先选择一个服务'); return; }
        document.getElementById('grantDialog').classList.remove('d-none');
        document.getElementById('grantResult').classList.add('d-none');
        document.getElementById('grantSearchResults').textContent = '';
    }

    function hideGrantDialog() {
        document.getElementById('grantDialog').classList.add('d-none');
    }

    function grantAccess() {
        var userId = document.getElementById('grantUserId').value.trim();
        if (!userId) { alert('请输入用户 ID'); return; }

        fetch('/actuator/serviceAuthorization/' + selectedServiceId + '?userId=' + encodeURIComponent(userId), {
            method: 'POST',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var resultEl = document.getElementById('grantResult');
                resultEl.classList.remove('d-none');
                resultEl.className = 'pal-result-msg ' + (data.success ? 'pal-success' : 'pal-error');
                resultEl.textContent = data.success
                    ? '授权成功！用户角色: ' + (data.roles || '')
                    : '授权失败: ' + (data.error || data.message || '未知错误');
                if (data.success) {
                    hideGrantDialog();
                    selectService(selectedServiceId);
                }
            })
            .catch(function (e) {
                var resultEl = document.getElementById('grantResult');
                resultEl.classList.remove('d-none');
                resultEl.className = 'pal-result-msg pal-error';
                resultEl.textContent = '请求失败: ' + e.message;
            });
    }

    function searchUsersForGrant() {
        var query = document.getElementById('authUserSearchInput').value.trim();
        if (!query) {
            document.getElementById('grantSearchResults').textContent = '';
            return;
        }
        // 使用 casUsers endpoint 搜索用户
        fetch('/actuator/casUsers?query=' + encodeURIComponent(query) + '&size=5', {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var container = document.getElementById('grantSearchResults');
                container.textContent = '';
                var users = data.users || [];
                users.forEach(function (u) {
                    var item = document.createElement('div');
                    item.className = 'pal-search-result-item';
                    item.textContent = u.username + ' (' + u.userId + ') — ' + (u.nickname || '') + ' [' + (u.userType || '') + ']';
                    item.addEventListener('click', function () {
                        document.getElementById('grantUserId').value = u.userId;
                    });
                    container.appendChild(item);
                });
            });
    }
})();