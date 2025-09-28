package com.maverick.maverickchatbot.tools;

import com.maverick.maverickchatbot.ai.tts.impl.VolcTtsDemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * TTS调试工具，用于快速测试火山引擎TTS配置
 * 使用方法：在启动参数中加入 --debug.tts.enabled=true
 */
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(name = "debug.tts.enabled", havingValue = "true")
public class TtsDebugger implements CommandLineRunner {

    @Value("${tts.volc.app-id}")
    private String appId;

    @Value("${tts.volc.access-token}")
    private String accessToken;

    @Value("${tts.volc.voice-type}")
    private String voiceType;

    @Value("${tts.volc.auth-header-prefix}")
    private String authHeaderPrefix;

    @Override
    public void run(String... args) throws Exception {
        log.info("🔧 开始TTS配置调试...");
        log.info("App ID: {}", appId);
        log.info("Access Token: {}****{}", 
            accessToken.length() > 8 ? accessToken.substring(0, 4) : "****", 
            accessToken.length() > 8 ? accessToken.substring(accessToken.length()-4) : "****");
        log.info("Voice Type: {}", voiceType);
        log.info("Auth Header Prefix: '{}'", authHeaderPrefix);

        // 测试不同的音色
        String[] testVoices = {
            "BV407_streaming",
            "BV001_streaming", 
            "BV002_streaming",
            voiceType
        };

        String testText = "你好，这是火山引擎TTS测试";
        
        for (String voice : testVoices) {
            try {
                log.info("🎤 测试音色: {}", voice);
                
                VolcTtsDemo.TtsRequest request = VolcTtsDemo.TtsRequest.builder()
                    .app(VolcTtsDemo.TtsRequest.App.builder()
                        .appid(appId)
                        .cluster("volcano_tts")
                        .build())
                    .user(VolcTtsDemo.TtsRequest.User.builder()
                        .uid("debug_test")
                        .build())
                    .audio(VolcTtsDemo.TtsRequest.Audio.builder()
                        .encoding("mp3")
                        .voiceType(voice)
                        .build())
                    .request(VolcTtsDemo.TtsRequest.Request.builder()
                        .reqID("debug-" + System.currentTimeMillis())
                        .operation("query")
                        .text(testText)
                        .build())
                    .build();

                log.info("请求JSON: {}", com.alibaba.fastjson.JSON.toJSONString(request));
                
                // 这里只构建请求，不实际发送，避免过多测试调用
                log.info("✅ 音色 {} 请求构建成功", voice);
                
            } catch (Exception e) {
                log.error("❌ 音色 {} 测试失败: {}", voice, e.getMessage());
            }
        }

        log.info("🔧 TTS配置调试完成");
        log.info("💡 建议：");
        log.info("1. 如果使用自定义音色出现403错误，请先尝试基础音色");
        log.info("2. 检查火山引擎控制台中的应用权限设置");
        log.info("3. 确认账户余额充足");
    }
}
