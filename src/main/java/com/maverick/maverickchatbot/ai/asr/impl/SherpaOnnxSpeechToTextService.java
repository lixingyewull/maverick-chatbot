package com.maverick.maverickchatbot.ai.asr.impl;

import com.maverick.maverickchatbot.ai.asr.SpeechToTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Slf4j
public class SherpaOnnxSpeechToTextService implements SpeechToTextService {

    private final RestTemplate restTemplate;

    @Value("${asr.vendor:sherpa-onnx}")
    private String vendor;

    @Value("${asr.sherpa.base-url:http://localhost:8081}")
    private String sherpaBaseUrl;

    @Value("${asr.sherpa.endpoint:/v1/asr:transcribe}")
    private String sherpaEndpoint;

    @Override
    public String transcribe(MultipartFile audio) {
        if (!"sherpa-onnx".equalsIgnoreCase(vendor)) {
            throw new IllegalStateException("ASR vendor 非 sherpa-onnx，当前: " + vendor);
        }
        try {
            String url = sherpaBaseUrl + sherpaEndpoint;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new org.springframework.core.io.ByteArrayResource(audio.getBytes()) {
                @Override
                public String getFilename() {
                    return audio.getOriginalFilename() != null ? audio.getOriginalFilename() : "audio";
                }
            });

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("sherpa-onnx 转写失败，HTTP:" + response.getStatusCode());
            }

            String responseBody = response.getBody();
            // 期望 JSON: {"text":"..."}
            String text = parseTextFromJson(responseBody);
            if (text == null || text.isEmpty()) {
                throw new IllegalStateException("sherpa-onnx 返回内容无法解析: " + responseBody);
            }
            return text;
        } catch (Exception e) {
            log.error("调用 sherpa-onnx 失败", e);
            throw new RuntimeException("调用 sherpa-onnx 失败: " + e.getMessage(), e);
        }
    }

    private String parseTextFromJson(String json) {
        try {
            // 仅做极简解析，避免引入额外依赖；生产可换为 Jackson
            String key = "\"text\"";
            int idx = json.indexOf(key);
            if (idx < 0) return null;
            int colon = json.indexOf(":", idx);
            if (colon < 0) return null;
            int firstQuote = json.indexOf('"', colon + 1);
            if (firstQuote < 0) return null;
            int secondQuote = json.indexOf('"', firstQuote + 1);
            if (secondQuote < 0) return null;
            return new String(json.substring(firstQuote + 1, secondQuote).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}


