package com.maverick.maverickchatbot.ai.tts;

/**
 * 文本转语音服务接口。
 */
public interface TtsService {

    /**
     * 将文本合成为音频数据（默认 WAV 16k）。
     * @param text 文本
     * @param voice 说话人或音色（可选）
     * @return 音频字节
     */
    byte[] synthesize(String text, String voice);
}


