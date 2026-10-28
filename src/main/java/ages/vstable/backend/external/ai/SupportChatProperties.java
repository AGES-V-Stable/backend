package ages.vstable.backend.external.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.support")
public class SupportChatProperties {

    private String provider = "gemini";
    private String apiKey = "";
    private String geminiModel = "gemini-flash-lite-latest";
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com";
    private String deepseekModel = "deepseek-chat";
    private String deepseekBaseUrl = "https://api.deepseek.com";
}
