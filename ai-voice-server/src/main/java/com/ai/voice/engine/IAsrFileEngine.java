package com.ai.voice.engine;

import java.io.File;

/**
 * 文件 ASR 引擎抽象接口（REST 上传场景）
 * 不同模型走不同 SDK API：Recognition.call / Transcription / QwenTranscription / MultiModal
 */
public interface IAsrFileEngine {

    /**
     * 识别音频文件中的语音
     *
     * @param audioFile  音频临时文件
     * @param format     音频格式（wav/pcm/mp3 等）
     * @param sampleRate 采样率
     * @return 识别文本，失败返回 null
     */
    String recognizeFile(File audioFile, String format, int sampleRate);
}
