package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Money;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Gera o comprovante a partir dos detalhes registrados, com fontes Unicode incorporadas.
 * Valores longos quebram linha e continuam em outra página, sem abreviar nomes.
 */
@Component
public class TransferReceiptPdfGenerator implements TransferReceiptProvider {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'xxx");
    private static final float MARGIN = 50f;
    private static final float VALUE_X = 230f;
    private static final float LINE_HEIGHT = 22f;
    private static final float VALUE_WIDTH = PDRectangle.A4.getWidth() - MARGIN - VALUE_X;

    @Override
    public byte[] generate(TransferDetailsResponse details) {
        try (PDDocument document = new PDDocument()) {
            PDFont regular = loadFont(document, "NotoSans-Regular.ttf");
            PDFont bold = loadFont(document, "NotoSans-Bold.ttf");
            List<PDFont> fonts = List.of(regular, loadFont(document, "NotoSansJP-Regular.ttf"),
                    loadFont(document, "NotoEmoji-Regular.ttf"));

            try (ReceiptLayout layout = new ReceiptLayout(document, regular, bold)) {
                for (Map.Entry<String, String> row : rows(details).entrySet()) {
                    boolean firstLine = true;
                    for (String line : wrap(row.getValue(), fonts, 11, VALUE_WIDTH)) {
                        layout.ensureSpace();
                        if (firstLine) {
                            writeText(layout.content, bold, 11, MARGIN, layout.y, row.getKey());
                            firstLine = false;
                        }
                        writeUnicodeText(layout.content, fonts, 11, VALUE_X, layout.y, line);
                        layout.y -= LINE_HEIGHT;
                    }
                }
                layout.y -= 20;
                String footer = "Documento gerado a partir dos dados registrados da operação em "
                        + DATE_TIME.format(OffsetDateTime.now()) + ".";
                for (String line : wrap(footer, fonts, 9, PDRectangle.A4.getWidth() - 2 * MARGIN)) {
                    layout.ensureSpace();
                    writeUnicodeText(layout.content, fonts, 9, MARGIN, layout.y, line);
                    layout.y -= LINE_HEIGHT;
                }
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

    private static PDFont loadFont(PDDocument document, String filename) throws IOException {
        try (InputStream input = TransferReceiptPdfGenerator.class.getResourceAsStream("/fonts/" + filename)) {
            if (input == null) {
                throw new IOException("Fonte do comprovante não encontrada: " + filename);
            }
            return PDType0Font.load(document, input);
        }
    }

    private static PDFont fontFor(List<PDFont> fonts, String character) throws IOException {
        for (PDFont font : fonts) {
            try {
                font.encode(character);
                return font;
            } catch (IllegalArgumentException ignored) {
                // Try the next bundled Unicode font; never replace a name with question marks.
            }
        }
        throw new IOException("Caractere sem suporte nas fontes do comprovante: U+"
                + Integer.toHexString(character.codePointAt(0)));
    }

    /** Wrap by measured glyph width, preserving explicit newlines and splitting oversized words. */
    private static List<String> wrap(String text, List<PDFont> fonts, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\R", -1)) {
            StringBuilder line = new StringBuilder();
            float lineWidth = 0;
            for (int codePoint : paragraph.codePoints().toArray()) {
                String character = new String(Character.toChars(codePoint));
                if (character.equals("\t")) {
                    character = " ";
                }
                float glyphWidth = fontFor(fonts, character).getStringWidth(character) * size / 1000;
                if (lineWidth + glyphWidth > width && !line.isEmpty()) {
                    int breakAt = line.lastIndexOf(" ");
                    if (breakAt > 0) {
                        lines.add(line.substring(0, breakAt + 1));
                        line.delete(0, breakAt + 1);
                        lineWidth = textWidth(line.toString(), fonts, size);
                    } else {
                        lines.add(line.toString());
                        line.setLength(0);
                        lineWidth = 0;
                    }
                }
                line.append(character);
                lineWidth += glyphWidth;
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static float textWidth(String text, List<PDFont> fonts, float size) throws IOException {
        float width = 0;
        for (int codePoint : text.codePoints().toArray()) {
            String character = new String(Character.toChars(codePoint));
            width += fontFor(fonts, character).getStringWidth(character) * size / 1000;
        }
        return width;
    }

    private static void writeUnicodeText(PDPageContentStream content, List<PDFont> fonts, float size,
                                         float x, float y, String text) throws IOException {
        content.beginText();
        content.newLineAtOffset(x, y);
        PDFont current = null;
        for (int codePoint : text.codePoints().toArray()) {
            String character = new String(Character.toChars(codePoint));
            PDFont font = fontFor(fonts, character);
            if (font != current) {
                content.setFont(font, size);
                current = font;
            }
            // Emit individual code points so automatic ligatures cannot alter extracted names or IDs.
            content.showText(character);
        }
        content.endText();
    }

    private static void writeText(PDPageContentStream content, PDFont font, float size,
                                  float x, float y, String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        for (int codePoint : text.codePoints().toArray()) {
            content.showText(new String(Character.toChars(codePoint)));
        }
        content.endText();
    }

    private static final class ReceiptLayout implements AutoCloseable {
        private final PDDocument document;
        private final PDFont regular;
        private final PDFont bold;
        private PDPageContentStream content;
        private float y;

        private ReceiptLayout(PDDocument document, PDFont regular, PDFont bold) throws IOException {
            this.document = document;
            this.regular = regular;
            this.bold = bold;
            newPage();
        }

        private void ensureSpace() throws IOException {
            if (y < MARGIN + LINE_HEIGHT) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN - 10;
            writeText(content, bold, 20, MARGIN, y, "Comprovante de transferência");
            y -= 22;
            writeText(content, regular, 11, MARGIN, y, "V-Stable");
            y -= 40;
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }
}
