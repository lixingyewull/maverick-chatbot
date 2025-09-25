package com.maverick.maverickchatbot.ai.roles;

import lombok.Data;

@Data
public class RoleConfig {
    private String id;
    private String name;
    private String avatar; // URL or relative path
    private String personaPrompt;
}


