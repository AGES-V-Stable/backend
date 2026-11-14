package ages.vstable.backend.external.ai;

import ages.vstable.backend.dto.support.SupportChatMessageDTO;

import java.util.List;

public interface SupportChatGateway {

    record SupportChatGatewayResult(String reply, String provider) {
    }

    SupportChatGatewayResult generateReply(
            String systemPrompt,
            List<SupportChatMessageDTO> history,
            String userMessage,
            String userContextSummary
    );
}
