package com.ai.voice.controller;

import com.ai.voice.common.R;
import com.ai.voice.dto.TtsSynthesizeDTO;
import com.ai.voice.service.IAsrFileService;
import com.ai.voice.service.ITtsService;
import com.ai.voice.vo.AsrFileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音服务公开接口
 * 实时 ASR 使用 WebSocket /api/voice/asr/realtime
 */
@Tag(name = "语音服务", description = "TTS / ASR 文件识别 / 实时 ASR 见 WebSocket")
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class ApiVoiceController {

    private final ITtsService ttsService;
    private final IAsrFileService asrFileService;

    @Operation(summary = "文本转语音", description = "成功返回音频二进制流，失败返回 R 错误信息")
    @PostMapping("/tts")
    public ResponseEntity<?> tts(@RequestBody @Valid TtsSynthesizeDTO dto) {
        byte[] audio = ttsService.synthesize(dto);
        if (audio == null || audio.length == 0) {
            return ResponseEntity.ok(R.fail("语音合成失败，请检查服务配置或 API Key"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tts.mp3")
                .body(audio);
    }

    @Operation(summary = "上传录音文件转文本", description = "支持 WAV/MP3，返回识别+纠错后文本")
    @PostMapping(value = "/asr/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<AsrFileVO> asrFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) return R.fail("请选择要识别的音频文件");
        try {
            String text = asrFileService.recognizeFromFile(file.getBytes(), file.getContentType());
            if (text == null) return R.fail("识别失败或无可识别内容");
            return R.success(new AsrFileVO(text));
        } catch (Exception e) {
            return R.fail("处理文件失败: " + e.getMessage());
        }
    }
}
