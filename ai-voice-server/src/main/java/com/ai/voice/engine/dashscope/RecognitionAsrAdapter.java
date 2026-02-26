package com.ai.voice.engine.dashscope;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.ai.voice.config.DashScopeProperties;
import com.ai.voice.engine.AsrSessionConfig;
import com.ai.voice.engine.IAsrEngine.AsrEventListener;
import com.ai.voice.engine.IAsrEngine.AsrSession;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

/**
 * fun-asr-realtime / paraformer-realtime-v2 / paraformer-realtime-8k-v2 / sensevoice-small
 * 走 Recognition SDK，wss://…/api-ws/v1/inference
 */
@Slf4j
class RecognitionAsrAdapter {

    private RecognitionAsrAdapter() {}

    /**
     * 创建 Recognition 实时识别会话（fun-asr-realtime / paraformer-realtime 等）
     *
     * @param props     DashScope 配置
     * @param config    会话配置（采样率）
     * @param listener  识别结果回调
     * @return AsrSession
     */
    static AsrSession createSession(DashScopeProperties props, AsrSessionConfig config, AsrEventListener listener) {
        int rate = config.getSampleRate() > 0 ? config.getSampleRate() : props.getAsr().getSampleRate();

        // 1. 构建识别参数（模型、API Key、格式、采样率）
        RecognitionParam param = RecognitionParam.builder()
                .model(props.getAsr().getModel())
                .apiKey(props.getApiKey())
                .format("pcm")
                .sampleRate(rate)
                .build();

        // 2. 创建 recognizer 并注册回调（onEvent / onComplete / onError）
        Recognition recognizer = new Recognition();
        try {
            recognizer.call(param, new ResultCallback<RecognitionResult>() {
                @Override public void onEvent(RecognitionResult r) {
                    if (r.getSentence() != null) listener.onText(r.getSentence().getText(), r.isSentenceEnd());
                }
                @Override public void onComplete() { listener.onSessionEnd(); }
                @Override public void onError(Exception e) { listener.onError(e.getMessage()); }
            });
        } catch (Exception e) {
            log.error("Recognition 启动失败: {}", e.getMessage());
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
