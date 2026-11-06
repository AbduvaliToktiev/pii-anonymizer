package ru.pii.anonymizer.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import ru.pii.anonymizer.SampleDocuments;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

class WordProcessorTest {

    private final WordProcessor processor = new WordProcessor(new TextAnonymizer(RussianPiiDetectors.all()));

    @Test
    void documentContainsNoPiiAfterProcessing() throws IOException {
        String text = DocumentText.word(processor.process(SampleDocuments.contractDocx(), new PseudonymRegistry()));

        assertThat(text).doesNotContain(SampleDocuments.PII_VALUES);
        assertThat(text).contains("Трудовой договор № 15", "Оклад работника составляет 120 000 рублей");
        assertThat(text).contains("в лице генерального директора [PERSON_");
    }

    @Test
    void nameSplitAcrossFormattedRunsIsReplacedAndFormattingKept() throws IOException {
        byte[] result = processor.process(SampleDocuments.contractDocx(), new PseudonymRegistry());

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            XWPFParagraph paragraph = doc.getParagraphs().stream()
                    .filter(p -> p.getText().startsWith("Ответственный"))
                    .findFirst()
                    .orElseThrow();
            List<XWPFRun> runs = paragraph.getRuns();

            assertThat(paragraph.getText()).matches("Ответственный: \\[PERSON_\\d+], тел\\. \\[PHONE_\\d+]\\.");
            assertThat(runs.get(0).text()).startsWith("Ответственный: [PERSON_");
            assertThat(runs.get(1).text()).isEmpty();
            assertThat(runs.get(2).text()).isEmpty();
            assertThat(runs.get(1).isBold()).isTrue();
            assertThat(runs.get(2).isItalic()).isTrue();
        }
    }

    @Test
    void samePersonInTextAndTableGetsSameToken() throws IOException {
        String text = DocumentText.word(processor.process(SampleDocuments.contractDocx(), new PseudonymRegistry()));

        // «Петрова Анна Сергеевна» из разбитого на фрагменты абзаца и из таблицы — одна метка
        Matcher m = Pattern.compile("Ответственный: (\\[PERSON_\\d+])").matcher(text);
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        assertThat(text.split(Pattern.quote(token), -1)).hasSize(3);
    }

    @Test
    void authorIsRemovedFromMetadata() throws IOException {
        byte[] result = processor.process(SampleDocuments.contractDocx(), new PseudonymRegistry());

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            assertThat(doc.getProperties().getCoreProperties().getCreator()).isNullOrEmpty();
        }
    }
}
