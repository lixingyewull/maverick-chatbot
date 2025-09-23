package com.maverick.maverickchatbot.ai.tts.impl;

import com.maverick.maverickchatbot.ai.tts.TtsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class CosyVoiceTtsService implements TtsService {

    private final RestTemplate restTemplate;

    @Value("${tts.vendor:cosyvoice}")
    private String vendor;

    @Value("${tts.cosyvoice.base-url:http://localhost:8082}")
    private String baseUrl;

    @Value("${tts.cosyvoice.endpoint:/v1/tts-synthesize}")
    private String endpoint;

    @Value("${tts.default-voice:}")
    private String defaultVoice;

    @Override
    public byte[] synthesize(String text, String voice) {
        // 兼容多实现：py3-tts / edge-tts / cosyvoice（均接受相同 form 字段）
        if (!"cosyvoice".equalsIgnoreCase(vendor)
                && !"py3-tts".equalsIgnoreCase(vendor)
                && !"edge-tts".equalsIgnoreCase(vendor)) {
            throw new IllegalStateException("TTS vendor 不受支持，当前: " + vendor);
        }
        String url = baseUrl + endpoint;

        HttpHeaders headers = new HttpHeaders();
        // 强制 UTF-8，避免中文被错误按 ISO-8859-1 编码
        headers.setContentType(new MediaType("application", "x-www-form-urlencoded", StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("text", text);
        String finalVoice = (voice != null && !voice.isEmpty()) ? voice : (defaultVoice != null && !defaultVoice.isEmpty() ? defaultVoice : null);
        if (finalVoice != null) {
            form.add("voice", finalVoice);
        }

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(form, headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("cosyvoice TTS 失败，HTTP: " + response.getStatusCode());
        }
        return response.getBody();
    }
}


