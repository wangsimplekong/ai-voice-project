package com.ai.voice.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ai.voice.engine.AsrSessionConfig;
import com.ai.voice.engine.IAsrEngine;
import com.ai.voice.service.ITextCorrectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时 ASR WebSocket（统一输出格式，与底层引擎解耦）
 *
 * 客户端发送：{"audio":"base64 PCM"} 或二进制 PCM | {"action":"end"}
 * 服务端推送：{"text":"..","sentenceEnd":false,"final":false} | {"text":"..","sentenceEnd":true,"final":true} | {"error":".."}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrRealtimeHandler extends AbstractWebSocketHandler {

    private final IAsrEngine asrEngine;
    private final ITextCorrectionService correctionService;
    private final ConcurrentHashMap<String, Ctx> ctxMap = new ConcurrentHashMap<>();

    /**
     * 连接建立时创建 ASR 会话并注册 listener，失败则推送错误并关闭连接
     *
     * @param ws WebSocket 会话
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        Ctx ctx = new Ctx();
        IAsrEngine.AsrEventListener listener = new IAsrEngine.AsrEventListener() {
            @Override public void onText(String text, boolean sentenceEnd) {
                push(ws, text, sentenceEnd, false);
                if (sentenceEnd && StringUtils.hasText(text)) ctx.append(text.trim());
            }
            @Override public void onSessionEnd() { finishAndCorrect(ws, ctx); }
            @Override public void onError(String msg) { pushError(ws, msg); }
        };
        IAsrEngine.AsrSession session = asrEngine.createSession(AsrSessionConfig.builder().build(), listener);
        if (session == null) {
            pushError(ws, "创建 ASR 会话失败");
            ws.close(CloseStatus.BAD_DATA);
            return;
        }
        ctx.session = session;
        ctxMap.put(ws.getId(), ctx);
    }

    /**
     * 处理文本消息：action=end 结束识别，audio=base64 发送 PCM
     *
     * @param ws      WebSocket 会话
     * @param message 文本消息
     */
    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        Ctx ctx = ctxMap.get(ws.getId());
        if (ctx == null || ctx.session == null) return;
        try {
            JSONObject obj = JSON.parseObject(message.getPayload());
            if (obj != null && "end".equals(obj.getString("action"))) { ctx.session.end(); return; }
            String audio = obj != null ? obj.getString("audio") : null;
            if (audio != null && !audio.isEmpty()) ctx.session.sendAudio(Base64.getDecoder().decode(audio));
        } catch (Exception e) {
            pushError(ws, e.getMessage());
        }
    }

    /**
     * 处理二进制消息：直接作为 PCM 发送给 ASR 引擎
     *
     * @param ws      WebSocket 会话
     * @param message 二进制消息
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        Ctx ctx = ctxMap.get(ws.getId());
        if (ctx != null && ctx.session != null && message.getPayload().hasArray())
            ctx.session.sendAudio(message.getPayload().array());
    }

    /**
     * 连接关闭时结束并关闭 ASR 会话，从 ctxMap 移除
     *
     * @param ws     WebSocket 会话
     * @param status 关闭状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Ctx ctx = ctxMap.remove(ws.getId());
        if (ctx != null && ctx.session != null) {
            try { ctx.session.end(); } finally { ctx.session.close(); }
        }
    }

    /**
     * 会话结束时对累积文本做 LLM 纠错并推送 final 结果，关闭会话
     *
     * @param ws  WebSocket 会话
     * @param ctx 当前连接上下文
     */
    private void finishAndCorrect(WebSocketSession ws, Ctx ctx) {
        String raw = ctx.getFull();
        if (!StringUtils.hasText(raw)) { push(ws, "", true, true); return; }
        try {
            push(ws, correctionService.correct(raw), true, true);
        } catch (Exception e) {
            push(ws, raw, true, true);
        } finally {
            ctxMap.remove(ws.getId());
            if (ctx.session != null) ctx.session.close();
        }
    }

    /**
     * 向客户端推送识别结果 JSON（text / sentenceEnd / final）
     *
     * @param ws       WebSocket 会话
     * @param text     识别文本
     * @param sentEnd  是否句子结束
     * @param isFinal  是否最终结果
     */
    private static void push(WebSocketSession ws, String text, boolean sentEnd, boolean isFinal) {
        try {
            if (!ws.isOpen()) return;
            JSONObject o = new JSONObject();
            o.put("text", text != null ? text : "");
            o.put("sentenceEnd", sentEnd);
            o.put("final", isFinal);
            ws.sendMessage(new TextMessage(o.toJSONString()));
        } catch (IOException ignored) { }
    }

    /**
     * 向客户端推送错误 JSON
     *
     * @param ws  WebSocket 会话
     * @param msg 错误信息
     */
    private static void pushError(WebSocketSession ws, String msg) {
        try {
            if (!ws.isOpen()) return;
            JSONObject o = new JSONObject();
            o.put("error", msg);
            ws.sendMessage(new TextMessage(o.toJSONString()));
        } catch (IOException ignored) { }
    }

    /**
     * 单连接上下文：持有 AsrSession 与按句累积的文本，用于最终纠错
     */
    private static class Ctx {
        /**
         * 当前 ASR 会话
         */
        IAsrEngine.AsrSession session;
        private final StringBuilder sb = new StringBuilder();

        /**
         * 追加一句识别文本（句间加空格）
         *
         * @param t 单句文本
         */
        synchronized void append(String t) { if (sb.length() > 0) sb.append(" "); sb.append(t); }

        /**
         * 获取当前累积的完整文本
         *
         * @return 拼接后的全文
         */
        synchronized String getFull() { return sb.toString(); }
    }
}
