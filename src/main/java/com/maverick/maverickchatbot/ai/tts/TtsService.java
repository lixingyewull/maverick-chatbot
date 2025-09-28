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

    /**
     * 流式合成：文本一次性送入，服务端分片返回音频。
     * 默认实现：调用 {@link #synthesize(String, String)} 并一次性回传。
     */
    default void synthesizeStream(String text, String voice, java.util.function.Consumer<byte[]> onChunk) throws Exception {
        byte[] all = synthesize(text, voice);
        if (all != null && all.length > 0) onChunk.accept(all);
    }
}


