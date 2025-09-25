import { WavRecorder } from './wav-recorder.js';

const startBtn = document.getElementById('start');
const stopBtn = document.getElementById('stop');
const statusEl = document.getElementById('status');
const player = document.getElementById('player');
const rolesEl = document.getElementById('roles');
const roleView = document.getElementById('roleView');
const chatView = document.getElementById('chatView');
const chatTitle = document.getElementById('chatTitle');
const chatList = document.getElementById('chatList');

let recorder;
let ws;
let chunkTimer;
let vadTimer;
let speaking = false; // 播放中标志（半双工）
let silenceMs = 0;
let hasVoiceSinceLastFlush = false;
let rmsThreshold = 0.015;
let selectedRoleId = null;
let selectedRoleAvatar = null;
let selectedRoleName = null;
const rolesById = {};

function setStatus(t){ statusEl.textContent = t; }

function detectAudioMime(ab) {
  try {
    const u8 = new Uint8Array(ab);
    // WAV: 'RIFF'....'WAVE'
    if (u8.length >= 12 && u8[0] === 0x52 && u8[1] === 0x49 && u8[2] === 0x46 && u8[3] === 0x46 && u8[8] === 0x57 && u8[9] === 0x41 && u8[10] === 0x56 && u8[11] === 0x45) {
      return 'audio/wav';
    }
    // MP3: 'ID3' or frame sync 0xFFEx
    if (u8.length >= 3 && u8[0] === 0x49 && u8[1] === 0x44 && u8[2] === 0x33) {
      return 'audio/mpeg';
    }
    if (u8.length >= 2 && u8[0] === 0xff && (u8[1] & 0xe0) === 0xe0) {
      return 'audio/mpeg';
    }
  } catch {}
  // 默认按 WAV 处理（我们当前 TTS 多数返回 WAV）
  return 'audio/wav';
}

function createAvatar(avatarUrl, fallbackText) {
  const box = document.createElement('div');
  box.style.width = '32px';
  box.style.height = '32px';
  box.style.borderRadius = '16px';
  box.style.overflow = 'hidden';
  box.style.flex = '0 0 32px';
  box.style.display = 'flex';
  box.style.alignItems = 'center';
  box.style.justifyContent = 'center';
  box.style.background = '#eee';
  if (avatarUrl) {
    const img = document.createElement('img');
    img.src = avatarUrl;
    img.alt = 'avatar';
    img.style.width = '100%';
    img.style.height = '100%';
    img.style.objectFit = 'cover';
    box.appendChild(img);
  } else if (fallbackText) {
    const span = document.createElement('span');
    span.textContent = fallbackText;
    span.style.fontSize = '14px';
    span.style.color = '#555';
    box.appendChild(span);
  }
  return box;
}

function appendBubble(text, who, avatarUrl) {
  const wrap = document.createElement('div');
  wrap.style.display = 'flex';
  wrap.style.margin = '6px 0';
  wrap.style.justifyContent = who === 'me' ? 'flex-end' : 'flex-start';
  wrap.style.gap = '8px';
  const bubble = document.createElement('div');
  bubble.style.maxWidth = '70%';
  bubble.style.padding = '8px 10px';
  bubble.style.borderRadius = '8px';
  bubble.style.whiteSpace = 'pre-wrap';
  bubble.style.wordBreak = 'break-word';
  bubble.style.fontSize = '14px';
  if (who === 'me') {
    bubble.style.background = '#d2f1ff';
  } else {
    bubble.style.background = '#fff';
  }
  bubble.textContent = text;
  const avatar = who === 'me' ? createAvatar(null, '我') : createAvatar(avatarUrl || selectedRoleAvatar, 'A');
  if (who === 'me') {
    wrap.appendChild(bubble);
    wrap.appendChild(avatar);
  } else {
    wrap.appendChild(avatar);
    wrap.appendChild(bubble);
  }
  chatList.appendChild(wrap);
  chatList.scrollTop = chatList.scrollHeight;
}

async function startRecordingLoop() {
  recorder = new WavRecorder({ sampleRate: 16000, numChannels: 1 });
  await recorder.start();
  setStatus('录音中 (WS/VAD)...');
  silenceMs = 0;
  hasVoiceSinceLastFlush = false;
  // VAD: 简单 RMS 阈值
  const minVoiceMs = 150;     // 至少达到这段有声再认为有声
  const minSilenceMs = 600;   // 静音超过该值触发切片
  let voiceAccumMs = 0;
  recorder.onLevel = (rms, frameCount) => {
    // 播放中暂停采集
    if (speaking) { recorder.pause(); return; }
    else { recorder.resume(); }
    const frameMs = (frameCount / recorder.sampleRate) * 1000;
    if (rms >= rmsThreshold) {
      voiceAccumMs += frameMs;
      silenceMs = 0;
      if (voiceAccumMs >= minVoiceMs) hasVoiceSinceLastFlush = true;
    } else {
      silenceMs += frameMs;
    }
  };
  // 定时检查静音状态并触发 flush
  vadTimer = setInterval(async () => {
    try {
      if (speaking) return; // 播放期间不发送
      if (silenceMs >= minSilenceMs && hasVoiceSinceLastFlush) {
        const blob = await recorder.flush();
        hasVoiceSinceLastFlush = false;
        if (ws && ws.readyState === WebSocket.OPEN && blob.size > 0) {
          ws.send(await blob.arrayBuffer());
        }
      }
    } catch (e) {
      setStatus('VAD 发送失败: ' + e.message);
    }
  }, 120);
}

