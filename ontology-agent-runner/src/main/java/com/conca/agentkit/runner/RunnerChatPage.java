package com.conca.agentkit.runner;

/**
 * 导出 jar 内嵌极简聊天页：与平台 chat.html 同一套 SSE 渲染语义（session/round/thinking/tool/answer/done/error）。
 * 页面由 AgentRunnerMain 的 GET / 直接返回，无外部静态资源依赖（单 jar 自包含的前提）。
 */
final class RunnerChatPage {

    private RunnerChatPage() {
    }

    static String html() {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>智能体对话</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; background: #f5f6f8; height: 100vh; display: flex; flex-direction: column; }
  header { background: #fff; border-bottom: 1px solid #e5e7eb; padding: 12px 20px; font-size: 15px; font-weight: 600; color: #1f2937; }
  header small { display: block; font-weight: normal; color: #6b7280; font-size: 12px; margin-top: 2px; }
  #msgs { flex: 1; overflow-y: auto; padding: 20px; }
  .bubble { max-width: 76%; margin: 0 0 14px; padding: 10px 14px; border-radius: 10px; font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
  .user { margin-left: auto; background: #2563eb; color: #fff; }
  .agent { background: #fff; border: 1px solid #e5e7eb; color: #1f2937; }
  details { font-size: 12px; color: #6b7280; margin: 4px 0; }
  details div { font-family: Consolas, monospace; background: #f9fafb; border-radius: 6px; padding: 8px; margin-top: 4px; max-height: 200px; overflow: auto; }
  .greet { background: #eff6ff; border: 1px solid #bfdbfe; color: #1e40af; }
  #bar { display: flex; gap: 10px; padding: 14px 20px; background: #fff; border-top: 1px solid #e5e7eb; }
  #input { flex: 1; border: 1px solid #d1d5db; border-radius: 8px; padding: 10px 12px; font-size: 14px; resize: none; height: 44px; font-family: inherit; }
  #send { background: #2563eb; color: #fff; border: none; border-radius: 8px; padding: 0 22px; font-size: 14px; cursor: pointer; }
  #send:disabled { background: #93c5fd; cursor: not-allowed; }
  .err { color: #b91c1c; }
</style>
</head>
<body>
<header><span id="title">智能体</span><small id="desc"></small></header>
<div id="msgs"></div>
<div id="bar">
  <textarea id="input" placeholder="输入问题，Enter 发送，Shift+Enter 换行"></textarea>
  <button id="send">发送</button>
</div>
<script>
let sessionId = null, streaming = false, cur = null;

async function init() {
  try {
    const m = await fetch('/meta').then(r => r.json());
    document.getElementById('title').textContent = m.name || '智能体';
    document.getElementById('desc').textContent = m.description || '';
    if (m.greeting) bubble(m.greeting, 'agent greet');
  } catch (e) {}
}

function bubble(text, cls) {
  const b = document.createElement('div');
  b.className = 'bubble ' + cls;
  b.textContent = text;
  document.getElementById('msgs').appendChild(b);
  scroll();
  return b;
}

function round() {
  const box = document.createElement('div');
  box.innerHTML = '<details style="display:none"><summary>思考过程</summary><div></div></details>' +
                  '<details style="display:none"><summary>执行过程</summary><div></div></details>' +
                  '<div class="bubble agent"><span class="answer"></span></div>';
  document.getElementById('msgs').appendChild(box);
  scroll();
  return { root: box, think: box.children[0], tool: box.children[1], answer: box.children[2].firstChild };
}

function ensureRound() { return cur || (cur = round()); }
function scroll() { document.getElementById('msgs').scrollTop = 1e9; }

async function send() {
  const input = document.getElementById('input');
  const text = input.value.trim();
  if (!text || streaming) return;
  streaming = true;
  document.getElementById('send').disabled = true;
  input.value = '';
  cur = null;
  bubble(text, 'user');
  try {
    const res = await fetch('/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: sessionId, message: text })
    });
    if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
    const reader = res.body.getReader(), dec = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf('\\n\\n')) >= 0) {
        const block = buf.slice(0, i);
        buf = buf.slice(i + 2);
        if (block.trim()) handle(block);
      }
    }
    if (buf.trim()) handle(buf);
  } catch (e) {
    const ui = ensureRound();
    ui.answer.textContent = '\\n[连接失败] ' + (e.message || '网络异常');
    ui.answer.className = 'err';
  } finally {
    streaming = false;
    document.getElementById('send').disabled = false;
    input.focus();
  }
}

function handle(block) {
  let event = 'message', data = '';
  for (const line of block.split('\\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data += line.slice(5).trim();
  }
  if (!data) return;
  let p = {};
  try { p = JSON.parse(data); } catch (e) { p = { delta: data }; }
  if (event === 'session') {
    sessionId = p.sessionId || sessionId;
  } else if (event === 'round') {
    if (p.phase === 'start') cur = round();
  } else if (event === 'thinking') {
    const ui = ensureRound();
    ui.think.style.display = '';
    ui.think.children[1].textContent += (p.delta || '');
    ui.think.open = true;
  } else if (event === 'tool') {
    const ui = ensureRound();
    ui.tool.style.display = '';
    ui.tool.children[1].textContent += (p.name ? '\\n[' + p.phase + '] ' + p.name : p.delta || '');
    ui.tool.open = true;
  } else if (event === 'answer') {
    ensureRound().answer.textContent += (p.delta || '');
  } else if (event === 'error') {
    const ui = ensureRound();
    ui.answer.textContent = '\\n[服务错误] ' + (p.message || '未知错误');
    ui.answer.className = 'err';
  }
  scroll();
}

document.getElementById('send').addEventListener('click', send);
document.getElementById('input').addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
init();
</script>
</body>
</html>
""";
    }
}
