package ages.vstable.backend.external.blindpay.dto;

/** Validações estruturais compartilhadas pelos corpos de requisição da BlindPay. */
final class RequestValidation {

    private RequestValidation() {
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be provided");
        }
    }

    static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be provided");
        }
    }

    static void requireExactlyOne(String first, String firstField, String second, String secondField) {
        boolean hasFirst = first != null && !first.isBlank();
        boolean hasSecond = second != null && !second.isBlank();
        if (hasFirst == hasSecond) {
            throw new IllegalArgumentException(
                    "Exactly one of " + firstField + " or " + secondField + " must be provided");
        }
    }

    static void requireMinimumAmount(Long amountInCents, long minimumInCents, String field) {
        requireNonNull(amountInCents, field);
        if (amountInCents < minimumInCents) {
            throw new IllegalArgumentException(field + " must be at least " + minimumInCents + " cents");
        }
    }
}
