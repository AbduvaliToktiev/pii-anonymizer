package ru.pii.anonymizer.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.junit.jupiter.api.Test;

import ru.pii.anonymizer.LargeSamples;
import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/** doc, odt и pptx: ПДн нет ни в тексте, ни в байтах файла, а сам файл открывается и сохраняет структуру. */
class NewFormatsTest {

    private final TextAnonymizer anonymizer = new TextAnonymizer(RussianPiiDetectors.all());
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = NewFormatsTest.class.getResourceAsStream("/documents/" + name)) {
            return in.readAllBytes();
        }
    }

    // ---------- doc ----------

    @Test
    void docHasNoPiiAndSamePersonInAnyCaseGetsOneToken() throws IOException {
        byte[] result = new WordDocProcessor(anonymizer).process(resource("order.doc"), new PseudonymRegistry());

        String text = extractor.toText(DocumentFormat.DOC, result);
        assertThat(text).doesNotContain(LargeSamples.ORDER_PII);
        assertThat(RawBytes.leaked(result, LargeSamples.ORDER_PII)).isEmpty();
        assertThat(text).contains("Приказ № 42", "30 000 рублей", "Продажи", "Маркетинг");

        // «Иванову Ивану Ивановичу» в тексте, «Иванов Иван Иванович» в таблице, «Иванова И.И.» в конце — одна метка
        String numbered = Tokens.renumber(text);
        assertThat(numbered).contains("премию [PERSON_1] (СНИЛС", "ознакомить [PERSON_1] и ");
        assertThat(numbered).containsPattern("\\[PERSON_1\\]\\s+Продажи");
        try (HWPFDocument reopened = new HWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(reopened.getRange().numParagraphs()).isGreaterThan(5);
        }
    }

    @Test
    void docContractHeaderAndTableAreAnonymized() throws IOException {
        byte[] result = new WordDocProcessor(anonymizer).process(resource("contract.doc"), new PseudonymRegistry());

        String text = extractor.toText(DocumentFormat.DOC, result);
        assertThat(RawBytes.leaked(result, List.of("Иванов", "Петрова", "Петровой", "Кузнецов", "112-233-445 95",
                "45 06 123456", "hr@example.ru", "765-43-21"))).isEmpty();
        assertThat(text).contains("Трудовой договор № 15", "120 000 рублей");
    }

    // ---------- odt ----------

    @Test
    void odtHasNoPiiAndKeepsArchiveStructure() throws IOException {
        byte[] source = resource("order.odt");
        byte[] result = new OdtProcessor(anonymizer).process(source, new PseudonymRegistry());

        String text = extractor.toText(DocumentFormat.ODT, result);
        assertThat(text).doesNotContain(LargeSamples.ORDER_PII);
        assertThat(RawBytes.leaked(result, LargeSamples.ORDER_PII)).isEmpty();
        assertThat(text).contains("Приказ № 42", "30 000 рублей", "Продажи");
        assertThat(Tokens.renumber(text)).contains("премию [PERSON_1] (СНИЛС");

        // Те же файлы в том же порядке, mimetype первый и несжатый
        assertThat(entryNames(result)).isEqualTo(entryNames(source));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(result))) {
            ZipEntry first = zip.getNextEntry();
            assertThat(first.getName()).isEqualTo("mimetype");
            assertThat(first.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(new String(zip.readAllBytes())).isEqualTo("application/vnd.oasis.opendocument.text");
        }
    }

    @Test
    void odtContractIsAnonymized() throws IOException {
        byte[] result = new OdtProcessor(anonymizer).process(resource("contract.odt"), new PseudonymRegistry());

        assertThat(RawBytes.leaked(result, List.of("Иванов", "Петрова", "Кузнецов", "112-233-445 95", "45 06 123456",
                "hr@example.ru", "765-43-21"))).isEmpty();
        assertThat(extractor.toText(DocumentFormat.ODT, result)).contains("Трудовой договор № 15");
    }

    // ---------- pptx ----------

    @Test
    void pptxHasNoPiiInSlidesNotesLinksAndMetadata() throws IOException {
        byte[] result = new PowerPointProcessor(anonymizer).process(LargeSamples.presentationPptx(), new PseudonymRegistry());

        String text = extractor.toText(DocumentFormat.PPTX, result);
        assertThat(text).doesNotContain(LargeSamples.PRESENTATION_PII);
        assertThat(RawBytes.leaked(result, LargeSamples.PRESENTATION_PII)).isEmpty();
        assertThat(text).contains("Итоги квартала", "30 000 руб.", "1 250 000", "Лучшие сотрудники");

        // «Ивану Ивановичу Иванову» на первом слайде и «Иванов Иван Иванович» в таблице — одна метка
        String numbered = Tokens.renumber(text);
        assertThat(numbered).contains("Премию выплатить [PERSON_1] —");
        assertThat(numbered).containsPattern("\\[PERSON_1\\]\\s+Продажи");
    }

    @Test
    void pptxNameSplitAcrossRunsKeepsFormatting() throws IOException {
        byte[] result = new PowerPointProcessor(anonymizer).process(LargeSamples.presentationPptx(), new PseudonymRegistry());

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(result))) {
            XSLFSlide first = show.getSlides().getFirst();
            XSLFTextParagraph paragraph = first.getShapes().stream()
                    .filter(XSLFTextBox.class::isInstance).map(XSLFTextBox.class::cast)
                    .flatMap(box -> box.getTextParagraphs().stream())
                    .filter(p -> p.getText().startsWith("Благодарность"))
                    .findFirst().orElseThrow();
            assertThat(paragraph.getTextRuns()).hasSize(4);
            assertThat(paragraph.getTextRuns().get(1).getRawText()).startsWith("[PERSON_");
            assertThat(paragraph.getTextRuns().get(1).isBold()).isTrue();
            assertThat(paragraph.getTextRuns().get(2).getRawText()).isEmpty();
            assertThat(paragraph.getText()).endsWith(" за проект, тел. " + paragraph.getText()
                    .substring(paragraph.getText().indexOf("[PHONE_")));
            assertThat(show.getSlides()).hasSize(2);
        }
    }

    private static List<String> entryNames(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
