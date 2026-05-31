/**
 * Palantir 用户管理 Tab 前端交互。
 * <p>
 * 通过 actuatorEndpoints.casUsers 调用 CasUsersEndpoint API。
 */
(function () {
    'use strict';

    window.loadUsers = loadUsers;
    window.showAddUserDialog = showAddUserDialog;
    window.hideAddUserDialog = hideAddUserDialog;
    window.submitInvitation = submitInvitation;
    window.disableUser = disableUser;
    window.enableUser = enableUser;
    window.unlockUser = unlockUser;
    window.forceChangePassword = forceChangePassword;

    let currentPage = 0;
    const pageSize = 20;

    /**
     * 加载用户列表
     */
    function loadUsers(page) {
        currentPage = page || 0;
        const query = document.getElementById('userSearchInput').value.trim();
        const statusEl = document.getElementById('userStatusFilter');
        const status = statusEl.value ? parseInt(statusEl.value) : null;

        let url = '/actuator/casUsers?';
        const params = new URLSearchParams();
        if (query) params.set('query', query);
        if (status !== null) params.set('status', status);
        params.set('page', currentPage);
        params.set('size', pageSize);
        url += params.toString();

        fetch(url, {
            method: 'GET',
            headers: { 'Accept': 'application/json' }
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                renderUserList(data.users || []);
                renderPagination(data);
            })
            .catch(function (e) {
                var tbody = document.getElementById('userListBody');
                tbody.textContent = '';
                var td = document.createElement('td');
                td.colSpan = 10;
                td.className = 'pal-error';
                td.textContent = '加载失败: ' + e.message;
                var tr = document.createElement('tr');
                tr.appendChild(td);
                tbody.appendChild(tr);
            });
    }

    function renderUserList(users) {
        var tbody = document.getElementById('userListBody');
        tbody.textContent = '';
        if (users.length === 0) {
            var td = document.createElement('td');
            td.colSpan = 10;
            td.textContent = '暂无数据';
            tbody.appendChild(document.createElement('tr').appendChild(td));
            return;
        }
        users.forEach(function (u) {
            var tr = document.createElement('tr');
            tr.appendChild(createTd(truncate(u.userId, 8), u.userId));
            tr.appendChild(createTd(u.username || '-'));
            tr.appendChild(createTd(u.nickname || '-'));
            tr.appendChild(createTd(u.phone || '-'));
            tr.appendChild(createTd(u.email || '-'));
            tr.appendChild(createTd(u.userType || '-'));
            // 状态
            var statusTd = document.createElement('td');
            statusTd.appendChild(createStatusBadge(u.status));
            tr.appendChild(statusTd);
            // 角色
            var rolesTd = document.createElement('td');
            rolesTd.appendChild(createRoleBadges(u.roles));
            tr.appendChild(rolesTd);
            tr.appendChild(createTd(u.lastLoginTime || '-'));
            // 操作
            var actionsTd = document.createElement('td');
            actionsTd.appendChild(createActionButtons(u));
            tr.appendChild(actionsTd);
            tbody.appendChild(tr);
        });
    }

    function createTd(text, title) {
        var td = document.createElement('td');
        td.textContent = text || '-';
        if (title) td.title = title;
        return td;
    }

    function createStatusBadge(status) {
        var span = document.createElement('span');
        span.className = 'pal-badge';
        switch (status) {
            case 1:
                span.className += ' pal-badge-success';
                span.textContent = '正常';
                break;
            case 2:
                span.className += ' pal-badge-warning';
                span.textContent = '锁定';
                break;
            case 3:
                span.className += ' pal-badge-danger';
                span.textContent = '禁用';
                break;
            default:
                span.textContent = '未知';
        }
        return span;
    }

    function createRoleBadges(roles) {
        var frag = document.createDocumentFragment();
        if (!roles) {
            frag.textContent = '-';
            return frag;
        }
        roles.split(',').forEach(function (r, i) {
            if (i > 0) frag.appendChild(document.createTextNode(' '));
            var span = document.createElement('span');
            span.className = 'pal-badge pal-badge-info';
            span.textContent = r.trim();
            frag.appendChild(span);
        });
        return frag;
    }

    function createActionButtons(u) {
        var frag = document.createDocumentFragment();
        if (u.status === 1) {
            frag.appendChild(createButton('禁用', 'pal-btn pal-btn-sm pal-btn-danger', function () { disableUser(u.userId); }));
        }
        if (u.status === 3) {
            frag.appendChild(createButton('启用', 'pal-btn pal-btn-sm pal-btn-success', function () { enableUser(u.userId); }));
        }
        if (u.status === 2) {
            frag.appendChild(createButton('解锁', 'pal-btn pal-btn-sm pal-btn-warning', function () { unlockUser(u.userId); }));
        }
        frag.appendChild(createButton('重置密码', 'pal-btn pal-btn-sm', function () { forceChangePassword(u.userId); }));
        return frag;
    }

    function createButton(label, className, onclick) {
        var btn = document.createElement('button');
        btn.className = className;
        btn.textContent = label;
        btn.addEventListener('click', onclick);
        return btn;
    }

    function renderPagination(data) {
        var total = data.total || 0;
        var page = data.page || 0;
        var totalPages = Math.ceil(total / pageSize);
        var container = document.getElementById('userPagination');
        container.textContent = '';
        if (totalPages <= 1) return;
        var span = document.createElement('span');
        span.textContent = '共 ' + total + ' 条  第 ' + (page + 1) + '/' + totalPages + ' 页';
        container.appendChild(span);
        if (page > 0) {
            container.appendChild(document.createTextNode(' '));
            container.appendChild(createButton('上一页', 'pal-btn pal-btn-sm', function () { loadUsers(page - 1); }));
        }
        if (data.hasMore) {
            container.appendChild(document.createTextNode(' '));
            container.appendChild(createButton('下一页', 'pal-btn pal-btn-sm', function () { loadUsers(page + 1); }));
        }
    }

    function disableUser(userId) {
        if (!confirm('确认禁用该用户？')) return;
        sendUserUpdate(userId, { status: 3 });
    }

    function enableUser(userId) {
        sendUserUpdate(userId, { status: 1 });
    }

    function unlockUser(userId) {
        sendUserUpdate(userId, { action: 'unlock' });
    }

    function forceChangePassword(userId) {
        if (!confirm('确认强制该用户下次登录修改密码？')) return;
        sendUserUpdate(userId, { mustChangePassword: true });
    }

    function sendUserUpdate(userId, body) {
        var params = new URLSearchParams();
        if (body.status !== undefined) params.set('status', body.status);
        if (body.action) params.set('action', body.action);
        if (body.mustChangePassword) params.set('mustChangePassword', 'true');

        fetch('/actuator/casUsers/' + encodeURIComponent(userId) + '?' + params.toString(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
        })
            .then(function (r) { return r.json(); })
            .then(function () { loadUsers(currentPage); })
            .catch(function (e) { alert('操作失败: ' + e.message); });
    }

    function showAddUserDialog() {
        document.getElementById('addUserDialog').classList.remove('d-none');
        document.getElementById('addUserResult').classList.add('d-none');
    }

    function hideAddUserDialog() {
        document.getElementById('addUserDialog').classList.add('d-none');
    }

    function submitInvitation() {
        var username = document.getElementById('addUsername').value.trim();
        var email = document.getElementById('addEmail').value.trim();
        var phone = document.getElementById('addPhone').value.trim();
        var roles = document.getElementById('addRoles').value.trim();

        if (!username) {
            alert('用户名不能为空');
            return;
        }

        var params = new URLSearchParams();
        params.set('username', username);
        if (email) params.set('email', email);
        if (phone) params.set('phone', phone);
        if (roles) params.set('roles', roles);

        fetch('/actuator/casUsers/' + encodeURIComponent(username) + '?' + params.toString(), {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
        })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                var resultEl = document.getElementById('addUserResult');
                resultEl.classList.remove('d-none');
                resultEl.className = 'pal-result-msg ' + (data.success ? 'pal-success' : 'pal-error');
                resultEl.textContent = data.success
                    ? '用户创建成功！ID: ' + (data.userId || '')
                    : '创建失败: ' + (data.message || '未知错误');
                if (data.success) loadUsers(0);
            })
            .catch(function (e) {
                var resultEl = document.getElementById('addUserResult');
                resultEl.classList.remove('d-none');
                resultEl.className = 'pal-result-msg pal-error';
                resultEl.textContent = '请求失败: ' + e.message;
            });
    }

    function truncate(str, len) {
        if (!str) return '';
        return str.length > len ? str.substring(0, len) + '...' : str;
    }
})();