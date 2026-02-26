package com.ai.voice.service.impl;

import com.ai.voice.engine.IAsrFileEngine;
import com.ai.voice.service.IAsrFileService;
import com.ai.voice.service.ITextCorrectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.*;

/**
 * 文件 ASR 服务实现：临时文件 + 引擎识别 + LLM 纠错
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsrFileServiceImpl implements IAsrFileService {

    private final IAsrFileEngine asrFileEngine;
    private final ITextCorrectionService correctionService;

    /**
     * 识别音频文件：保存临时文件、解析格式与采样率、调用引擎、LLM 纠错
     *
     * @param audioBytes  音频字节数组
     * @param contentType MIME 类型
     * @return 纠错后文本，失败返回 null
     */
    @Override
    public String recognizeFromFile(byte[] audioBytes, String contentType) {
        if (audioBytes == null || audioBytes.length == 0) return null;

        // 1. 保存为临时文件
        File tempFile = null;
        try {
            tempFile = saveTempFile(audioBytes);
            if (tempFile == null) return null;

            // 2. 解析格式和采样率
            String format = guessFormat(contentType, tempFile);
            int sampleRate = detectSampleRate(tempFile, format);

            // 3. 调用文件识别引擎
            String rawText = asrFileEngine.recognizeFile(tempFile, format, sampleRate);
            if (!StringUtils.hasText(rawText)) return null;

            // 4. LLM 纠错
            return correctionService.correct(rawText);
        } catch (Exception e) {
            log.error("文件识别失败: {}", e.getMessage());
            return null;
        } finally {
            if (tempFile != null) tempFile.delete();
        }
    }

    /**
     * 将音频字节数组保存为临时文件
     *
     * @param data 音频原始字节
     * @return 临时文件，失败返回 null
     */
    private File saveTempFile(byte[] data) {
        try {
            File f = File.createTempFile("asr_", ".wav");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            return f;
        } catch (IOException e) {
            log.error("保存临时文件失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 根据 MIME 类型和文件后缀推断音频格式
     *
     * @param contentType MIME 类型
     * @param file        音频文件
     * @return 音频格式标识（wav/mp3/pcm）
     */
    private String guessFormat(String contentType, File file) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.contains("wav") || lower.contains("wave")) return "wav";
            if (lower.contains("mp3")) return "mp3";
            if (lower.contains("pcm")) return "pcm";
        }
        String name = file.getName().toLowerCase();
        if (name.endsWith(".wav")) return "wav";
        if (name.endsWith(".mp3")) return "mp3";
        return "wav";
    }

    /**
     * 检测音频文件采样率，非 WAV 格式默认返回 16000
     *
     * @param file   音频文件
     * @param format 音频格式
     * @return 采样率（Hz）
     */
    private int detectSampleRate(File file, String format) {
        if (!"wav".equals(format)) return 16000;
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            AudioFormat fmt = ais.getFormat();
            return (int) fmt.getSampleRate();
        } catch (Exception e) {
            return 16000;
        }
    }
}
