package com.maverick.maverickchatbot.web;

import com.maverick.maverickchatbot.ai.AiCodeHelper;
import com.maverick.maverickchatbot.ai.asr.SpeechToTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.maverick.maverickchatbot.web.dto.ChatAudioResponse;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final AiCodeHelper aiCodeHelper;
    private final SpeechToTextService speechToTextService;

    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAudioResponse chatWithAudio(@RequestParam("file") MultipartFile file) {
        String asrText = speechToTextService.transcribe(file);
        String aiText = aiCodeHelper.chat(asrText);
        return new ChatAudioResponse(asrText, aiText);
    }
}


