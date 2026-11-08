package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.springframework.stereotype.Component;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.detect.TextAnonymizer.Replacement;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание Word 97–2003 (doc) с сохранением форматирования.
 *
 * <p>Замена делается средствами формата: текст абзаца заменяется на месте ({@link Range#replaceText}),
 * поэтому таблицы, стили и оформление символов остаются; метка получает оформление первого
 * символа заменённого фрагмента. Абзацы и совпадения обходятся с конца — замена не сдвигает
 * позиции ещё не обработанного текста.
 *
 * <p>Колонтитулы, сноски, примечания и надписи хранятся в doc отдельными частями с фиксированными
 * границами, и POI не пересчитывает их при изменении длины текста. Поэтому там длина сохраняется:
 * метка ставится, если помещается в длину исходного фрагмента (остаток — пробелы), иначе фрагмент
 * закрашивается звёздочками той же длины.
 *
 * <p>Автор стирается и в свойствах документа, и в служебной таблице SttbfAssoc внутри файла, где Word
 * хранит автора, последнего редактора и путь к шаблону.
 */
@Component
public class WordDocProcessor implements DocumentProcessor {

    private final TextAnonymizer anonymizer;

    public WordDocProcessor(TextAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.DOC;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(DocMetadata.scrub(content)))) {
            processRange(document.getRange(), registry, false);
            processRange(document.getHeaderStoryRange(), registry, true);
            processRange(document.getFootnoteRange(), registry, true);
            processRange(document.getEndnoteRange(), registry, true);
            processRange(document.getCommentsRange(), registry, true);
            processRange(document.getMainTextboxRange(), registry, true);
            clearAuthor(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
            document.write(out);
            return out.toByteArray();
        } catch (EncryptedDocumentException e) {
            throw new DocumentProcessingException("Файл защищён паролем — снимите защиту и загрузите снова", e);
        }
    }

    /** @param keepLength {@code true} — часть с фиксированными границами: длина текста не должна меняться */
    private void processRange(Range range, PseudonymRegistry registry, boolean keepLength) {
        if (range == null || range.text().isEmpty()) {
            return;
        }
        for (int i = range.numParagraphs() - 1; i >= 0; i--) {
            Paragraph paragraph = range.getParagraph(i);
            String text = paragraph.text();
            List<Replacement> plan = anonymizer.plan(text, registry);
            for (Replacement r : plan.reversed()) {
                String original = text.substring(r.start(), r.end());
                String replacement = keepLength ? sameLength(r.token(), original.length()) : r.token();
                paragraph.replaceText(original, replacement, r.start());
            }
        }
    }

    private static String sameLength(String token, int length) {
        return token.length() <= length ? token + " ".repeat(length - token.length()) : "*".repeat(length);
    }

    private static void clearAuthor(HWPFDocument document) {
        SummaryInformation info = document.getSummaryInformation();
        if (info != null) {
            info.setAuthor("");
            info.setLastAuthor("");
        }
    }
}
