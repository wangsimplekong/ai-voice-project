package com.ai.voice.config;

import com.ai.voice.websocket.AsrRealtimeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * 注册实时 ASR 的 WebSocket 端点 /api/voice/asr/realtime
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrRealtimeHandler asrRealtimeHandler;

    /**
     * 注册实时 ASR 处理器及路径
     *
     * @param registry WebSocket 处理器注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrRealtimeHandler, "/api/voice/asr/realtime")
                .setAllowedOrigins("*");
    }
}
