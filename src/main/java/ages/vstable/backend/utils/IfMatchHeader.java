package ages.vstable.backend.utils;

import ages.vstable.backend.exception.InvalidParameterException;
import ages.vstable.backend.exception.PreconditionRequiredException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lê a versão do recurso enviada em If-Match, no mesmo formato do ETag devolvido: decimal entre aspas. */
public final class IfMatchHeader {

    private static final Pattern VERSION = Pattern.compile("\"(\\d{1,18})\"");

    private IfMatchHeader() {
    }

    /** A versão informada; ausente é 428 {@code VERSION_REQUIRED} e fora do formato é 400. */
    public static long requireVersion(String header) {
        if (header == null || header.isBlank()) {
            throw new PreconditionRequiredException("VERSION_REQUIRED",
                    "Informe a versão do recurso no cabeçalho If-Match.");
        }
        Matcher matcher = VERSION.matcher(header.trim());
        if (!matcher.matches()) {
            throw new InvalidParameterException(
                    "O cabeçalho If-Match deve ser a versão do recurso entre aspas, como \"3\".");
        }
        return Long.parseLong(matcher.group(1));
    }
}
