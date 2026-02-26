package com.ai.voice.engine;

/**
 * TTS 引擎抽象接口
 */
public interface ITtsEngine {

    /**
     * 文本转语音合成
     *
     * @param text  待合成文本
     * @param voice 音色标识（可为空，使用引擎默认）
     * @return 音频字节数组，合成失败返回 null
     */
    byte[] synthesize(String text, String voice);
}
