package com.maverick.maverickchatbot.ai.asr;

import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转文字服务接口。
 */
public interface SpeechToTextService {

    /**
     * 将上传的音频进行转写，返回文本。
     * @param audio 音频文件（常见类型：wav/mp3/m4a等）
     * @return 转写文本
     */
    String transcribe(MultipartFile audio);
}


