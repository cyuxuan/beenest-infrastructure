(function () {
  "use strict";

  /* ─── Chart.js 全局暗色默认值 ─────────────────────────── */
  function applyChartTheme() {
    if (typeof Chart === "undefined") return false;
    Chart.defaults.color = "#94a3b8";
    Chart.defaults.borderColor = "rgba(255,255,255,0.06)";
    Chart.defaults.plugins.legend.labels.color = "#94a3b8";
    Chart.defaults.scale.grid.color = "rgba(255,255,255,0.04)";
    Chart.defaults.scale.ticks.color = "#64748b";
    return true;
  }

  /* ─── Ace Editor 暗色主题切换 ─────────────────────────── */
  var aceThemeApplied = false;

  function applyAceTheme() {
    if (typeof ace === "undefined") return;
    var editors = document.querySelectorAll(".ace_editor");
    if (editors.length === 0) return;

    editors.forEach(function (el) {
      try {
        var editor = ace.edit(el);
        if (editor.getTheme().indexOf("twilight") === -1) {
          editor.setTheme("ace/theme/twilight");
        }
      } catch (e) {}
    });
    aceThemeApplied = true;
  }

  /* ─── Highlight.js 暗色主题 ───────────────────────────── */
  var hljsDarkLoaded = false;

  function applyHljsDarkTheme() {
    if (hljsDarkLoaded) return;
    if (typeof hljs === "undefined") return;

    /* 动态注入 atom-one-dark 配色（避免额外 HTTP 请求） */
    var style = document.createElement("style");
    style.id = "palantir-hljs-dark";
    style.textContent = [
      "body.beenest-palantir-dashboard .hljs { background: #0f1629 !important; color: #e2e8f0 !important; }",
      "body.beenest-palantir-dashboard .hljs-comment, body.beenest-palantir-dashboard .hljs-quote { color: #64748b !important; font-style: italic; }",
      "body.beenest-palantir-dashboard .hljs-keyword, body.beenest-palantir-dashboard .hljs-selector-tag { color: #c792ea !important; }",
      "body.beenest-palantir-dashboard .hljs-string, body.beenest-palantir-dashboard .hljs-addition { color: #c3e88d !important; }",
      "body.beenest-palantir-dashboard .hljs-number, body.beenest-palantir-dashboard .hljs-literal { color: #f78c6c !important; }",
      "body.beenest-palantir-dashboard .hljs-title, body.beenest-palantir-dashboard .hljs-section { color: #82aaff !important; }",
      "body.beenest-palantir-dashboard .hljs-built_in { color: #ffcb6b !important; }",
      "body.beenest-palantir-dashboard .hljs-attr, body.beenest-palantir-dashboard .hljs-attribute { color: #c3e88d !important; }",
      "body.beenest-palantir-dashboard .hljs-name, body.beenest-palantir-dashboard .hljs-tag { color: #f07178 !important; }",
      "body.beenest-palantir-dashboard .hljs-variable, body.beenest-palantir-dashboard .hljs-template-variable { color: #eeffff !important; }",
      "body.beenest-palantir-dashboard .hljs-deletion { color: #f07178 !important; }",
      "body.beenest-palantir-dashboard .hljs-type { color: #ffcb6b !important; }",
      "body.beenest-palantir-dashboard .hljs-symbol, body.beenest-palantir-dashboard .hljs-bullet { color: #89ddff !important; }",
      "body.beenest-palantir-dashboard .hljs-meta { color: #ffcb6b !important; }"
    ].join("\n");
    document.head.appendChild(style);
    hljsDarkLoaded = true;
  }

  /* ─── jQuery UI SelectMenu 下拉暗色修补 ───────────────── */
  function patchJqueryUiSelectMenu() {
    if (!window.jQuery || !window.jQuery.ui || !window.jQuery.ui.selectmenu) return;

    var origOpen = window.jQuery.ui.selectmenu.prototype._open;
    if (origOpen && !origOpen.__palantirPatched) {
      window.jQuery.ui.selectmenu.prototype._open = function () {
        var result = origOpen.apply(this, arguments);
        var menu = this.menuWrap;
        if (menu) {
          menu.find(".ui-menu").css({
            background: "#151d33",
            border: "1px solid rgba(255,255,255,0.06)",
            borderRadius: "8px",
            boxShadow: "0 8px 24px rgba(0,0,0,0.3)"
          });
          menu.find(".ui-menu-item-wrapper").css({
            color: "#94a3b8"
          });
        }
        return result;
      };
      window.jQuery.ui.selectmenu.prototype._open.__palantirPatched = true;
    }
  }

  /* ─── palantir.js 分组行内联样式修补 ───────────────────── */
  function patchGroupRowColors() {
    if (!window.jQuery || !window.jQuery.fn || !window.jQuery.fn.dataTable) return;

    /* palantir.js 在 drawCallback 中注入 inline style:
       style='font-weight: bold; background-color:var(--cas-theme-primary);
              color:var(--mdc-text-button-label-text-color);'
       CSS 变量已被我们覆盖，所以颜色会自动跟随。
       但我们可以通过 MutationObserver 在运行时微调。 */
    var observer = new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        m.addedNodes.forEach(function (node) {
          if (node.nodeType === 1 && node.tagName === "TR") {
            var style = node.getAttribute("style");
            if (style && style.indexOf("cas-theme-primary") >= 0) {
              node.style.background = "rgba(6,182,212,0.08)";
              node.style.color = "#e2e8f0";
            }
          }
        });
      });
    });

    var dashboard = document.getElementById("dashboard");
    if (dashboard) {
      observer.observe(dashboard, { childList: true, subtree: true });
    }
  }

  /* ─── 初始化调度 ───────────────────────────────────────── */
  var initAttempts = 0;
  var maxInitAttempts = 120; /* ~6 秒 */

  function init() {
    initAttempts++;
    var chartOk = applyChartTheme();
    applyAceTheme();
    applyHljsDarkTheme();
    patchJqueryUiSelectMenu();

    /* palantir.js 初始化完成后再修补分组行 */
    if (document.getElementById("dashboard") && !document.getElementById("dashboard").classList.contains("d-none")) {
      patchGroupRowColors();
    }

    /* 持续监测 Ace 编辑器（动态创建） */
    if (!aceThemeApplied && initAttempts < maxInitAttempts) {
      return false;
    }

    return true;
  }

  function retryInit() {
    if (init()) {
      /* Ace 编辑器可能在后续 tab 切换中动态创建，保持监测 */
      setInterval(applyAceTheme, 2000);
      return;
    }
    if (initAttempts < maxInitAttempts) {
      setTimeout(retryInit, 50);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", retryInit, { once: true });
  } else {
    retryInit();
  }
})();
