package ages.vstable.backend.util;

public final class CpfUtils {

    private CpfUtils() {
    }

    public static boolean isValid(String cpf) {
        if (cpf == null) {
            return false;
        }

        String digitos = cpf.replaceAll("\\D", "");

        if (digitos.length() != 11 || digitos.chars().distinct().count() == 1) {
            return false;
        }

        int primeiroDigito = calculateCheckDigit(digitos.substring(0, 9), 10);
        int segundoDigito = calculateCheckDigit(digitos.substring(0, 9) + primeiroDigito, 11);

        return digitos.equals(digitos.substring(0, 9) + primeiroDigito + segundoDigito);
    }

    private static int calculateCheckDigit(String base, int pesoInicial) {
        int soma = 0;
        int peso = pesoInicial;

        for (char c : base.toCharArray()) {
            soma += Character.getNumericValue(c) * peso--;
        }

        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
