package com.maverick.maverickchatbot.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

//@AiService
public interface AiCodeHelperService {

    @SystemMessage(fromResource = "system-prompt.txt")
    String chat(String userMessage);

    // 通过在用户消息中预置一条“系统前缀”，实现动态 persona 效果
    @SystemMessage(fromResource = "system-prompt.txt")
    String chatWithPersona(@UserMessage String personaPrefix, @UserMessage String userMessage);

    // 使用模板化 system prompt，动态注入 {{character}} 与 {{series}}
    @SystemMessage(fromResource = "system-prompt.txt")
    String chatWithRoleSystem(@V("character") String character,
                              @V("series") String series,
                              @UserMessage String userMessage);

    // 加入 few-shot 示例块
    @SystemMessage(fromResource = "system-prompt.txt")
    String chatWithRoleSystemAndShots(@V("character") String character,
                                      @V("series") String series,
                                      @UserMessage String fewShotBlock,
                                      @UserMessage String userMessage);

    // 结构化示例方式（如果未来升级到支持 Assistant 角色注解可再启用）
    // 保留文本块方式作为当前实现

}
