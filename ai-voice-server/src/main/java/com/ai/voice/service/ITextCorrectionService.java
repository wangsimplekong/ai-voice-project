package com.ai.voice.service;

public interface ITextCorrectionService {

    /**
     * 对 ASR 原始识别文本进行 LLM 纠错
     *
     * @param rawText ASR 原始输出文本
     * @return 纠错后的文本，纠错失败时返回原文
     */
    String correct(String rawText);
}
