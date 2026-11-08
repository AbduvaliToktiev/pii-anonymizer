package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import ru.pii.anonymizer.detect.Finding;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание PDF на месте: шрифты, вёрстка, картинки и таблицы остаются как были.
 *
 * <ol>
 *   <li>Текст собирается из команд вывода текста (Tj, TJ, ', ") страницы и форм, которые она рисует,
 *       с учётом положения каждого символа на странице: символ вплотную — то же слово, небольшой зазор —
 *       пробел, большой зазор или другая строка — перенос. Так слово, разбитое Word на фрагменты
 *       с разным оформлением, остаётся словом, а ячейки таблицы не склеиваются в «ФИО».</li>
 *   <li>В этом тексте ищутся ПДн; символы найденного значения удаляются из потока страницы —
 *       в файле их больше нет, ни видимых, ни скрытых.</li>
 *   <li>На их место ставится метка. Метка рисуется стандартным шрифтом Helvetica: во встроенных шрифтах
 *       документа обычно есть только буквы самого документа, а латиницы, цифр и скобок метки в них может
 *       не быть. Helvetica есть в любой программе просмотра PDF и в файл не встраивается.
 *       Остальной текст остаётся в своих шрифтах.</li>
 *   <li>Готовый файл проверяется тем же способом: если детекторы в нём ещё что-то находят,
 *       файл не отдаётся — лучше ошибка, чем утечка.</li>
 * </ol>
 *
 * <p>Обезличиваются также свойства документа (автор стирается, название и тема — обезличиваются),
 * метаданные XMP (удаляются), закладки, примечания, ссылки mailto и значения полей форм.
 * Метка обычно длиннее исходного текста, поэтому остаток строки может сдвинуться вправо.
 * Сканы без текстового слоя отклоняются: для них нужно распознавание (OCR).
 */
@Component
public class PdfProcessor implements DocumentProcessor {

    /** Сдвиг внутри TJ (в тысячных долях кегля) больше этого — пробел между словами, ещё больше — табуляция. */
    private static final float WORD_GAP = 200;
    private static final float COLUMN_GAP = 2500;
    /** Сдвиг Td по горизонтали больше стольких кеглей — переход в соседнюю ячейку. */
    private static final float CELL_JUMP_SIZES = 3;
    /** Зазор между символами (в кеглях): больше первого — пробел, больше второго — соседняя ячейка. */
    private static final float SPACE_GAP = 0.15f;
    private static final float CELL_GAP = 1.0f;

    private final TextAnonymizer anonymizer;

    public PdfProcessor(TextAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PDF;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.isEncrypted()) {
                doc.setAllSecurityToBeRemoved(true);
            }
            boolean hasText = false;
            Set<COSBase> visited = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                hasText |= new StreamEditor(doc, page, page, page.getResources(), visited).anonymize(doc, registry);
                processAnnotations(doc, page, registry, visited);
            }
            if (!hasText) {
                throw new DocumentProcessingException(
                        "В PDF нет текстового слоя (похоже на скан). Распознавание (OCR) пока не поддерживается");
            }
            processMetadata(doc, registry);
            processOutline(doc.getDocumentCatalog().getDocumentOutline(), registry);
            processForm(doc.getDocumentCatalog().getAcroForm(), registry);

            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
            doc.save(out);
            byte[] result = out.toByteArray();
            verify(result);
            return result;
        } catch (InvalidPasswordException e) {
            throw new DocumentProcessingException("PDF защищён паролем — снимите защиту и загрузите снова", e);
        }
    }

    /** Повторная проверка готового файла тем же разбором: ни одного значения, которое нашли бы детекторы. */
    private void verify(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSBase> visited = new HashSet<>();
            for (PDPage page : doc.getPages()) {
                if (new StreamEditor(doc, page, page, page.getResources(), visited).hasPii()) {
                    throw new DocumentProcessingException("Не удалось убрать все ПДн из PDF, не меняя его вёрстку: "
                            + "часть текста записана так, что её нельзя заменить на месте. Файл не отдан");
                }
            }
        }
    }

    // ---------- поток страницы ----------

    /** Символ, выведенный на страницу: байты кода в шрифте, его текст и ширина (в тысячных долях кегля). */
    private record Glyph(byte[] code, String text, float width) {
    }

    /**
     * Метка на месте удалённого значения.
     *
     * @param scale горизонтальное масштабирование метки (Tz, в процентах), чтобы она заняла ровно место
     *              исходного текста; {@code null} — без масштабирования (ширина исходного текста неизвестна)
     */
    private record Insert(String token, Float scale) {
    }

    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    /** Строка из команды вывода текста: целиком Tj, ', " или один элемент массива TJ. */
    private static final class Shown {
        /** Что стоит в тексте перед строкой: ничего, пробел или перенос. */
        String before;
        final List<Glyph> glyphs;
        final boolean[] removed;
        /** Метка, которую нужно вставить перед символом с этим номером. */
        final Map<Integer, Insert> insertBefore = new LinkedHashMap<>();
        /** Положение и ширина символов на странице; {@code null}, если геометрию определить не удалось. */
        float[] x;
        float[] y;
        float[] w;
        float[] height;

        Shown(String before, List<Glyph> glyphs) {
            this.before = before;
            this.glyphs = glyphs;
            this.removed = new boolean[glyphs.size()];
        }

        boolean changed() {
            if (!insertBefore.isEmpty()) {
                return true;
            }
            for (boolean flag : removed) {
                if (flag) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Команда вывода текста с её строками и шрифтом в момент вывода. */
    private static final class Show {
        final String operator;
        final List<COSBase> operands;
        final COSName font;
        final float size;
        /** Горизонтальное масштабирование (Tz) в момент вывода. */
        final float scale;
        final List<Shown> strings = new ArrayList<>();
        /** Номер строки в массиве TJ (для Tj, ', " — -1). */
        final List<Integer> arrayIndex = new ArrayList<>();

        Show(String operator, List<COSBase> operands, COSName font, float size, float scale) {
            this.operator = operator;
            this.operands = operands;
            this.font = font;
            this.size = size;
            this.scale = scale;
        }

        boolean changed() {
            return strings.stream().anyMatch(Shown::changed);
        }
    }

    /** Разбирает поток страницы или формы: собирает текст, удаляет ПДн и вставляет метки. */
    private final class StreamEditor {

        private final PDContentStream stream;
        private final PDDocument document;
        private final PDPage page;
        private final PDResources resources;
        private final Set<COSBase> visited;
        private final List<Object> tokens;
        private final List<Show> shows = new ArrayList<>();
        private final Map<Integer, Show> showAt = new LinkedHashMap<>();
        private final List<COSName> forms = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        /** Позиция в тексте → {команда, строка, символ}; {@code null} — пробел или перенос между строками. */
        private final List<int[]> owner = new ArrayList<>();

        /** @param page страница, на которой рисуется поток: по ней вычисляется положение символов */
        StreamEditor(PDDocument document, PDContentStream stream, PDPage page, PDResources resources, Set<COSBase> visited)
                throws IOException {
            this.document = document;
            this.stream = stream;
            this.page = page;
            this.resources = resources == null ? new PDResources() : resources;
            this.visited = visited;
            this.tokens = new PDFStreamParser(stream).parse();
            analyze();
            placeByGeometry();
            buildText();
        }

        /** Обезличивает поток и вложенные формы. {@code true} — в них есть текст. */
        boolean anonymize(PDDocument doc, PseudonymRegistry registry) throws IOException {
            boolean hasText = !text.toString().isBlank();
            List<Finding> findings = anonymizer.detect(text.toString());
            if (!findings.isEmpty()) {
                for (Finding f : findings) {
                    mark(f, registry.tokenFor(f.type(), f.value()));
                }
                write(doc, rewrite());
            }
            for (PDFormXObject form : forms()) {
                hasText |= new StreamEditor(document, form, page, resourcesOf(form), visited).anonymize(doc, registry);
            }
            return hasText;
        }

        /** Найдут ли детекторы что-нибудь в потоке или вложенных формах. */
        boolean hasPii() throws IOException {
            if (!anonymizer.detect(text.toString()).isEmpty()) {
                return true;
            }
            for (PDFormXObject form : forms()) {
                if (new StreamEditor(document, form, page, resourcesOf(form), visited).hasPii()) {
                    return true;
                }
            }
            return false;
        }

        private List<PDFormXObject> forms() throws IOException {
            List<PDFormXObject> result = new ArrayList<>();
            for (COSName name : forms) {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject form && visited.add(form.getCOSObject())) {
                    result.add(form);
                }
            }
            return result;
        }

        private PDResources resourcesOf(PDFormXObject form) {
            return form.getResources() == null ? resources : form.getResources();
        }

        /** Команды вывода текста, их шрифт, строки и разделители между ними; вызываемые формы. */
        private void analyze() throws IOException {
            Deque<Object[]> stack = new ArrayDeque<>();
            COSName font = null;
            float size = 10;
            float scale = 100;
            String pending = "\n";
            List<COSBase> operands = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                Object token = tokens.get(i);
                if (!(token instanceof Operator op)) {
                    operands.add((COSBase) token);
                    continue;
                }
                switch (op.getName()) {
                    case "q" -> stack.push(new Object[] {font, size, scale});
                    case "Q" -> {
                        if (!stack.isEmpty()) {
                            Object[] state = stack.pop();
                            font = (COSName) state[0];
                            size = (Float) state[1];
                            scale = (Float) state[2];
                        }
                    }
                    case "Tz" -> {
                        if (!operands.isEmpty() && operands.getFirst() instanceof COSNumber number) {
                            scale = number.floatValue();
                        }
                    }
                    case "Tf" -> {
                        if (operands.size() >= 2 && operands.get(0) instanceof COSName name
                                && operands.get(1) instanceof COSNumber number) {
                            font = name;
                            size = number.floatValue();
                        }
                    }
                    case "BT", "ET", "Tm", "T*" -> pending = "\n";
                    case "Td", "TD" -> {
                        if (operands.size() >= 2 && operands.get(0) instanceof COSNumber tx
                                && operands.get(1) instanceof COSNumber ty) {
                            boolean newLine = Math.abs(ty.floatValue()) > 0.01
                                    || Math.abs(tx.floatValue()) > CELL_JUMP_SIZES * Math.max(Math.abs(size), 1e-3f);
                            pending = strongest(pending, newLine ? "\n" : " ");
                        }
                    }
                    case "Do" -> {
                        if (!operands.isEmpty() && operands.getFirst() instanceof COSName name) {
                            forms.add(name);
                        }
                        pending = "\n";
                    }
                    case "Tj", "'", "\"", "TJ" -> {
                        if (!op.getName().equals("Tj") && !op.getName().equals("TJ")) {
                            pending = "\n";
                        }
                        PDFont pdFont = font == null ? null : resources.getFont(font);
                        if (pdFont != null) {
                            Show show = new Show(op.getName(), List.copyOf(operands), font, size, scale);
                            collectStrings(show, pdFont, pending);
                            shows.add(show);
                            showAt.put(i, show);
                            pending = "";
                        }
                    }
                    default -> {
                    }
                }
                operands.clear();
            }
        }

        private static String strongest(String a, String b) {
            return a.equals("\n") || b.equals("\n") ? "\n" : a.equals(" ") || b.equals(" ") ? " " : "";
        }

        private void collectStrings(Show show, PDFont font, String before) throws IOException {
            if (show.operator.equals("TJ")) {
                if (show.operands.isEmpty() || !(show.operands.getFirst() instanceof COSArray array)) {
                    return;
                }
                String gap = before;
                for (int k = 0; k < array.size(); k++) {
                    COSBase element = array.getObject(k);
                    if (element instanceof COSString string) {
                        show.strings.add(new Shown(gap, decode(font, string)));
                        show.arrayIndex.add(k);
                        gap = "";
                    } else if (element instanceof COSNumber number) {
                        float shift = number.floatValue();
                        gap = strongest(gap, shift <= -COLUMN_GAP ? "\n" : shift <= -WORD_GAP ? " " : "");
                    }
                }
            } else if (!show.operands.isEmpty() && show.operands.getLast() instanceof COSString string) {
                show.strings.add(new Shown(before, decode(font, string)));
                show.arrayIndex.add(-1);
            }
        }

        private List<Glyph> decode(PDFont font, COSString string) throws IOException {
            byte[] bytes = string.getBytes();
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            List<Glyph> glyphs = new ArrayList<>();
            while (in.available() > 0) {
                int before = bytes.length - in.available();
                int code = font.readCode(in);
                int after = bytes.length - in.available();
                String unicode = font.toUnicode(code);
                byte[] raw = new byte[after - before];
                System.arraycopy(bytes, before, raw, 0, raw.length);
                glyphs.add(new Glyph(raw, unicode == null ? "�" : unicode, font.getWidth(code)));
            }
            return glyphs;
        }

        /**
         * Разделители между строками по положению символов на странице. Если символы не удалось
         * сопоставить с командами потока один к одному (поток не рисуется на странице, шрифт без
         * таблицы Unicode), остаются разделители по командам.
         */
        private void placeByGeometry() throws IOException {
            if (page == null || shows.isEmpty()) {
                return;
            }
            Locator locator = new Locator(stream);
            int number = document.getPages().indexOf(page) + 1;
            if (number == 0) {
                return;
            }
            locator.setStartPage(number);
            locator.setEndPage(number);
            locator.getText(document);
            List<TextPosition> positions = locator.positions;
            int total = shows.stream().flatMap(show -> show.strings.stream()).mapToInt(shown -> shown.glyphs.size()).sum();
            if (positions.size() != total) {
                return;
            }
            int index = 0;
            TextPosition previous = null;
            for (Show show : shows) {
                for (Shown shown : show.strings) {
                    if (shown.glyphs.isEmpty()) {
                        continue;
                    }
                    TextPosition first = positions.get(index);
                    shown.before = previous == null ? "\n" : separator(previous, first);
                    int n = shown.glyphs.size();
                    shown.x = new float[n];
                    shown.y = new float[n];
                    shown.w = new float[n];
                    shown.height = new float[n];
                    for (int g = 0; g < n; g++) {
                        TextPosition position = positions.get(index + g);
                        shown.x[g] = position.getXDirAdj();
                        shown.y[g] = position.getYDirAdj();
                        shown.w[g] = position.getWidthDirAdj();
                        shown.height[g] = Math.max(Math.max(position.getFontSizeInPt(), position.getHeightDir()), 1f);
                    }
                    index += n;
                    previous = positions.get(index - 1);
                }
            }
        }

        private static String separator(TextPosition a, TextPosition b) {
            float size = Math.max(Math.max(b.getFontSizeInPt(), b.getHeightDir()), 1f);
            if (Math.abs(b.getYDirAdj() - a.getYDirAdj()) > 0.5f * size) {
                return "\n";
            }
            float gap = b.getXDirAdj() - (a.getXDirAdj() + a.getWidthDirAdj());
            if (gap > CELL_GAP * size || gap < -0.5f * size) {
                return "\n";
            }
            return gap > SPACE_GAP * size ? " " : "";
        }

        private void buildText() {
            for (int s = 0; s < shows.size(); s++) {
                List<Shown> strings = shows.get(s).strings;
                for (int t = 0; t < strings.size(); t++) {
                    Shown shown = strings.get(t);
                    for (int c = 0; c < shown.before.length(); c++) {
                        text.append(shown.before.charAt(c));
                        owner.add(null);
                    }
                    for (int g = 0; g < shown.glyphs.size(); g++) {
                        String glyph = shown.glyphs.get(g).text();
                        for (int c = 0; c < glyph.length(); c++) {
                            text.append(glyph.charAt(c));
                            owner.add(new int[] {s, t, g});
                        }
                    }
                }
            }
        }

        /** Символы найденного значения — на удаление, перед первым из них — метка. */
        private void mark(Finding finding, String token) {
            int[] first = null;
            int[] last = null;
            for (int p = finding.start(); p < finding.end(); p++) {
                int[] o = owner.get(p);
                if (o != null) {
                    first = first == null ? o : first;
                    last = o;
                }
            }
            if (first == null) {
                return;
            }
            shows.get(first[0]).strings.get(first[1]).insertBefore.putIfAbsent(first[2], new Insert(token, fit(first, last, token)));
            for (int s = first[0]; s <= last[0]; s++) {
                List<Shown> strings = shows.get(s).strings;
                int tFrom = s == first[0] ? first[1] : 0;
                int tTo = s == last[0] ? last[1] : strings.size() - 1;
                for (int t = tFrom; t <= tTo; t++) {
                    Shown shown = strings.get(t);
                    int gFrom = s == first[0] && t == first[1] ? first[2] : 0;
                    int gTo = s == last[0] && t == last[1] ? last[2] : shown.glyphs.size() - 1;
                    for (int g = gFrom; g <= gTo; g++) {
                        shown.removed[g] = true;
                    }
                }
            }
        }

        /**
         * Масштаб метки, при котором она занимает ровно ширину удалённого текста первой строки значения:
         * текст после метки остаётся на своём месте и ничего не наезжает друг на друга.
         */
        private Float fit(int[] first, int[] last, String token) {
            Show show = shows.get(first[0]);
            Shown start = show.strings.get(first[1]);
            if (start.x == null) {
                return null;
            }
            float x0 = start.x[first[2]];
            float y0 = start.y[first[2]];
            float lineHeight = start.height[first[2]];
            float right = x0;
            float pageWidth = 0;
            float fontWidth = 0;
            for (int s = first[0]; s <= last[0]; s++) {
                List<Shown> strings = shows.get(s).strings;
                int tFrom = s == first[0] ? first[1] : 0;
                int tTo = s == last[0] ? last[1] : strings.size() - 1;
                for (int t = tFrom; t <= tTo; t++) {
                    Shown shown = strings.get(t);
                    if (shown.x == null) {
                        continue;
                    }
                    int gFrom = s == first[0] && t == first[1] ? first[2] : 0;
                    int gTo = s == last[0] && t == last[1] ? last[2] : shown.glyphs.size() - 1;
                    for (int g = gFrom; g <= gTo; g++) {
                        if (Math.abs(shown.y[g] - y0) > 0.5f * lineHeight) {
                            continue; // перенос: метка занимает место первой строки
                        }
                        right = Math.max(right, shown.x[g] + shown.w[g]);
                        pageWidth += shown.w[g];
                        fontWidth += shown.glyphs.get(g).width() / 1000f * shows.get(s).size;
                    }
                }
            }
            float target = right - x0;
            if (target <= 0 || pageWidth <= 0 || fontWidth <= 0) {
                return null;
            }
            try {
                // Во сколько раз ширина на странице больше ширины в единицах текста (Tm, CTM), без учёта текущего Tz
                float pagePerText = pageWidth / fontWidth / (show.scale / 100f);
                float natural = HELVETICA.getStringWidth(token) / 1000f * show.size * pagePerText;
                if (natural <= 0) {
                    return null;
                }
                return Math.max(10f, Math.min(100f, 100f * target / natural));
            } catch (IOException e) {
                return null;
            }
        }

        private void write(PDDocument doc, List<Object> rewritten) throws IOException {
            if (stream instanceof PDPage target) {
                PDStream contents = new PDStream(doc);
                try (OutputStream out = contents.createOutputStream(COSName.FLATE_DECODE)) {
                    new ContentStreamWriter(out).writeTokens(rewritten);
                }
                target.setContents(contents);
            } else if (stream instanceof PDFormXObject form) {
                try (OutputStream out = form.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
                    new ContentStreamWriter(out).writeTokens(rewritten);
                }
            }
        }

        /** Новый поток: изменённые команды вывода текста заменяются, остальное копируется как есть. */
        private List<Object> rewrite() {
            COSName helvetica = resources.add(HELVETICA);
            List<Object> out = new ArrayList<>(tokens.size());
            int operandsStart = 0;
            for (int i = 0; i < tokens.size(); i++) {
                if (!(tokens.get(i) instanceof Operator)) {
                    continue;
                }
                Show show = showAt.get(i);
                if (show == null || !show.changed()) {
                    out.addAll(tokens.subList(operandsStart, i + 1));
                } else {
                    emit(show, helvetica, out);
                }
                operandsStart = i + 1;
            }
            out.addAll(tokens.subList(operandsStart, tokens.size()));
            return out;
        }

        private void emit(Show show, COSName helvetica, List<Object> out) {
            switch (show.operator) {
                case "'" -> out.add(Operator.getOperator("T*"));
                case "\"" -> {
                    out.add(show.operands.get(0));
                    out.add(Operator.getOperator("Tw"));
                    out.add(show.operands.get(1));
                    out.add(Operator.getOperator("Tc"));
                    out.add(Operator.getOperator("T*"));
                }
                default -> {
                }
            }
            COSArray current = new COSArray();
            if (!show.operator.equals("TJ")) {
                // Tj выводится как TJ: в массиве можно оставить сдвиг на месте удалённых символов
                float skipped = emitPieces(show.strings.getFirst(), show, helvetica, out, current, 0);
                shift(current, skipped);
                flush(current, out);
                return;
            }
            // TJ: строки и сдвиги идут в массиве; метка рисуется отдельной командой, массив делится на части.
            // Невозмещённая ширина переходит от строки к строке: Word кладёт в TJ каждую букву отдельно.
            COSArray source = (COSArray) show.operands.getFirst();
            float skipped = 0;
            int next = 0;
            for (int k = 0; k < source.size(); k++) {
                if (next < show.arrayIndex.size() && show.arrayIndex.get(next) == k) {
                    skipped = emitPieces(show.strings.get(next), show, helvetica, out, current, skipped);
                    next++;
                } else if (source.getObject(k) instanceof COSNumber kerning && insideRemoved(show, next)) {
                    // Кернинг между удалёнными буквами удаляется вместе с ними; его сдвиг (−n) учитывается
                    skipped -= kerning.floatValue();
                } else {
                    current.add(source.getObject(k));
                }
            }
            shift(current, skipped);
            flush(current, out);
        }

        /** Сдвиг перед строкой {@code next} стоит между удалёнными символами: последним предыдущей и первым следующей. */
        private static boolean insideRemoved(Show show, int next) {
            if (next == 0 || next >= show.strings.size()) {
                return false;
            }
            Shown before = show.strings.get(next - 1);
            Shown after = show.strings.get(next);
            return !before.glyphs.isEmpty() && !after.glyphs.isEmpty()
                    && before.removed[before.glyphs.size() - 1] && after.removed[0] && after.insertBefore.get(0) == null;
        }

        /**
         * Выводит строку в массив TJ: оставшиеся символы — исходным шрифтом и байтами, метки — Helvetica
         * отдельной командой (массив перед ней выводится). На месте удалённых символов остаётся сдвиг
         * той же ширины, поэтому текст после значения стоит там же, где стоял.
         */
        /**
         * @param skipped ширина удалённых символов, ещё не возмещённая сдвигом (в тысячных долях кегля исходного
         *                шрифта); после метки уменьшается на её ширину
         * @return невозмещённая ширина после строки
         */
        private float emitPieces(Shown shown, Show show, COSName helvetica, List<Object> out, COSArray current,
                                 float skipped) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            for (int g = 0; g <= shown.glyphs.size(); g++) {
                Insert insert = shown.insertBefore.get(g);
                if (insert != null) {
                    keep(kept, current, out);
                    skipped = shift(current, skipped);
                    flush(current, out);
                    out.add(helvetica);
                    out.add(new COSFloat(show.size));
                    out.add(Operator.getOperator("Tf"));
                    if (insert.scale() != null) {
                        out.add(new COSFloat(insert.scale()));
                        out.add(Operator.getOperator("Tz"));
                    }
                    out.add(new COSString(insert.token()));
                    out.add(Operator.getOperator("Tj"));
                    if (insert.scale() != null) {
                        out.add(new COSFloat(show.scale));
                        out.add(Operator.getOperator("Tz"));
                    }
                    out.add(show.font);
                    out.add(new COSFloat(show.size));
                    out.add(Operator.getOperator("Tf"));
                    skipped = -tokenWidth(insert, show);
                }
                if (g < shown.glyphs.size()) {
                    if (shown.removed[g]) {
                        skipped += shown.glyphs.get(g).width();
                    } else {
                        if (skipped != 0) {
                            keep(kept, current, out);
                            skipped = shift(current, skipped);
                        }
                        kept.writeBytes(shown.glyphs.get(g).code());
                    }
                }
            }
            keep(kept, current, out);
            return skipped;
        }

        /**
         * Сдвиг вправо на невозмещённую ширину удалённых символов. Если метка оказалась шире удалённого
         * (значение продолжается в следующей команде), назад не сдвигаем — иначе текст наехал бы на метку.
         */
        private float shift(COSArray array, float skipped) {
            if (skipped > 0.5f) {
                array.add(new COSFloat(-skipped));
            }
            return 0;
        }

        /** Ширина метки в тысячных долях кегля исходного шрифта (с учётом масштаба метки и строки). */
        private float tokenWidth(Insert insert, Show show) {
            try {
                float scale = insert.scale() == null ? 100f : insert.scale();
                return HELVETICA.getStringWidth(insert.token()) * scale / Math.max(show.scale, 1f);
            } catch (IOException e) {
                return 0;
            }
        }

        /** Накопленные исходные байты — в массив TJ. */
        private void keep(ByteArrayOutputStream kept, COSArray array, List<Object> out) {
            if (kept.size() > 0) {
                array.add(new COSString(kept.toByteArray()));
                kept.reset();
            }
        }

        private void flush(COSArray array, List<Object> out) {
            if (array != null && array.size() > 0) {
                COSArray copy = new COSArray();
                for (int k = 0; k < array.size(); k++) {
                    copy.add(array.getObject(k));
                }
                out.add(copy);
                out.add(Operator.getOperator("TJ"));
                array.clear();
            }
        }
    }

    /**
     * Положение символов одного потока на странице: страница обрабатывается целиком (так вычисляются
     * координаты), но запоминаются только символы потока {@code target} — самой страницы или формы.
     */
    private static final class Locator extends PDFTextStripper {

        final List<TextPosition> positions = new ArrayList<>();
        private final COSBase target;
        private boolean recording;
        private boolean done;

        Locator(PDContentStream target) throws IOException {
            this.target = target instanceof PDPage page ? page.getCOSObject() : ((PDFormXObject) target).getCOSObject();
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            recording = page.getCOSObject() == target;
            super.processPage(page);
            recording = false;
        }

        @Override
        public void showForm(PDFormXObject form) throws IOException {
            if (done) {
                return;
            }
            if (form.getCOSObject() == target) {
                recording = true;
                super.showForm(form);
                recording = false;
                done = true;
            } else if (!recording) {
                // Ищем форму-цель внутри других форм; символы чужих форм не записываем
                super.showForm(form);
            } else {
                boolean outer = recording;
                recording = false;
                super.showForm(form);
                recording = outer;
            }
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (recording) {
                positions.add(text);
            }
        }
    }

    // ---------- остальные места, где бывает текст ----------

    private void processAnnotations(PDDocument doc, PDPage page, PseudonymRegistry registry, Set<COSBase> visited)
            throws IOException {
        for (PDAnnotation annotation : page.getAnnotations()) {
            String contents = annotation.getContents();
            if (contents != null && !contents.isBlank()) {
                annotation.setContents(anonymizer.anonymize(contents, registry));
            }
            if (annotation instanceof PDAnnotationLink link) {
                PDAction action = link.getAction();
                if (action instanceof PDActionURI uri && uri.getURI() != null && !anonymizer.detect(uri.getURI()).isEmpty()) {
                    link.setAction(null);
                }
            }
            PDAppearanceStream appearance = annotation.getNormalAppearanceStream();
            if (appearance != null && visited.add(appearance.getCOSObject())) {
                new StreamEditor(doc, appearance, page, appearance.getResources(), visited).anonymize(null, registry);
            }
        }
    }

    private void processMetadata(PDDocument doc, PseudonymRegistry registry) {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setAuthor("");
        if (info.getTitle() != null) {
            info.setTitle(anonymizer.anonymize(info.getTitle(), registry));
        }
        if (info.getSubject() != null) {
            info.setSubject(anonymizer.anonymize(info.getSubject(), registry));
        }
        if (info.getKeywords() != null) {
            info.setKeywords(anonymizer.anonymize(info.getKeywords(), registry));
        }
        // XMP дублирует автора и название в XML; проще удалить, чем обезличивать
        doc.getDocumentCatalog().setMetadata(null);
    }

    private void processOutline(PDOutlineNode node, PseudonymRegistry registry) {
        if (node == null) {
            return;
        }
        for (PDOutlineItem item : node.children()) {
            if (item.getTitle() != null) {
                item.setTitle(anonymizer.anonymize(item.getTitle(), registry));
            }
            processOutline(item, registry);
        }
    }

    /** Значения полей формы; их внешний вид обработан вместе с примечаниями страницы. */
    private void processForm(PDAcroForm form, PseudonymRegistry registry) {
        if (form == null) {
            return;
        }
        for (PDField field : form.getFieldTree()) {
            String value = field.getCOSObject().getString(COSName.V);
            if (value != null && !value.isBlank()) {
                field.getCOSObject().setString(COSName.V, anonymizer.anonymize(value, registry));
            }
        }
    }
}
