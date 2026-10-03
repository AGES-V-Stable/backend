package ages.vstable.backend.service;

import ages.vstable.backend.dto.company.CompanyNormalizedData;
import ages.vstable.backend.exception.UnprocessableEntityException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
class CompanyDataValidator {

    private static final Pattern CNPJ_DIGITS_ONLY = Pattern.compile("\\d{14}");
    private static final Pattern CNPJ_MASKED = Pattern.compile("\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}");
    private static final Pattern CEP_DIGITS_ONLY = Pattern.compile("\\d{8}");
    private static final Pattern CEP_MASKED = Pattern.compile("\\d{5}-\\d{3}");

    CompanyNormalizedData normalize(
            String legalName,
            String cnpj,
            String country,
            String zipCode,
            String city,
            String state
    ) {
        String normalizedLegalName = required(legalName, "legalName", 255);
        if (normalizedLegalName.length() < 3) {
            throw new IllegalArgumentException("legalName: length must be between 3 and 255");
        }

        String normalizedCountry = required(country, "country", 100);
        String normalizedState = required(state, "state", 100);
        String normalizedCity = optional(city, "city", 255);

        return new CompanyNormalizedData(
                normalizedLegalName,
                normalizeCnpj(cnpj),
                normalizedCountry,
                normalizeCep(normalizedCountry, zipCode),
                normalizedCity,
                normalizedState
        );
    }

    private String normalizeCnpj(String cnpj) {
        String value = cnpj == null ? "" : cnpj.trim();
        if (!CNPJ_DIGITS_ONLY.matcher(value).matches() && !CNPJ_MASKED.matcher(value).matches()) {
            throw new UnprocessableEntityException("CNPJ has an invalid format");
        }

        String digits = value.replaceAll("\\D", "");
        if (digits.chars().distinct().count() == 1
                || calculateDigit(digits.substring(0, 12), new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}) != digits.charAt(12) - '0'
                || calculateDigit(digits.substring(0, 13), new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}) != digits.charAt(13) - '0') {
            throw new UnprocessableEntityException("CNPJ has an invalid format");
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

    private String normalizeCep(String country, String zipCode) {
        String value = required(zipCode, "zipCode", 20);
        if (!"Brasil".equalsIgnoreCase(country)) {
            return value;
        }
        if (!CEP_DIGITS_ONLY.matcher(value).matches() && !CEP_MASKED.matcher(value).matches()) {
            throw new UnprocessableEntityException("Zip code has an invalid format");
        }
        return value.replace("-", "");
    }

    private String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + ": must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + ": maximum length is " + maxLength);
        }
        return normalized;
    }

    private String optional(String value, String field, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + ": maximum length is " + maxLength);
        }
        return normalized;
    }
}
