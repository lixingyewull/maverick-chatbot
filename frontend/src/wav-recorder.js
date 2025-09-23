export class WavRecorder {
  constructor({ sampleRate = 16000, numChannels = 1 } = {}) {
    this.sampleRate = sampleRate;
    this.numChannels = numChannels;
    this.audioContext = null;
    this.mediaStream = null;
    this.processor = null;
    this.recording = false;
    this.buffers = Array.from({ length: numChannels }, () => []);
    this.length = 0;
    this.onLevel = null; // (rms, frameCount) => void
  }

  async start() {
    this.mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    this.audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: this.sampleRate });
    const source = this.audioContext.createMediaStreamSource(this.mediaStream);
    const processor = this.audioContext.createScriptProcessor(4096, source.channelCount, this.numChannels);
    processor.onaudioprocess = (e) => {
      // RMS level callback（始终计算，便于 VAD）
      try {
        const ch0 = e.inputBuffer.getChannelData(0);
        let sumSq = 0;
        for (let i = 0; i < ch0.length; i++) { const v = ch0[i]; sumSq += v * v; }
        const rms = Math.sqrt(sumSq / ch0.length);
        if (typeof this.onLevel === 'function') this.onLevel(rms, ch0.length);
      } catch {}
      // 录音缓存（仅在 recording=true 时累计）
      if (!this.recording) return;
      for (let ch = 0; ch < this.numChannels; ch++) {
        const input = e.inputBuffer.getChannelData(ch);
        this.buffers[ch].push(new Float32Array(input));
      }
      this.length += e.inputBuffer.length;
    };
    source.connect(processor);
    processor.connect(this.audioContext.destination);
    this.processor = processor;
    this.recording = true;
  }

  async stop() {
    this.recording = false;
    if (this.processor) this.processor.disconnect();
    if (this.mediaStream) this.mediaStream.getTracks().forEach(t => t.stop());
    const interleaved = this._mergeBuffers();
    const wav = this._encodeWav(interleaved, this.sampleRate, this.numChannels);
    return new Blob([wav], { type: 'audio/wav' });
  }

  async flush() {
    // 仅将当前缓存编码为 WAV，不停止音频流，便于分段发送
    const interleaved = this._mergeBuffers();
    // 重置缓存以便下一段
    this.buffers = Array.from({ length: this.numChannels }, () => []);
    this.length = 0;
    const wav = this._encodeWav(interleaved, this.sampleRate, this.numChannels);
    return new Blob([wav], { type: 'audio/wav' });
  }

  pause() { this.recording = false; }
  resume() { this.recording = true; }

  _mergeBuffers() {
    if (this.numChannels === 1) {
      const data = new Float32Array(this.length);
      let offset = 0;
      for (const buf of this.buffers[0]) { data.set(buf, offset); offset += buf.length; }
      return data;
    }
    const chData = this.buffers.map(ch => {
      const data = new Float32Array(this.length);
      let offset = 0;
      for (const buf of ch) { data.set(buf, offset); offset += buf.length; }
      return data;
    });
    const interleaved = new Float32Array(this.length * this.numChannels);
    let idx = 0;
    for (let i = 0; i < this.length; i++) {
      for (let ch = 0; ch < this.numChannels; ch++) interleaved[idx++] = chData[ch][i];
    }
    return interleaved;
  }

  _encodeWav(samples, sampleRate, numChannels) {
    const bytesPerSample = 2; // 16-bit PCM
    const blockAlign = numChannels * bytesPerSample;
    const byteRate = sampleRate * blockAlign;
    const dataSize = samples.length * bytesPerSample;

    const buffer = new ArrayBuffer(44 + dataSize);
    const view = new DataView(buffer);

    let offset = 0;
    function writeString(s) { for (let i = 0; i < s.length; i++) view.setUint8(offset++, s.charCodeAt(i)); }
    function writeUint32(v) { view.setUint32(offset, v, true); offset += 4; }
    function writeUint16(v) { view.setUint16(offset, v, true); offset += 2; }

    // RIFF header
    writeString('RIFF');
    writeUint32(36 + dataSize);
    writeString('WAVE');

    // fmt chunk
    writeString('fmt ');
    writeUint32(16); // PCM
    writeUint16(1); // PCM
    writeUint16(numChannels);
    writeUint32(sampleRate);
    writeUint32(byteRate);
    writeUint16(blockAlign);
    writeUint16(16); // bits

    // data chunk
    writeString('data');
    writeUint32(dataSize);

    // PCM data
    const volume = 0x7fff;
    for (let i = 0; i < samples.length; i++) {
      const s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(offset, s * volume, true);
      offset += 2;
    }

    return buffer;
  }
}


