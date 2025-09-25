package com.maverick.maverickchatbot.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@Profile("!ingest")
public class WebSocketContainerConfig {

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);          // 64KB 文本
        container.setMaxBinaryMessageBufferSize(5 * 1024 * 1024);  // 5MB 二进制
        container.setAsyncSendTimeout(60_000L);                    // 发送超时 60s
        container.setMaxSessionIdleTimeout(300_000L);              // 空闲 5 分钟
        return container;
    }
}


