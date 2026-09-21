package ages.vstable.backend.external.avenia.dto;

import lombok.Data;

// TODO: shape não confirmado contra o sandbox real (diferente do endpoint de liveness,
// que já foi validado — ver AveniaClientTest). Ajustar os nomes dos campos assim que
// alguém rodar POST /v2/documents/ com documentType ID/DRIVERS-LICENSE/PASSPORT contra
// o sandbox e confirmar a resposta de verdade.
@Data
public class AveniaDocumentUploadResponse {
    private String id;
    private String uploadUrlFront;
    private String uploadUrlBack;
}
