package com.ai.voice.engine.local;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ai.voice.config.LocalVoiceProperties;
import com.ai.voice.engine.AsrSessionConfig;
import com.ai.voice.engine.IAsrEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * 本地自部署 ASR 实时引擎
 * 通过 WebSocket 连接 asr_realtime_server.py (VAD + Qwen3-ASR)
 * 协议与 DashScope 引擎对等，Java 侧零改动
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.voice.local.enabled", havingValue = "true")
public class LocalAsrEngine implements IAsrEngine {

    private final LocalVoiceProperties props;

    /**
     * 创建本地 ASR 实时会话，连接 asr_realtime_server.py WebSocket
     *
     * @param config   会话配置（采样率、语言等，当前由服务端默认）
     * @param listener 识别结果与事件回调
     * @return AsrSession，连接失败时返回 null
     */
    @Override
    public AsrSession createSession(AsrSessionConfig config, AsrEventListener listener) {
        // 1. 建立 WebSocket 连接
        try {
            URI uri = URI.create(props.getAsrRealtimeWsEndpoint());
            log.debug("连接本地 ASR 实时服务: {}", uri);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(props.getWsConnectTimeoutMs()))
                    .build();

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(uri, new WsListener(listener))
                    .join();

            // 2. 封装为 AsrSession 返回
            log.info("本地 ASR 会话已建立: {}", uri);
            return new LocalAsrSession(ws);
        } catch (Exception e) {
            log.error("连接本地 ASR 实时服务失败: {}", e.getMessage());
            listener.onError("连接 ASR 实时服务失败: " + e.getMessage());
            return null;
        }
    }

    // ---- WebSocket Listener: 解析服务端 JSON 推送 ----

    /**
     * WebSocket 监听器：接收服务端 JSON 并解析后回调
     */
    private static class WsListener implements WebSocket.Listener {
        private final AsrEventListener callback;
        private final StringBuilder textBuf = new StringBuilder();

        /**
         * @param callback 识别结果与事件回调
         */
        WsListener(AsrEventListener callback) { this.callback = callback; }

        /**
         * 收到服务端文本帧时累积，last 为 true 时拼成完整 JSON 并解析、回调 listener
         */
        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                handleMessage(textBuf.toString());
                textBuf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        /**
         * 连接关闭时通知 listener 会话结束
         */
        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            callback.onSessionEnd();
            return null;
        }

        /**
         * 发生错误时记录日志并回调 listener.onError
         */
        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("ASR WebSocket 错误: {}", error.getMessage());
            callback.onError("WebSocket 错误: " + error.getMessage());
        }

        /**
         * 解析服务端 JSON：text/sentenceEnd/final 或 error，并回调 listener
         *
         * @param json 服务端推送的 JSON 字符串
         */
        private void handleMessage(String json) {
            try {
                JSONObject obj = JSON.parseObject(json);
                if (obj.containsKey("error")) {
                    callback.onError(obj.getString("error"));
                    return;
                }

                String text = obj.getString("text");
                boolean sentenceEnd = obj.getBooleanValue("sentenceEnd");
                boolean isFinal = obj.getBooleanValue("final");

                if (isFinal) {
                    callback.onSessionEnd();
                } else if (text != null && !text.isEmpty()) {
                    callback.onText(text, sentenceEnd);
                }
            } catch (Exception e) {
                log.warn("解析 ASR 响应失败: {}", json);
            }
        }
    }

    /**
     * 本地 ASR 会话：向 WebSocket 发送音频与 end/close 指令
     */
    private static class LocalAsrSession implements AsrSession {
        private final WebSocket ws;
        private volatile boolean closed = false;

        /**
         * @param ws 已建立的 WebSocket 连接
         */
        LocalAsrSession(WebSocket ws) { this.ws = ws; }

        /**
         * 将 PCM 音频数据通过 WebSocket 二进制帧发送给服务端
         */
        @Override
        public void sendAudio(byte[] pcmBytes) {
            if (closed || pcmBytes == null || pcmBytes.length == 0) return;
            try {
                ws.sendBinary(ByteBuffer.wrap(pcmBytes), true);
            } catch (Exception e) {
                log.warn("发送音频数据失败: {}", e.getMessage());
            }
        }

        /**
         * 通知服务端音频发送结束，触发最终识别结果
         */
        @Override
        public void end() {
            if (closed) return;
            try {
                ws.sendText("{\"action\":\"end\"}", true);
            } catch (Exception e) {
                log.warn("发送 end 指令失败: {}", e.getMessage());
            }
        }

        /**
         * 关闭 WebSocket 连接并标记会话已关闭
         */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) { }
        }
    }
}
