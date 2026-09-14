package ages.vstable.backend.service;

import ages.vstable.backend.exception.UnprocessableEntityException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
class EmpresaDadosValidator {

    private static final Pattern CNPJ_DIGITOS = Pattern.compile("\\d{14}");
    private static final Pattern CNPJ_MASCARADO = Pattern.compile("\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}");
    private static final Pattern CEP_DIGITOS = Pattern.compile("\\d{8}");
    private static final Pattern CEP_MASCARADO = Pattern.compile("\\d{5}-\\d{3}");

    EmpresaDadosNormalizados normalize(
            String razaoSocial,
            String cnpj,
            String pais,
            String cep,
            String cidade,
            String estado
    ) {
        String razaoSocialNormalizada = required(razaoSocial, "razaoSocial", 255);
        if (razaoSocialNormalizada.length() < 3) {
            throw new IllegalArgumentException("razaoSocial: tamanho deve ser entre 3 e 255");
        }

        String paisNormalizado = required(pais, "pais", 100);
        String estadoNormalizado = required(estado, "estado", 100);
        String cidadeNormalizada = optional(cidade, "cidade", 255);

        return new EmpresaDadosNormalizados(
                razaoSocialNormalizada,
                normalizeCnpj(cnpj),
                paisNormalizado,
                normalizeCep(paisNormalizado, cep),
                cidadeNormalizada,
                estadoNormalizado
        );
    }

    private String normalizeCnpj(String cnpj) {
        String value = cnpj == null ? "" : cnpj.trim();
        if (!CNPJ_DIGITOS.matcher(value).matches() && !CNPJ_MASCARADO.matcher(value).matches()) {
            throw new UnprocessableEntityException("CNPJ em formato inválido");
        }

        String digits = value.replaceAll("\\D", "");
        if (digits.chars().distinct().count() == 1
                || calculateDigit(digits.substring(0, 12), new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}) != digits.charAt(12) - '0'
                || calculateDigit(digits.substring(0, 13), new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}) != digits.charAt(13) - '0') {
            throw new UnprocessableEntityException("CNPJ em formato inválido");
        }
        return digits;
    }

    private int calculateDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }

    private String normalizeCep(String pais, String cep) {
        String value = required(cep, "cep", 20);
        if (!"Brasil".equalsIgnoreCase(pais)) {
            return value;
        }
        if (!CEP_DIGITOS.matcher(value).matches() && !CEP_MASCARADO.matcher(value).matches()) {
            throw new UnprocessableEntityException("CEP em formato inválido");
        }
        return value.replace("-", "");
    }

    private String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + ": não deve estar em branco");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + ": tamanho máximo é " + maxLength);
        }
        return normalized;
    }

    private String optional(String value, String field, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + ": tamanho máximo é " + maxLength);
        }
        return normalized;
    }
}
