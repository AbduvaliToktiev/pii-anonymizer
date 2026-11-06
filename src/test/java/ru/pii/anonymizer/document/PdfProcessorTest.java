package ru.pii.anonymizer.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.pii.anonymizer.SampleDocuments;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

class PdfProcessorTest {

    private Path font;
    private PdfProcessor processor;

    @BeforeEach
    void setUp() {
        var systemFont = SampleDocuments.systemFont();
        assumeTrue(systemFont.isPresent(), "Нет системного шрифта с кириллицей");
        font = systemFont.get();
        processor = new PdfProcessor(new TextAnonymizer(RussianPiiDetectors.all()), new PdfProperties(null, null));
    }

    @Test
    void rebuiltPdfContainsNoPii() throws IOException {
        byte[] result = processor.process(SampleDocuments.memoPdf(font), new PseudonymRegistry());
        String text = DocumentText.pdf(result);

        assertThat(text).doesNotContain(SampleDocuments.PII_VALUES);
        assertThat(text).doesNotContain("Петровой", "Иванов Иван");
        assertThat(text).contains("Служебная записка", "50 000 рублей",
                "сотруднику [PERSON_1] (СНИЛС [SNILS_1])", "[PHONE_1]", "[EMAIL_1]", "[PHONE_2]");
    }

    @Test
    void scannedPdfWithoutTextIsRejected() throws IOException {
        byte[] blank;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            blank = out.toByteArray();
        }

        assertThatThrownBy(() -> processor.process(blank, new PseudonymRegistry()))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("нет текстового слоя");
    }
}
