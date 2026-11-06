package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFAbstractFootnoteEndnote;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.springframework.stereotype.Component;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.detect.TextAnonymizer.Replacement;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание Word (docx) с сохранением форматирования.
 *
 * <p>Главная сложность: Word разбивает текст абзаца на фрагменты (runs) с разным оформлением,
 * и одно ФИО может оказаться в трёх фрагментах — «Ива», «нов Иван», «ович». Поэтому поиск
 * идёт по склеенному тексту всего абзаца, а замена раскладывается обратно по фрагментам:
 * метка пишется в первый фрагмент совпадения, остаток совпадения удаляется из следующих.
 * Оформление первого фрагмента переходит на метку, остальной текст сохраняет своё.
 *
 * <p>Обходятся основной текст, таблицы (включая вложенные), колонтитулы и сноски;
 * из метаданных удаляется автор.
 */
@Component
public class WordProcessor implements DocumentProcessor {

    private final TextAnonymizer anonymizer;

    public WordProcessor(TextAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.DOCX;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            processBody(document.getBodyElements(), registry);
            for (XWPFHeaderFooter header : document.getHeaderList()) {
                processBody(header.getBodyElements(), registry);
            }
            for (XWPFHeaderFooter footer : document.getFooterList()) {
                processBody(footer.getBodyElements(), registry);
            }
            for (XWPFAbstractFootnoteEndnote note : document.getFootnotes()) {
                processBody(note.getBodyElements(), registry);
            }
            for (XWPFAbstractFootnoteEndnote note : document.getEndnotes()) {
                processBody(note.getBodyElements(), registry);
            }
            clearAuthor(document);

            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
            document.write(out);
            return out.toByteArray();
        } catch (EncryptedDocumentException e) {
            throw new DocumentProcessingException("Файл защищён паролем — снимите защиту и загрузите снова", e);
        }
    }

    private void processBody(List<IBodyElement> elements, PseudonymRegistry registry) {
        for (IBodyElement element : elements) {
            switch (element) {
                case XWPFParagraph paragraph -> processParagraph(paragraph, registry);
                case XWPFTable table -> processTable(table, registry);
                default -> {
                    // SDT (элементы управления содержимым) пока не обрабатываются
                }
            }
        }
    }

    private void processTable(XWPFTable table, PseudonymRegistry registry) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                processBody(cell.getBodyElements(), registry);
            }
        }
    }

    void processParagraph(XWPFParagraph paragraph, PseudonymRegistry registry) {
        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            return;
        }

        String[] texts = new String[runs.size()];
        int[] offsets = new int[runs.size()];
        int[] lengths = new int[runs.size()];
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            texts[i] = textOf(runs.get(i));
            offsets[i] = full.length();
            lengths[i] = texts[i].length();
            full.append(texts[i]);
        }

        List<Replacement> plan = anonymizer.plan(full.toString(), registry);
        if (plan.isEmpty()) {
            return;
        }

        boolean[] changed = new boolean[runs.size()];
        // С конца: замена не сдвигает позиции более ранних совпадений
        for (Replacement r : plan.reversed()) {
            int first = runAt(offsets, lengths, r.start());
            int last = runAt(offsets, lengths, r.end() - 1);
            int startInFirst = r.start() - offsets[first];
            int endInLast = r.end() - offsets[last];

            if (first == last) {
                texts[first] = texts[first].substring(0, startInFirst) + r.token()
                        + texts[first].substring(endInLast);
            } else {
                texts[last] = texts[last].substring(endInLast);
                for (int i = first + 1; i < last; i++) {
                    texts[i] = "";
                    changed[i] = true;
                }
                texts[first] = texts[first].substring(0, startInFirst) + r.token();
                changed[last] = true;
            }
            changed[first] = true;
        }

        for (int i = 0; i < runs.size(); i++) {
            if (changed[i]) {
                setText(runs.get(i), texts[i]);
            }
        }
    }

    /**
     * Индекс фрагмента, в который попадает символ с позицией {@code position} склеенного текста.
     * Используются исходные длины: тексты фрагментов меняются по ходу замен.
     */
    private static int runAt(int[] offsets, int[] lengths, int position) {
        for (int i = 0; i < offsets.length; i++) {
            if (position >= offsets[i] && position < offsets[i] + lengths[i]) {
                return i;
            }
        }
        throw new IllegalStateException("Позиция " + position + " вне текста абзаца");
    }

    /** Весь текст фрагмента: внутри одного run может быть несколько элементов w:t. */
    private static String textOf(XWPFRun run) {
        CTText[] parts = run.getCTR().getTArray();
        if (parts.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (CTText part : parts) {
            sb.append(part.getStringValue());
        }
        return sb.toString();
    }

    /** Записывает текст в первый w:t фрагмента и очищает остальные, сохраняя оформление run. */
    private static void setText(XWPFRun run, String text) {
        CTText[] parts = run.getCTR().getTArray();
        if (parts.length == 0) {
            run.setText(text, 0);
            return;
        }
        run.setText(text, 0);
        for (int i = 1; i < parts.length; i++) {
            run.setText("", i);
        }
    }

    private static void clearAuthor(XWPFDocument document) {
        POIXMLProperties.CoreProperties core = document.getProperties().getCoreProperties();
        core.setCreator("");
        core.setLastModifiedByUser("");
    }
}
