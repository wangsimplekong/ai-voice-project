package com.ai.voice.engine.dashscope;

import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerParam;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerRealtime;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.engine.AsrSessionConfig;
import com.ai.voice.engine.IAsrEngine.AsrEventListener;
import com.ai.voice.engine.IAsrEngine.AsrSession;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

/**
 * gummy-realtime-v1（实时长语音识别+可选翻译）
 * 走 TranslationRecognizerRealtime SDK
 */
@Slf4j
class GummyRealtimeAsrAdapter {

    private GummyRealtimeAsrAdapter() {}

    /**
     * 创建 GummyRealtime 实时识别会话
     *
     * @param props     DashScope 配置
     * @param config    会话配置（采样率、语言）
     * @param listener  识别结果回调
     * @return AsrSession
     */
    static AsrSession createSession(DashScopeProperties props, AsrSessionConfig config, AsrEventListener listener) {
        int rate = config.getSampleRate() > 0 ? config.getSampleRate() : props.getAsr().getSampleRate();
        String lang = config.getLanguage() != null ? config.getLanguage() : props.getAsr().getLanguage();

        // 1. 构建识别参数（API Key、模型、格式、采样率、语言）
        TranslationRecognizerParam param = TranslationRecognizerParam.builder()
                .apiKey(props.getApiKey())
                .model(props.getAsr().getModel())
                .format("pcm")
                .sampleRate(rate)
                .sourceLanguage(lang)
                .transcriptionEnabled(true)
                .translationEnabled(false)
                .build();

        // 2. 创建 recognizer 并注册回调（onEvent / onComplete / onError）
        TranslationRecognizerRealtime recognizer = new TranslationRecognizerRealtime();
        try {
            recognizer.call(param, new ResultCallback<TranslationRecognizerResult>() {
                @Override public void onEvent(TranslationRecognizerResult r) {
                    if (r.getTranscriptionResult() != null) {
                        listener.onText(r.getTranscriptionResult().getText(), r.isSentenceEnd());
                    }
                }
                @Override public void onComplete() { listener.onSessionEnd(); }
                @Override public void onError(Exception e) { listener.onError(e.getMessage()); }
            });
        } catch (Exception e) {
            log.error("GummyRealtime 启动失败: {}", e.getMessage());
            listener.onError(e.getMessage());
            return null;
        }

        // 3. 封装 AsrSession 返回（sendAudio / end / close）
        return new AsrSession() {
            @Override public void sendAudio(byte[] pcm) {
                if (pcm != null && pcm.length > 0) recognizer.sendAudioFrame(ByteBuffer.wrap(pcm));
            }
            @Override public void end() { recognizer.stop(); }
            @Override public void close() {
                try { if (recognizer.getDuplexApi() != null) recognizer.getDuplexApi().close(1000, "bye"); }
                catch (Exception ignored) { }
            }
        };
    }
}
