package com.maverick.maverickchatbot.ai.tts.impl;

import com.maverick.maverickchatbot.ai.tts.TtsService;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 火山引擎TTS服务实现，直接集成WebSocket API
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "tts.vendor", havingValue = "volc-demo")
@RequiredArgsConstructor
public class VolcTtsDemo implements TtsService {

    @Value("${tts.volc.app-id}")
    private String appId;

    @Value("${tts.volc.access-token}")
    private String accessToken;

    @Value("${tts.volc.voice-type}")
    private String voiceType;
    
    @Value("${tts.volc.auth-header-prefix:Bearer; }")
    private String authHeaderPrefix;

    public static final String API_URL = "wss://openspeech.bytedance.com/api/v1/tts/ws_binary";

    @Override
    public byte[] synthesize(String text, String voice) {
        try {
            log.info("Starting TTS synthesis for text: '{}' with voice: '{}'", text, voice);
            TtsRequest ttsRequest = buildTtsRequest(text, voice);
            TtsWebsocketClient client = new TtsWebsocketClient(accessToken, authHeaderPrefix);
            byte[] result = client.submit(ttsRequest);
//            System.out.println(JSON.toJSONString(ttsRequest));
//            System.out.println(JSON.toJSONString(client));
            log.info("TTS synthesis completed, audio length: {} bytes", result.length);
            return result;
        } catch (Exception e) {
            log.error("TTS synthesis failed for text: '{}', voice: '{}', error: {}", text, voice, e.getMessage(), e);
            throw new RuntimeException("TTS synthesis failed", e);
        }
    }

    @Override
    public void synthesizeStream(String text, String voice, Consumer<byte[]> onChunk) throws Exception {
        // 不再支持流式合成，直接使用一次性合成并一次性返回
        log.info("Stream synthesis requested, but using one-time synthesis instead for text: '{}'", text);
        byte[] audio = synthesize(text, voice);
        if (audio != null && audio.length > 0 && onChunk != null) {
            onChunk.accept(audio);
        }
    }

    private TtsRequest buildTtsRequest(String text, String voice) {
        String finalVoice = voice != null ? voice : voiceType;
        log.info("Building TTS request - AppId: {}, VoiceType: {}, Text length: {}", appId, finalVoice, text.length());

        TtsRequest request = TtsRequest.builder()
            .app(TtsRequest.App.builder()
                .appid(appId)
                .cluster("volcano_icl")
                .build())
            .user(TtsRequest.User.builder()
                .uid("uid")
                .build())
            .audio(TtsRequest.Audio.builder()
                .encoding("mp3")
                .voiceType(finalVoice)
                .build())
            .request(TtsRequest.Request.builder()
                .reqID(UUID.randomUUID().toString())
                .operation("query")
                .text(text)
                .build())
            .build();
            
        log.debug("TTS request built successfully");
        return request;
    }

    // 标准TTS WebSocket客户端，用于一次性合成
    public static class TtsWebsocketClient extends WebSocketClient {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public TtsWebsocketClient(String accessToken, String authHeaderPrefix) {
            super(URI.create(API_URL), Collections.singletonMap("Authorization", authHeaderPrefix + accessToken));
            log.info("Creating TTS WebSocket client with auth header: '{}[REDACTED]'", authHeaderPrefix);
        }

        public byte[] submit(TtsRequest ttsRequest) throws InterruptedException {
            String json = JSON.toJSONString(ttsRequest);
            log.info("TTS request: {}", json);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            byte[] header = {0x11, 0x10, 0x10, 0x00};
            ByteBuffer requestByte = ByteBuffer.allocate(8 + jsonBytes.length);
            requestByte.put(header).putInt(jsonBytes.length).put(jsonBytes);

            log.info("Connecting to TTS WebSocket: {}", API_URL);
            this.connectBlocking();
            synchronized (this) {
                this.send(requestByte.array());
                log.info("TTS request sent, waiting for response...");
                wait();
                byte[] result = this.buffer.toByteArray();
                log.info("Received TTS response, size: {} bytes", result.length);
                return result;
            }
        }

