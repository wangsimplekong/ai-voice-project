package com.ai.voice.engine;

/**
 * 实时 ASR 引擎抽象接口（WebSocket 场景）
 */
public interface IAsrEngine {

    /**
     * 创建一个实时 ASR 会话
     *
     * @param config   会话配置（采样率、语言等）
     * @param listener 事件回调（识别文本、会话结束、错误）
     * @return ASR 会话实例，创建失败返回 null
     */
    AsrSession createSession(AsrSessionConfig config, AsrEventListener listener);

    /** 实时 ASR 会话，负责发送音频和控制生命周期 */
    interface AsrSession {
        /** 发送 PCM 音频数据 */
        void sendAudio(byte[] pcmBytes);
        /** 通知引擎音频发送结束，触发最终识别 */
        void end();
        /** 关闭会话并释放资源 */
        void close();
    }

    /** ASR 事件监听器 */
    interface AsrEventListener {
        /** 收到识别文本，sentenceEnd 为 true 表示一句话结束 */
        void onText(String text, boolean sentenceEnd);
        /** 整个会话结束 */
        void onSessionEnd();
        /** 识别过程出错 */
        void onError(String message);
    }
}
