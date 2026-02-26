package com.ai.voice.config;

import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 语音引擎统一配置
 * 根据 local.enabled 选择本地自部署或 DashScope 云 API，并初始化对应端点
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({DashScopeProperties.class, LocalVoiceProperties.class, TextCorrectionProperties.class})
public class VoiceConfig {

    private final DashScopeProperties dashScopeProps;
    private final LocalVoiceProperties localProps;

    /**
     * 启动时根据配置打印当前模式，若为 DashScope 则设置 SDK 全局 API 地址
     */
    @PostConstruct
    public void init() {
        if (localProps.isEnabled()) {
            log.info("语音引擎: 本地自部署模式");
            log.info("  ASR HTTP:      {}", localProps.getAsrUrl());
            log.info("  ASR WebSocket: {}", localProps.getAsrRealtimeWsEndpoint());
            log.info("  TTS HTTP:      {}", localProps.getTtsUrl());
        } else {
            log.info("语音引擎: DashScope 云 API 模式");
            Constants.baseWebsocketApiUrl = dashScopeProps.getInferenceWsUrl();
            Constants.baseHttpApiUrl = dashScopeProps.getHttpApiUrl();
        }
    }
}
