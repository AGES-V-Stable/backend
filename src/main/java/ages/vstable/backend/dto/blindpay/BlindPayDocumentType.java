package ages.vstable.backend.dto.blindpay;

/**
 * Finalidade de um documento enviado à BlindPay. Documentos de identidade aceitam
 * apenas imagem (JPEG/PNG); os demais aceitam também PDF.
 */
public enum BlindPayDocumentType {
    INCORPORATION(false),
    PROOF_OF_OWNERSHIP(false),
    PROOF_OF_ADDRESS(false),
    SOURCE_OF_FUNDS(false),
    ID_DOCUMENT_FRONT(true),
    ID_DOCUMENT_BACK(true),
    RFI_DOCUMENT(false);

    private final boolean imageOnly;

    BlindPayDocumentType(boolean imageOnly) {
        this.imageOnly = imageOnly;
    }

    public boolean imageOnly() {
        return imageOnly;
    }
}
