package com.maverick.maverickchatbot.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatAudioResponse {
    private String asrText;
    private String aiText;
}


