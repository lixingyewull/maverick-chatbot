package com.maverick.maverickchatbot;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import lombok.Builder;
import lombok.Data;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

public class TestTtsDemo {
    public static final String API_URL = "wss://openspeech.bytedance.com/api/v1/tts/ws_binary";

    public static void main(String[] args) throws Exception {
        // 你的 appid 和 access_token
        String appid = "9838143164";
        String accessToken = "GbhGf-4QEcxJ2D88FfPN8g8VdWeXoMo4";

        TtsRequest ttsRequest = TtsRequest.builder()
            .app(TtsRequest.App.builder()
                .appid(appid)
                .cluster("volcano_tts")
                .build())
            .user(TtsRequest.User.builder()
                .uid("uid")
                .build())
            .audio(TtsRequest.Audio.builder()
                .encoding("mp3")
                .voiceType("S_VUvKX6GF1")
                .build())
            .request(TtsRequest.Request.builder()
                .reqID(UUID.randomUUID().toString())
                .operation("query")
                .text("字节跳动语音合成")
                .build())
            .build();
        TtsWebsocketClient ttsWebsocketClient = new TtsWebsocketClient(accessToken);
        byte[] audio = ttsWebsocketClient.submit(ttsRequest);
        FileOutputStream fos = new FileOutputStream("test.mp3");
        fos.write(audio);
        fos.close();
        System.out.println("TTS done. Generated test.mp3 with " + audio.length + " bytes");
    }

    public static class TtsWebsocketClient extends WebSocketClient {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public TtsWebsocketClient(String accessToken) {
            super(URI.create(API_URL), Collections.singletonMap("Authorization", "Bearer; " + accessToken));
        }

        public byte[] submit(TtsRequest ttsRequest) throws InterruptedException {
            String json = JSON.toJSONString(ttsRequest);
            System.out.println("request: " + json);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            byte[] header = {0x11, 0x10, 0x10, 0x00};
            ByteBuffer requestByte = ByteBuffer.allocate(8 + jsonBytes.length);
            requestByte.put(header).putInt(jsonBytes.length).put(jsonBytes);

            this.connectBlocking();
            synchronized (this) {
                this.send(requestByte.array());
                wait();
                return this.buffer.toByteArray();
            }
        }

        @Override
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
                        throw new RuntimeException(e);
                    }
                    if (sequenceNumber < 0) {
                        this.close(CloseFrame.NORMAL, "received all audio data.");
                    }
                }
            } else if (messageType == 15) {
                bytes.get(fourByte, 0, 4);
                int code = new BigInteger(fourByte).intValue();
                bytes.get(fourByte, 0, 4);
                int messageSize = new BigInteger(fourByte).intValue();
                byte[] messageBytes = new byte[messageSize];
                bytes.get(messageBytes, 0, messageSize);
                String message = new String(messageBytes, StandardCharsets.UTF_8);
                System.err.println("TTS Error: code=" + code + ", message=" + message);
                throw new RuntimeException("TTS Error: code=" + code + ", message=" + message);
            }
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            System.out.println("opened connection");
        }

        @Override
        public void onMessage(String message) {
            System.out.println("received message: " + message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            System.out.println("Connection closed by " + (remote ? "remote" : "us") + ", Code: " + code + ", Reason: " + reason);
            synchronized (this) {
                notify();
            }
        }

        @Override
        public void onError(Exception e) {
            close(CloseFrame.NORMAL, e.toString());
        }
    }

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
