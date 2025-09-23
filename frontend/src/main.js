import { WavRecorder } from './wav-recorder.js';

const startBtn = document.getElementById('start');
const stopBtn = document.getElementById('stop');
const statusEl = document.getElementById('status');
const player = document.getElementById('player');
const output = document.getElementById('output');
const rmsSlider = document.getElementById('rms');
const rmsVal = document.getElementById('rmsVal');

let recorder;
let ws;
let chunkTimer;
let vadTimer;
let speaking = false; // 播放中标志（半双工）
let silenceMs = 0;
let hasVoiceSinceLastFlush = false;
let rmsThreshold = 0.015;

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

function wireRmsUI() {
  if (!rmsSlider || !rmsVal) return;
  rmsSlider.value = String(rmsThreshold);
  rmsVal.textContent = Number(rmsSlider.value).toFixed(3);
  rmsSlider.addEventListener('input', () => {
    rmsThreshold = Number(rmsSlider.value);
    rmsVal.textContent = rmsThreshold.toFixed(3);
  });
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
  const wsUrl = `${wsProtocol}://localhost:8080/ws/voice`;
  ws = new WebSocket(wsUrl);
  ws.binaryType = 'arraybuffer';
  ws.onopen = async () => {
    setStatus('WS 已连接');
    startBtn?.removeAttribute('disabled');
    stopBtn?.setAttribute('disabled', 'true');
  };
  ws.onmessage = async (evt) => {
    if (typeof evt.data === 'string') {
      // 文本控制消息
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
connectWS();
wireRmsUI();


