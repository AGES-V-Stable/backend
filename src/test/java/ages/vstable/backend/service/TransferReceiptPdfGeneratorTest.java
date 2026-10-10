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
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                .contains("Saldo V-Stable");
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
    void generate_characterTheFontCannotEncode_becomesQuestionMarkInsteadOfFailing() throws IOException {
        String text = textOf(generator.generate(payment("東京商事 Ltd 😀\nlinha")));

        assertThat(text).contains("???? Ltd ??linha").doesNotContain("東京");
    }

    @Test
    void generate_missingOptionalValues_showsDashesWithoutInventingValues() throws IOException {
        TransferDetailsResponse sparse = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, null, TransactionStatus.SETTLED, null, null,
                new Money(null, "BRL"), new Money("10.00", "USD"), null, null, null, null, true);

        String text = textOf(generator.generate(sparse));

        assertThat(text).contains("Valor de origem -").contains("US$ 10,00").doesNotContain("Cotação aplicada");
    }

    @Test
    void generate_unknownCurrencyCode_fallsBackToTheCode() throws IOException {
        TransferDetailsResponse stable = new TransferDetailsResponse(
                transferId, UUID.randomUUID(), null, Type.PAGAMENTO, TransactionStatus.SETTLED, "Wallet", null,
                new Money("100.00", "BRL"), new Money("19.80", "USDC"), "BLOCKCHAIN", null, null, null, true);

        String text = textOf(generator.generate(stable));

        assertThat(text).contains("USDC 19,80").contains("Blockchain");
    }
}
