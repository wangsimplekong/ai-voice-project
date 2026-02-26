package com.ai.voice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自部署语音服务配置（Qwen3-ASR / Qwen3-TTS on GPU Server）
 * 设置 enabled=true 后自动切换到本地引擎，DashScope 引擎不再加载
 */
@Data
@ConfigurationProperties(prefix = "ai.voice.local")
public class LocalVoiceProperties {

    /**
     * 是否启用本地自部署引擎（启用后 DashScope 引擎自动禁用）
     */
    private boolean enabled = false;
 
    /**
     * qwen-asr-serve HTTP 地址 (文件识别 + 整段识别)
     */
    private String asrUrl = "http://localhost:8003";

    /**
     * asr_realtime_server.py WebSocket 地址 (实时流式 ASR)
     */
    private String asrRealtimeUrl = "ws://localhost:8005";

    /**
     * tts_server.py HTTP 地址 (语音合成)
     */
    private String ttsUrl = "http://localhost:8004";

    /**
     * TTS 默认说话人（留空使用模型默认）
     * Qwen3-TTS-CustomVoice 可用说话人:
     * aiden, dylan, eric, ono_anna, ryan, serena, sohee, uncle_fu, vivian
     */
    private String ttsVoice = "vivian";

    /**
     * TTS 默认语言
     */
    private String ttsLanguage = "Auto";

    /**
     * TTS 默认输出格式: wav / mp3
     */
    private String ttsFormat = "mp3";

    /**
     * HTTP 请求超时 (毫秒)
     */
    private int timeoutMs = 30000;

    /**
     * WebSocket 连接超时 (毫秒)
     */
    private int wsConnectTimeoutMs = 5000;

    public String getAsrRealtimeWsEndpoint() {
        String base = asrRealtimeUrl.endsWith("/") ? asrRealtimeUrl : asrRealtimeUrl + "/";
        return base + "ws/asr";
    }
}
