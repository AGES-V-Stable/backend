package ages.vstable.backend.service;

import ages.vstable.backend.dto.transaction.TransferDetailsResponse;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Cost;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Costs;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.ExchangeRate;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Money;
import ages.vstable.backend.dto.transaction.TransferDetailsResponse.Type;
import ages.vstable.backend.entity.enums.TransactionStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TransferReceiptPdfGeneratorTest {

    private final TransferReceiptPdfGenerator generator = new TransferReceiptPdfGenerator();
    private final UUID transferId = UUID.randomUUID();

    private TransferDetailsResponse payment(String counterparty) {
        return new TransferDetailsResponse(
                transferId,
                UUID.randomUUID(),
                OffsetDateTime.of(2026, 8, 24, 14, 32, 0, 0, ZoneOffset.ofHours(-3)),
                Type.PAGAMENTO,
                TransactionStatus.SETTLED,
                counterparty,
                null,
                new Money("125000.00", "BRL"),
                new Money("23062.73", "USD"),
                "ACCOUNT_BALANCE",
                new ExchangeRate("USD", "BRL", "5.42"),
                new Costs(new Cost("562.50", "BRL", "0.45"), null, null),
                null,
                true);
    }

    /** Extrai o texto do PDF; o formato pt-BR usa espaço não separável entre símbolo e valor. */
    private String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            return new PDFTextStripper().getText(document).replace(' ', ' ');
        }
    }

    @Test
    void generate_producesAValidPdfWithTheRecordedOperation() throws IOException {
        byte[] pdf = generator.generate(payment("Atlas Imports LLC"));

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text)
                .contains("Comprovante de transferência")
                .contains(transferId.toString())
                .contains("24/08/2026 14:32")
                .contains("Pagamento internacional")
                .contains("Concluída")
                .contains("Beneficiário")
                .contains("Atlas Imports LLC")
                .contains("R$ 125.000,00")
                .contains("US$ 23.062,73")
                .contains("1 USD = R$ 5,42")
                .contains("0,45% - R$ 562,50")
                .contains("Saldo V-Stable")
                .contains("Documento gerado a partir dos dados registrados da operação em ");
    }

    @Test
    void generate_receipt_labelsTheCounterpartyAsPayer() throws IOException {
        TransferDetailsResponse base = payment("Demo Overseas Customer");
        TransferDetailsResponse receipt = new TransferDetailsResponse(
                base.id(), base.companyId(), base.date(), Type.RECEBIMENTO, TransactionStatus.SETTLED,
                base.counterpartyName(), null, base.destination(), base.source(), null,
                base.exchangeRate(), base.costs(), null, true);

        String text = textOf(generator.generate(receipt));

        assertThat(text).contains("Recebimento internacional").contains("Pagador").contains("Demo Overseas Customer");
    }

    @Test
    void generate_unicodeNames_preservesCjkEmojiGreekAndCyrillic() throws IOException {
        String name = "東京商事 Ltd 😀 João Łukasz Ωμέγα Москва";
        String text = textOf(generator.generate(payment(name)));

        assertThat(text).contains(name).doesNotContain("?");
    }

    @Test
    void generate_longName_wrapsWithoutLosingCharacters() throws IOException {
        String name = "InternationalBeneficiary".repeat(12) + " 東京商事 FIM";
        String text = textOf(generator.generate(payment(name))).replaceAll("\\s", "");

        assertThat(text).contains(name.replaceAll("\\s", "")).doesNotContain("...");
    }

    @Test
    void generate_explicitNewline_preservesBothLines() throws IOException {
        String text = textOf(generator.generate(payment("東京商事\nSegunda linha")));

        assertThat(text).contains("東京商事").contains("Segunda linha").doesNotContain("?");
    }

    @Test
    void generate_veryLongPayerName_continuesOnAdditionalPages() throws IOException {
        String name = "Pagador東京".repeat(600) + " FIM";
        TransferDetailsResponse base = payment(name);
        TransferDetailsResponse incoming = new TransferDetailsResponse(
                base.id(), base.companyId(), base.date(), Type.RECEBIMENTO, base.status(), name,
                null, base.destination(), base.source(), null, base.exchangeRate(), base.costs(), null, true);
        try (PDDocument document = Loader.loadPDF(generator.generate(incoming))) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(3);
            String text = new PDFTextStripper().getText(document).replaceAll("\\s", "");
            String withoutHeaders = text.replace("ComprovantedetransferênciaV-Stable", "");
            assertThat(withoutHeaders).contains(name.replaceAll("\\s", ""));
            assertThat(text).contains("Valordeorigem").contains("TaxaV-Stable");
        }
    }

    @Test
    void generate_missingOptionalValues_showsDashesWithoutInventingValues() throws IOException {
        TransferDetailsResponse sparse = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, null, null, null, null,
                new Money(null, "BRL"), new Money("10.00", "USD"), null, null, null, null, true);

        String text = textOf(generator.generate(sparse));

        assertThat(text)
                .contains("Status -")
                .contains("Beneficiário -")
                .contains("Valor de origem -")
                .contains("US$ 10,00")
                .contains("Origem dos recursos -")
                .doesNotContain("Cotação aplicada");
    }

    @Test
    void generate_unknownCurrencyCode_fallsBackToTheCode() throws IOException {
        TransferDetailsResponse stable = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, Type.PAGAMENTO, TransactionStatus.SETTLED, "Wallet", null,
                new Money("100.00", "BRL"), new Money("19.80", "USDC"), "BLOCKCHAIN", null, null, null, true);

        String text = textOf(generator.generate(stable));

        assertThat(text).contains("USDC 19,80").contains("Blockchain");
    }

    @Test
    void generate_formatsPercentageWithTwoToFourFractionDigits() throws IOException {
        TransferDetailsResponse minDigits = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, Type.PAGAMENTO, TransactionStatus.SETTLED, "Atlas", null,
                new Money("100.00", "BRL"), new Money("20.00", "USD"), "PIX", null,
                new Costs(new Cost("1.00", "BRL", "1"), null, null), null, true);
        TransferDetailsResponse maxDigits = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, Type.PAGAMENTO, TransactionStatus.SETTLED, "Atlas", null,
                new Money("100.00", "BRL"), new Money("20.00", "USD"), "TED", null,
                new Costs(new Cost("1.23", "BRL", "1.23456"), null, null), null, true);

        assertThat(textOf(generator.generate(minDigits))).contains("1,00% - R$ 1,00").contains("PIX");
        assertThat(textOf(generator.generate(maxDigits))).contains("1,2346% - R$ 1,23").contains("TED");
    }

    @Test
    void generate_rowsFillingFirstPage_pushesFooterOntoSecondPage() throws IOException {
        String twentyTwoLines = String.join("\n", java.util.Collections.nCopies(22, "Linha Beneficiario"));
        TransferDetailsResponse details = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, Type.PAGAMENTO, TransactionStatus.SETTLED,
                twentyTwoLines, null, new Money("100.00", "BRL"), new Money("20.00", "USD"),
                "PIX", null, null, null, true);

        try (PDDocument document = Loader.loadPDF(generator.generate(details))) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            PDFTextStripper page2Stripper = new PDFTextStripper();
            page2Stripper.setStartPage(2);
            page2Stripper.setEndPage(2);
            String page2Text = page2Stripper.getText(document);
            assertThat(page2Text).contains("Documento gerado a partir dos dados registrados da operação em ");
        }
    }

    @Test
    void generate_positionsHeaderRowsAndFooterAtExpectedVerticalCoordinates() throws IOException {
        byte[] pdf = generator.generate(payment("Beneficiario Com Espacos Multiplos ".repeat(6).trim()));
        List<Float> lineYPositions = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                    if (!textPositions.isEmpty()) {
                        lineYPositions.add(textPositions.getFirst().getTextMatrix().getTranslateY());
                    }
                    super.writeString(text, textPositions);
                }
            };
            String extracted = stripper.getText(document);
            assertThat(extracted).contains("Beneficiario Com Espacos Multiplos");
        }
        float pageHeight = PDRectangle.A4.getHeight();
        assertThat(lineYPositions.get(0)).isCloseTo(pageHeight - 50f - 10f, within(0.1f));
        assertThat(lineYPositions.get(1)).isCloseTo(pageHeight - 50f - 10f - 22f, within(0.1f));
        assertThat(lineYPositions.get(2)).isCloseTo(pageHeight - 50f - 10f - 22f - 40f, within(0.1f));
        assertThat(lineYPositions.getLast()).isLessThan(lineYPositions.get(lineYPositions.size() - 2) - 35f);
    }

    @Test
    @SuppressWarnings("unchecked")
    void wrapAndTextWidth_handleExactWidthLeadingSpaceAndWordWrapping() throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDFont regular = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "loadFont", document, "NotoSans-Regular.ttf");
            List<PDFont> fonts = List.of(regular);

            Float abcWidth = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "textWidth", "ABC", fonts, 11f);
            assertThat(abcWidth).isNotNull().isGreaterThan(10f).isLessThan(50f);

            List<String> exactFit = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "wrap", "ABC", fonts, 11f, abcWidth);
            assertThat(exactFit).containsExactly("ABC");

            Float slightlySmaller = abcWidth - 0.1f;
            List<String> wrappedAbc = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "wrap", "ABC", fonts, 11f, slightlySmaller);
            assertThat(wrappedAbc).containsExactly("AB", "C");

            List<String> leadingSpaceOversized = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "wrap", " " + "A".repeat(40), fonts, 11f, 50f);
            assertThat(leadingSpaceOversized.getFirst()).startsWith(" A");
            assertThat(String.join("", leadingSpaceOversized)).isEqualTo(" " + "A".repeat(40));

            Float twoWordsWidth = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "textWidth", "Alpha Beta ", fonts, 11f);
            List<String> multiWord = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "wrap", "Alpha Beta Gamma", fonts, 11f, twoWordsWidth);
            assertThat(multiWord).containsExactly("Alpha Beta ", "Gamma");
        }
    }

    @Test
    void receiptLayout_initializesFirstPageAndChecksEnsureSpaceBoundary() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDFont regular = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "loadFont", document, "NotoSans-Regular.ttf");
            PDFont bold = ReflectionTestUtils.invokeMethod(
                    TransferReceiptPdfGenerator.class, "loadFont", document, "NotoSans-Bold.ttf");

            Class<?> layoutClass = Class.forName(
                    "ages.vstable.backend.service.TransferReceiptPdfGenerator$ReceiptLayout");
            Constructor<?> ctor = layoutClass.getDeclaredConstructor(PDDocument.class, PDFont.class, PDFont.class);
            ctor.setAccessible(true);

            try (AutoCloseable layout = (AutoCloseable) ctor.newInstance(document, regular, bold)) {
                assertThat(document.getNumberOfPages()).isEqualTo(1);
                float initialY = (float) ReflectionTestUtils.getField(layout, "y");
                assertThat(initialY).isCloseTo(PDRectangle.A4.getHeight() - 50f - 10f - 22f - 40f, within(0.01f));

                ReflectionTestUtils.setField(layout, "y", 72f);
                ReflectionTestUtils.invokeMethod(layout, "ensureSpace");
                assertThat(document.getNumberOfPages()).isEqualTo(1);

                ReflectionTestUtils.setField(layout, "y", 71.9f);
                ReflectionTestUtils.invokeMethod(layout, "ensureSpace");
                assertThat(document.getNumberOfPages()).isEqualTo(2);
            }
        }
    }
}
