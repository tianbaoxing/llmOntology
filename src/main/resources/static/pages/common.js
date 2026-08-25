/**
 * 公共脚本：header/菜单渲染 + 会话控制 + 公共工具函数。
 * 用法：页面底部引入本文件后调用 CF.renderHeader(opts)、CF.initUser(onUser)。
 *   opts.extra      logo 组后追加的 HTML（如返回链接）
 *   opts.titleSuffix 标题后缀（如 ' · 实例列表'），标题取自 /api/app 的 app.title
 * 公共工具以全局函数暴露（$ / api / showMsg / doAction / esc / code / badge / table / statCard / fmtSize），
 * 页面可用同名函数声明覆盖（如 users 页的 doAction）。
 */
function $(id) { return document.getElementById(id); }

function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
}

async function api(method, url, body) {
  const opt = { method, headers: { 'Content-Type': 'application/json' } };
  if (body !== undefined) opt.body = JSON.stringify(body);
  const res = await fetch(url, opt);
  if (res.status === 401) { location.href = '/login'; throw new Error('未登录或会话已过期'); }
  const json = await res.json();
  if (json.code !== 0) throw new Error(json.msg || ('HTTP ' + res.status));
  return json.data;
}

function showMsg(text, ok) {
  let m = $('msg');
  if (!m) { // 页面未放置 #msg 时自动补到内容区顶部
    m = document.createElement('div');
    m.id = 'msg';
    (document.querySelector('.container') || document.body).insertAdjacentElement('afterbegin', m);
  }
  m.textContent = text;
  m.className = 'msg ' + (ok ? 'ok' : 'err');
  clearTimeout(m._t);
  m._t = setTimeout(() => m.className = 'msg', 4000);
}

function doAction(fn) {
  fn().then(d => showMsg(JSON.stringify(d), true)).catch(e => showMsg('失败：' + e.message, false));
}

function code(s) { return `<code>${esc(s)}</code>`; }

function badge(layer) { return `<span class="badge ${layer}">${layer}</span>`; }

function statCard(num, label) {
  return `<div class="stat-card"><div class="num">${num}</div><div class="label">${label}</div></div>`;
}

function table(heads, rows) {
  return `<table><tr>${heads.map(h => `<th>${h}</th>`).join('')}</tr>` +
    rows.map(r => `<tr>${r.map(c => `<td>${c}</td>`).join('')}</tr>`).join('') + '</table>';
}

function fmtSize(n) {
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
  if (n >= 1024) return (n / 1024).toFixed(1) + ' KB';
  return n + ' B';
}

const CF = (() => {
  /** 顶部菜单（admin 项对普通用户隐藏，由 initUser 控制） */
  const MENU = [
    { path: '/', label: '概览' },
    { path: '/chat', label: 'AI 问答' },
    { path: '/dashboard', label: '驾驶舱' },
    { path: '/files', label: '知识库', admin: true },
    { path: '/data', label: '图谱管理', admin: true },
    { path: '/backup', label: '备份恢复', admin: true },
    { path: '/users', label: '用户管理', admin: true }
  ];
  /** ADMIN 专属页面：普通用户直访时前端跳回概览（接口层另有 403 兜底；/users 由后端 403；驾驶舱为全员自助统计） */
  const ADMIN_PAGES = new Set(['/files', '/data', '/backup']);

  function renderHeader(opts = {}) {
    const cur = location.pathname;
    const tabs = MENU.map(m =>
      `<a class="tab${m.path === cur ? ' active' : ''}${m.admin ? ' admin-tab' : ''}"` +
      `${m.admin ? ' style="display:none"' : ''} href="${m.path}">${m.label}</a>`).join('');
    document.body.insertAdjacentHTML('afterbegin', `
<div class="header">
  <div class="logo-group">
    <svg class="logo" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="logo">
      <rect x="9" y="3" width="5" height="9" rx="1.5" fill="#e3b04b"/>
      <rect x="34" y="3" width="5" height="9" rx="1.5" fill="#e3b04b"/>
      <path d="M5 11 h38 l-4.5 21 h-29 Z" fill="#e3b04b"/>
      <path d="M14 19 L24 15.5 L34 19 L24 27.5 Z" stroke="#8a5a18" stroke-width="1.8" fill="none" opacity=".7"/>
      <circle cx="14" cy="19" r="3" fill="#38bdf8"/>
      <circle cx="34" cy="19" r="3" fill="#38bdf8"/>
      <circle cx="24" cy="15.5" r="3" fill="#f8fafc"/>
      <circle cx="24" cy="27.5" r="3" fill="#38bdf8"/>
      <path d="M12 32 l-3 13 h6 l1.5 -13 Z" fill="#e3b04b"/>
      <path d="M36 32 l3 13 h-6 l-1.5 -13 Z" fill="#e3b04b"/>
    </svg>
    <h1 id="app-title">管理控制台</h1>
  </div>
  ${opts.extra || ''}
  <div class="tabs">${tabs}</div>
  <div class="spacer"></div>
  <div class="user-box" id="user-box" style="display:none">
    <span id="user-name"></span>
    <a class="logout" id="btn-logout">退出登录</a>
  </div>
</div>`);
    // 标题来自启动配置（app.title），获取失败时保留页面默认文案
    fetch('/api/app').then(r => r.json()).then(j => {
      if (j && j.data && j.data.title) $('app-title').textContent = j.data.title + (opts.titleSuffix || '');
    }).catch(() => {});
    $('btn-logout').onclick = async () => {
      try { await fetch('/api/session', { method: 'DELETE' }); } catch (e) {}
      location.href = '/login';
    };
  }

  /** 会话初始化：未登录跳登录页；管理员展开 admin 菜单；普通用户移除并拦截管理页直访 */
  async function initUser(onUser) {
    let me = null;
    try { me = await fetch('/api/session').then(r => r.json()).then(j => j.data); } catch (e) {}
    if (!me) { location.href = '/login'; return; }
    if (me.role === 'ADMIN') {
      document.querySelectorAll('.admin-tab').forEach(el => el.style.display = '');
      document.querySelectorAll('.admin-only').forEach(el => el.style.display = '');
    } else {
      document.querySelectorAll('.admin-tab').forEach(el => el.remove());
      document.querySelectorAll('.admin-only').forEach(el => el.remove());
      if (ADMIN_PAGES.has(location.pathname)) { location.replace('/'); return; }
    }
    $('user-name').textContent = me.username + (me.role === 'ADMIN' ? ' · 管理员' : '');
    $('user-box').style.display = '';
    if (onUser) onUser(me);
  }

  return { renderHeader, initUser };
})();