function connectWS() {
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const qs = selectedRoleId ? `?roleId=${encodeURIComponent(selectedRoleId)}` : '';
  const wsUrl = `${wsProtocol}://localhost:8080/ws/voice${qs}`;
  ws = new WebSocket(wsUrl);
  ws.binaryType = 'arraybuffer';
  ws.onopen = async () => {
    setStatus('WS 已连接');
    if (selectedRoleId) startBtn?.removeAttribute('disabled');
    stopBtn?.setAttribute('disabled', 'true');
  };
  ws.onmessage = async (evt) => {
    if (typeof evt.data === 'string') {
      // 文本控制消息，优先解析 JSON
      try {
        const data = JSON.parse(evt.data);
        if (data && data.type === 'text') {
          if (data.user) appendBubble(data.user, 'me', null);
          if (data.ai) {
            const aiAvatar = data.aiRoleId && rolesById[data.aiRoleId]
              ? rolesById[data.aiRoleId].avatar
              : selectedRoleAvatar;
            appendBubble(data.ai, 'ai', aiAvatar);
          }
        }
      } catch {}
      return;
    }
    // 半双工：收到音频先暂停采集
    speaking = true;
    recorder?.pause();
    const mime = detectAudioMime(evt.data);
    const blob = new Blob([evt.data], { type: mime });
    const url = URL.createObjectURL(blob);
    player.src = url;
    try {
      await player.play();
      player.onended = () => {
        speaking = false;
        recorder?.resume();
      };
    } catch {
      speaking = false;
      recorder?.resume();
    }
  };
  ws.onclose = () => {
    setStatus('WS 断开，重连中...');
    if (chunkTimer) clearInterval(chunkTimer);
    setTimeout(connectWS, 1000);
  };
  ws.onerror = () => {
    setStatus('WS 错误');
  };
}
async function loadRoles() {
  try {
    const res = await fetch('/api/roles');
    const roles = await res.json();
    rolesEl.innerHTML = '';
    const resolveAvatar = (p) => {
      if (!p) return null;
      if (p.startsWith('http://') || p.startsWith('https://')) return p;
      if (p.startsWith('/')) return p; // 从 Vite public 提供
      return '/' + p.replace(/^\/+/, '');
    };
    roles.forEach(r => {
      rolesById[r.id] = r;
      const card = document.createElement('div');
      card.style.border = '1px solid #ccc';
      card.style.padding = '8px';
      card.style.cursor = 'pointer';
      card.style.width = '140px';

      const center = document.createElement('div');
      center.style.textAlign = 'center';

      const avatarBox = document.createElement('div');
      avatarBox.style.width = '80px';
      avatarBox.style.height = '80px';
      avatarBox.style.borderRadius = '40px';
      avatarBox.style.margin = '0 auto 6px';
      avatarBox.style.overflow = 'hidden';
      avatarBox.style.background = '#eee';
      avatarBox.style.display = 'flex';
      avatarBox.style.alignItems = 'center';
      avatarBox.style.justifyContent = 'center';

      const img = document.createElement('img');
      img.src = resolveAvatar(r.avatar);
      img.alt = r.name || 'avatar';
      img.style.width = '100%';
      img.style.height = '100%';
      img.style.objectFit = 'cover';
      img.onerror = () => {
        img.style.display = 'none';
        const span = document.createElement('span');
        span.textContent = (r.name || 'A').slice(0, 1);
        span.style.fontSize = '28px';
        span.style.color = '#666';
        avatarBox.appendChild(span);
      };
      avatarBox.appendChild(img);

      const nameDiv = document.createElement('div');
      nameDiv.textContent = r.name || '';

      center.appendChild(avatarBox);
      center.appendChild(nameDiv);
      card.appendChild(center);

      card.onclick = () => {
        selectedRoleId = r.id;
        selectedRoleAvatar = resolveAvatar(r.avatar) || null;
        selectedRoleName = r.name || '';
        chatTitle.textContent = `聊天 - ${r.name}`;
        roleView.style.display = 'none';
        chatView.style.display = '';
        setStatus('正在连接...');
        connectWS();
      };
      rolesEl.appendChild(card);
    });
  } catch (e) {
    setStatus('加载角色失败: ' + e.message);
  }
}

// 绑定按钮
startBtn?.addEventListener('click', async () => {
  if (ws?.readyState !== WebSocket.OPEN) {
    setStatus('WS 未连接，重试中...');
    return;
  }
  try {
    await startRecordingLoop();
    startBtn?.setAttribute('disabled', 'true');
    stopBtn?.removeAttribute('disabled');
  } catch (e) {
    setStatus('录音失败: ' + e.message);
  }
});

stopBtn?.addEventListener('click', async () => {
  try {
    if (chunkTimer) clearInterval(chunkTimer);
    if (vadTimer) clearInterval(vadTimer);
    // 发送尾段
    const last = await recorder.flush();
    if (ws && ws.readyState === WebSocket.OPEN && last.size > 0) {
      ws.send(await last.arrayBuffer());
    }
    setStatus('已停止录音');
    startBtn?.removeAttribute('disabled');
    stopBtn?.setAttribute('disabled', 'true');
  } catch (e) {
    setStatus('停止失败: ' + e.message);
  }
});

// 页面加载即建立 WS 连接
loadRoles();
wireRmsUI();


