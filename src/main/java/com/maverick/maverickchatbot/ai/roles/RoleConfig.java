package com.maverick.maverickchatbot.ai.roles;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@Data
public class RoleConfig {
    private String id;
    private String name;
    private String avatar; // URL or relative path
    private String personaPrompt;
    private String series; // 作品/世界观名，用于系统模板变量
    private String voice; // 角色默认音色标识（映射到 TTS 的 voice 或参考音频配置）

    // RAG 知识入库目录名；若为空则回退为 id 同名目录
    private String docsDir;

    private List<Example> examples; // few-shot 示例

    // 角色语音样本（用于语音克隆/音色标识），支持多个
    private List<VoiceSample> voiceSamples;

    @Data
    public static class Example {
        private String user;
        private String ai;
    }

    @Data
    public static class VoiceSample {
        // 对应 YAML 中的 spk_id / audio_path
        @JsonProperty("spk_id")
        private String spkId;

        @JsonProperty("audio_path")
        private String audioPath;
    }
}