        @Override
        @SuppressWarnings("unused")
        public void onMessage(ByteBuffer bytes) {
            int protocolVersion = (bytes.get(0) & 0xff) >> 4;
            int headerSize = bytes.get(0) & 0x0f;
            int messageType = (bytes.get(1) & 0xff) >> 4;
            int messageTypeSpecificFlags = bytes.get(1) & 0x0f;
            int serializationMethod = (bytes.get(2) & 0xff) >> 4;
            int messageCompression = bytes.get(2) & 0x0f;
            int reserved = bytes.get(3) & 0xff;
            bytes.position(headerSize * 4);
            byte[] fourByte = new byte[4];
            if (messageType == 11) {
                // Audio-only server response
                log.debug("Received audio-only response");
                if (messageTypeSpecificFlags == 0) {
                    // Ack without audio data
                } else {
                    bytes.get(fourByte, 0, 4);
                    int sequenceNumber = new BigInteger(fourByte).intValue();
                    bytes.get(fourByte, 0, 4);
                    int payloadSize = new BigInteger(fourByte).intValue();
                    byte[] payload = new byte[payloadSize];
                    bytes.get(payload, 0, payloadSize);
                    try {
                        this.buffer.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write audio payload", e);
                    }
                    if (sequenceNumber < 0) {
                        // Received the last segment
                        this.close(CloseFrame.NORMAL, "Received all audio data");
                    }
                }
            } else if (messageType == 15) {
                // Error message from server
                bytes.get(fourByte, 0, 4);
                int code = new BigInteger(fourByte).intValue();
                bytes.get(fourByte, 0, 4);
                int messageSize = new BigInteger(fourByte).intValue();
                byte[] messageBytes = new byte[messageSize];
                bytes.get(messageBytes, 0, messageSize);
                String message = new String(messageBytes, StandardCharsets.UTF_8);
                throw new TtsException(code, message);
            } else {
                log.warn("Received unknown response message type: {}", messageType);
            }
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            log.debug("TTS WebSocket connection opened");
        }

        @Override
        public void onMessage(String message) {
            log.debug("Received text message: {}", message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            log.debug("Connection closed by {}, Code: {}, Reason: {}", 
                (remote ? "remote" : "us"), code, reason);
            synchronized (this) {
                notify();
            }
        }

        @Override
        public void onError(Exception e) {
            log.error("WebSocket error", e);
            close(CloseFrame.NORMAL, e.toString());
        }
    }

    // 火山引擎TTS异常类
    @Getter
    public static class TtsException extends RuntimeException {
        private final int code;
        private final String message;

        public TtsException(int code, String message) {
            super(buildErrorMessage(code, message));
            this.code = code;
            this.message = message;
        }

        private static String buildErrorMessage(int code, String message) {
            String baseMessage = "TTS Error: code=" + code + ", message=" + message;
            if (code == 403) {
                return baseMessage + "\n🔧 403错误解决方案：" +
                       "\n1. 检查Access Token是否有效: 登录火山引擎控制台获取最新token" +
                       "\n2. 验证App ID是否正确: 确保app-id与控制台中的应用ID一致" +
                       "\n3. 音色权限问题: 当前音色可能需要特殊权限或不支持" +
                       "\n   建议使用基础音色如: BV407_streaming, BV001_streaming" +
                       "\n4. 账户状态: 检查账户余额和服务状态" +
                       "\n5. 认证头格式: 当前使用格式，请确认是否正确";
            }
            return baseMessage;
        }
    }

    // 火山引擎TTS请求结构
    @Data
    @Builder
    public static class TtsRequest {
        @JSONField(name = "app")
        private App app;
        @JSONField(name = "user")
        private User user;
        @JSONField(name = "audio")
        private Audio audio;
        @JSONField(name = "request")
        private Request request;

        @Data
        @Builder
        public static class App {
            @JSONField(name = "appid")
            private String appid;
            @JSONField(name = "token")
            private String token;
            @JSONField(name = "cluster")
            private String cluster;
        }

        @Data
        @Builder
        public static class User {
            @JSONField(name = "uid")
            private String uid;
        }

        @Data
        @Builder
        public static class Audio {
            @JSONField(name = "voice_type")
            private String voiceType;
            @JSONField(name = "voice")
            private String voice;
            @JSONField(name = "encoding")
            private String encoding;
            @JSONField(name = "speed_ratio")
            private Double speedRatio;
            @JSONField(name = "volume_ratio")
            private Double volumeRatio;
            @JSONField(name = "pitch_ratio")
            private Double pitchRatio;
            @JSONField(name = "emotion")
            private String emotion;
            @JSONField(name = "language")
            private String language;
        }

        @Data
        @Builder
        public static class Request {
            @JSONField(name = "reqid")
            private String reqID;
            @JSONField(name = "text")
            private String text;
            @JSONField(name = "text_type")
            private String textType;
            @JSONField(name = "operation")
            private String operation;
            @JSONField(name = "silence_duration")
            private Integer silenceDuration;
        }
    }
}
