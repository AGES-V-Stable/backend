package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Money;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gera o comprovante (PDF de uma página) a partir dos detalhes registrados da transferência.
 * Usa só fontes padrão do PDF, então caracteres fora do Latin-1 viram "?".
 */
@Component
public class TransferReceiptPdfGenerator implements TransferReceiptProvider {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'xxx");
    private static final float MARGIN = 50f;
    private static final float VALUE_X = 230f;
    private static final float LINE_HEIGHT = 22f;
    private static final int MAX_VALUE_LENGTH = 62;

    @Override
    public byte[] generate(TransferDetailsResponse details) {
        PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN - 10;
                writeText(content, bold, 20, MARGIN, y, "Comprovante de transferência");
                y -= 22;
                writeText(content, regular, 11, MARGIN, y, "V-Stable");
                y -= 40;

                for (Map.Entry<String, String> row : rows(details).entrySet()) {
                    writeText(content, bold, 11, MARGIN, y, row.getKey());
                    writeText(content, regular, 11, VALUE_X, y, abbreviate(row.getValue()));
                    y -= LINE_HEIGHT;
                }

                y -= 20;
                writeText(content, regular, 9, MARGIN, y,
                        "Documento gerado a partir dos dados registrados da operação em "
                                + DATE_TIME.format(OffsetDateTime.now()) + ".");
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gerar o comprovante em PDF", e);
        }
    }

    private Map<String, String> rows(TransferDetailsResponse details) {
        boolean incoming = details.type() == Type.RECEBIMENTO;
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Transferência", String.valueOf(details.id()));
        rows.put("Data", details.date() == null ? "-" : DATE_TIME.format(details.date()));
        rows.put("Tipo", incoming ? "Recebimento internacional" : "Pagamento internacional");
        rows.put("Status", statusLabel(details));
        rows.put(incoming ? "Pagador" : "Beneficiário", orDash(details.counterpartyName()));
        rows.put("Valor de origem", formatMoney(details.source()));
        rows.put("Valor de destino", formatMoney(details.destination()));
        if (details.exchangeRate() != null) {
            rows.put("Cotação aplicada", "1 " + details.exchangeRate().fromCurrency() + " = "
                    + formatMoney(new Money(details.exchangeRate().rate(), details.exchangeRate().toCurrency())));
        }
        if (details.costs() != null && details.costs().serviceFee() != null) {
            var fee = details.costs().serviceFee();
            String percentage = fee.percentage() == null ? "" : formatPercent(fee.percentage()) + " - ";
            rows.put("Taxa V-Stable", percentage + formatMoney(new Money(fee.amount(), fee.currency())));
        }
        rows.put("Origem dos recursos", fundingLabel(details.fundingSource()));
        return rows;
    }

    private static String statusLabel(TransferDetailsResponse details) {
        if (details.status() == null) {
            return "-";
        }
        return switch (details.status()) {
            case SETTLED -> "Concluída";
            case PROCESSING -> "Em processamento";
            case AWAITING_PAYMENT -> "Aguardando pagamento";
            case HELD -> "Retida para análise";
            case FAILED -> "Com falha";
            case PARTIAL_FAILURE -> "Com falha parcial";
            case CANCELED -> "Cancelada";
            case EXPIRED -> "Expirada";
        };
    }

    private static String fundingLabel(String fundingSource) {
        if (fundingSource == null) {
            return "-";
        }
        return switch (fundingSource) {
            case "ACCOUNT_BALANCE" -> "Saldo V-Stable";
            case "PIX" -> "PIX";
            case "TED" -> "TED";
            case "BLOCKCHAIN" -> "Blockchain";
            default -> fundingSource;
        };
    }

    private static String formatMoney(Money money) {
        if (money == null || money.amount() == null) {
            return "-";
        }
        BigDecimal amount = new BigDecimal(money.amount());
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance(PT_BR);
            format.setCurrency(Currency.getInstance(money.currency()));
            return format.format(amount);
        } catch (IllegalArgumentException | NullPointerException e) {
            NumberFormat plain = NumberFormat.getNumberInstance(PT_BR);
            plain.setMinimumFractionDigits(2);
            return money.currency() + " " + plain.format(amount);
        }
    }

    private static String formatPercent(String percentage) {
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(4);
        return format.format(new BigDecimal(percentage)) + "%";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String abbreviate(String value) {
        return value.length() <= MAX_VALUE_LENGTH ? value : value.substring(0, MAX_VALUE_LENGTH - 3) + "...";
    }

    private static void writeText(PDPageContentStream content, PDType1Font font, float size,
                                  float x, float y, String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(encodable(font, text));
        content.endText();
    }

    /** Troca por "?" o que a fonte não codifica, para um nome exótico não derrubar o comprovante. */
    private static String encodable(PDType1Font font, String text) {
        StringBuilder safe = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            try {
                font.encode(character);
                safe.append(Character.isISOControl(codePoint) ? "?" : character);
            } catch (IOException | IllegalArgumentException e) {
                safe.append('?');
            }
        });
        return safe.toString();
    }
}
