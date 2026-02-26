package com.ai.voice.engine.dashscope;

import com.alibaba.dashscope.audio.omni.*;
import com.google.gson.JsonObject;
import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.engine.AsrSessionConfig;
import com.ai.voice.engine.IAsrEngine.AsrEventListener;
import com.ai.voice.engine.IAsrEngine.AsrSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.Collections;

/**
 * qwen3-asr-flash-realtime / qwen3-asr-turbo-realtime / qwen-omni-turbo
 * 走 wss://…/api-ws/v1/realtime
 */
@Slf4j
class OmniRealtimeAsrAdapter {

    private OmniRealtimeAsrAdapter() {}

    /**
     * 创建 OmniRealtime 实时识别会话（qwen3-asr-* / qwen-omni-*）
     *
     * @param props     DashScope 配置
     * @param config    会话配置（采样率、语言）
     * @param listener  识别结果回调
     * @return AsrSession，连接失败返回 null
     */
    static AsrSession createSession(DashScopeProperties props, AsrSessionConfig config, AsrEventListener listener) {
        // 1. 构建连接参数与转写参数（模型、URL、API Key、语言、采样率）
        OmniRealtimeParam param = OmniRealtimeParam.builder()
                .model(props.getAsr().getModel())
                .url(props.getRealtimeWsUrl())
                .apikey(props.getApiKey())
                .build();

        OmniRealtimeTranscriptionParam tp = new OmniRealtimeTranscriptionParam();
        tp.setLanguage(config.getLanguage() != null ? config.getLanguage() : props.getAsr().getLanguage());
        tp.setInputSampleRate(config.getSampleRate() > 0 ? config.getSampleRate() : props.getAsr().getSampleRate());
        tp.setInputAudioFormat("pcm");

        OmniRealtimeConfig omniConfig = OmniRealtimeConfig.builder()
                .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                .enableTurnDetection(true)
                .turnDetectionType("server_vad")
                .turnDetectionThreshold(0.0f)
                .turnDetectionSilenceDurationMs(400)
                .transcriptionConfig(tp)
                .build();

        // 2. 构建 SDK 回调（onOpen / onEvent / onClose）
        OmniRealtimeCallback sdkCb = new OmniRealtimeCallback() {
            @Override public void onOpen() { }
            @Override public void onEvent(JsonObject msg) { dispatch(msg, listener); }
            @Override public void onClose(int code, String reason) { listener.onSessionEnd(); }
        };

        // 3. 建立连接并更新会话配置
        OmniRealtimeConversation conv = new OmniRealtimeConversation(param, sdkCb);
        try {
            conv.connect();
            conv.updateSession(omniConfig);
        } catch (Exception e) {
            log.error("OmniRealtime 连接失败: {}", e.getMessage());
            listener.onError(e.getMessage());
            return null;
        }

        // 4. 封装 AsrSession 返回（sendAudio / end / close）
        return new AsrSession() {
            @Override public void sendAudio(byte[] pcm) {
                if (pcm != null && pcm.length > 0) conv.appendAudio(Base64.getEncoder().encodeToString(pcm));
            }
            @Override public void end() {
                try { conv.endSession(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            @Override public void close() { conv.close(); }
        };
    }

    /**
     * 解析 Omni 服务端推送的 JSON，回调 onText / onSessionEnd / onError
     *
     * @param msg      服务端推送的 JSON 消息
     * @param listener 识别结果回调
     */
    private static void dispatch(JsonObject msg, AsrEventListener listener) {
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        if ("session.finished".equals(type)) { listener.onSessionEnd(); return; }
        if ("error".equals(type)) { listener.onError(msg.has("error") ? msg.get("error").toString() : type); return; }
        if (type.contains("input_audio_transcription")) {
            String text = extractText(msg);
            if (StringUtils.hasText(text)) listener.onText(text, type.endsWith(".completed"));
        }
    }

    /**
     * 从 Omni 消息中提取识别文本
     *
     * @param msg 服务端消息
     * @return 识别文本，无则返回 null
     */
    private static String extractText(JsonObject msg) {
        try {
            if (msg.has("transcript")) return msg.get("transcript").getAsString();
            if (msg.has("text")) return msg.get("text").getAsString();
            if (!msg.has("conversation")) return null;
            JsonObject item = msg.getAsJsonObject("conversation").getAsJsonObject("item");
            if (item == null) return null;
            JsonObject trans = item.getAsJsonObject("input_audio_transcription");
            if (trans == null) return null;
            if (trans.has("text")) return trans.get("text").getAsString();
            if (trans.has("transcript")) return trans.get("transcript").getAsString();
        } catch (Exception ignored) { }
        return null;
    }
}
