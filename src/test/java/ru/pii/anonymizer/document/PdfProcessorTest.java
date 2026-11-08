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

import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.SampleDocuments;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

class PdfProcessorTest {

    private Path font;
    private PdfProcessor processor;

    @BeforeEach
    void setUp() {
        // Шрифт нужен только чтобы создать тестовый PDF; сам сервис шрифтов не требует
        var systemFont = SampleDocuments.systemFont();
        assumeTrue(systemFont.isPresent(), "Нет системного шрифта с кириллицей для тестового PDF");
        font = systemFont.get();
        processor = new PdfProcessor(new TextAnonymizer(RussianPiiDetectors.all()));
    }

    /**
     * PDF, сохранённый Word: шрифты встроены не целиком, слово «Петровой Анне Сергеевне» разбито на фрагменты
     * с разным оформлением, есть таблица. ПДн нет, страница та же, шрифты документа на месте, у одного
     * человека одна метка — в тексте, в сокращении и в таблице.
     */
    @Test
    void wordPdfIsAnonymizedInPlaceWithOriginalFonts() throws IOException {
        byte[] source;
        try (var in = PdfProcessorTest.class.getResourceAsStream("/documents/order.pdf")) {
            source = in.readAllBytes();
        }

        byte[] result = processor.process(source, new PseudonymRegistry());

        String text = DocumentText.pdf(result);
        assertThat(text).doesNotContain(ru.pii.anonymizer.LargeSamples.ORDER_PII);
        assertThat(text).contains("Приказ № 42 о премировании", "30 000 рублей", "ФИО", "Отдел", "Премия, руб.",
                "Продажи", "Маркетинг");
        // Метка короче ФИО: на её месте остаётся промежуток, поэтому пробелов может быть несколько
        String numbered = Tokens.renumber(text).replaceAll("[ \\t]+", " ");
        assertThat(numbered).contains("премию [PERSON_2] (СНИЛС [SNILS_1]", "ознакомить [PERSON_2]");
        assertThat(numbered).containsPattern("\\[PERSON_2]\\s*Продажи\\s+30 000");

        try (PDDocument before = org.apache.pdfbox.Loader.loadPDF(source);
             PDDocument after = org.apache.pdfbox.Loader.loadPDF(result)) {
            assertThat(after.getNumberOfPages()).isEqualTo(before.getNumberOfPages());
            java.util.Set<String> fontsBefore = fontNames(before);
            java.util.Set<String> fontsAfter = fontNames(after);
            assertThat(fontsAfter).containsAll(fontsBefore).contains("Helvetica");
            assertThat(after.getDocumentInformation().getAuthor()).isEmpty();
        }
    }

    private static java.util.Set<String> fontNames(PDDocument doc) throws IOException {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (PDPage page : doc.getPages()) {
            for (var name : page.getResources().getFontNames()) {
                names.add(page.getResources().getFont(name).getName());
            }
        }
        return names;
    }

    @Test
    void anonymizedPdfContainsNoPii() throws IOException {
        byte[] result = processor.process(SampleDocuments.memoPdf(font), new PseudonymRegistry());
        String text = Tokens.renumber(DocumentText.pdf(result)).replaceAll("[ \\t]+", " ");

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
