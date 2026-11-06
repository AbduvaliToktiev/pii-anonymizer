package ru.pii.anonymizer.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание PDF.
 *
 * <p>Если нарисовать чёрный прямоугольник поверх ПДн, текст под ним остаётся в файле
 * и копируется. Надёжно удалить текст из произвольного PDF без коммерческих библиотек
 * сложно, поэтому сервис <b>собирает новый PDF</b>: извлекает текст каждой страницы,
 * обезличивает его и записывает на новые страницы. Исходных данных в результате гарантированно нет.
 *
 * <p>Цена — теряется вёрстка: картинки, таблицы как графика, шрифты оформления.
 * Для передачи в LLM это не важно — ей нужен текст. Сканы без текстового слоя
 * отклоняются: для них нужен OCR.
 */
@Component
public class PdfProcessor implements DocumentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessor.class);

    private static final List<Path> SYSTEM_FONTS = Stream.of(
                    "C:/Windows/Fonts/arial.ttf",
                    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/dejavu/DejaVuSans.ttf",
                    "/usr/share/fonts/TTF/DejaVuSans.ttf",
                    "/System/Library/Fonts/Supplemental/Arial.ttf",
                    "/Library/Fonts/Arial.ttf")
            .map(Path::of)
            .toList();

    private static final float MARGIN = 50f;

    private final TextAnonymizer anonymizer;
    private final float fontSize;
    private final Optional<Path> fontPath;

    public PdfProcessor(TextAnonymizer anonymizer, PdfProperties properties) {
        this.anonymizer = anonymizer;
        this.fontSize = properties.fontSize();
        this.fontPath = resolveFont(properties.fontPath());
        fontPath.ifPresentOrElse(
                path -> log.info("Шрифт для PDF: {}", path),
                () -> log.warn("Не найден TTF-шрифт с кириллицей — обработка PDF будет недоступна. "
                        + "Укажите путь в свойстве anonymizer.pdf.font-path"));
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PDF;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        Path font = fontPath.orElseThrow(() -> new DocumentProcessingException(
                "Не настроен шрифт для PDF: укажите anonymizer.pdf.font-path"));

        List<String> pages = extractPages(content);
        if (pages.stream().allMatch(String::isBlank)) {
            throw new DocumentProcessingException(
                    "В PDF нет текстового слоя (похоже на скан). Распознавание (OCR) пока не поддерживается");
        }

        try (PDDocument target = new PDDocument()) {
            PageWriter writer = new PageWriter(target, PDType0Font.load(target, font.toFile()), fontSize);
            for (String page : pages) {
                writer.writePage(anonymizer.anonymize(page, registry));
            }
            target.getDocumentInformation().setProducer("pii-anonymizer");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            target.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> extractPages(byte[] content) throws IOException {
        try (PDDocument source = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            List<String> pages = new ArrayList<>(source.getNumberOfPages());
            for (int page = 1; page <= source.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(source));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new DocumentProcessingException("PDF защищён паролем — снимите защиту и загрузите снова", e);
        }
    }

    private static Optional<Path> resolveFont(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!Files.isReadable(path)) {
                throw new IllegalStateException("Шрифт из anonymizer.pdf.font-path не найден: " + path);
            }
            return Optional.of(path);
        }
        return SYSTEM_FONTS.stream().filter(Files::isReadable).findFirst();
    }

    /** Пишет текст на страницы A4 с переносом строк по ширине. */
    private static final class PageWriter {

        private final PDDocument document;
        private final PDFont font;
        private final float fontSize;
        private final float leading;
        private final float maxWidth;
        private final Map<Integer, Boolean> glyphCache = new HashMap<>();

        PageWriter(PDDocument document, PDFont font, float fontSize) {
            this.document = document;
            this.font = font;
            this.fontSize = fontSize;
            this.leading = fontSize * 1.4f;
            this.maxWidth = PDRectangle.A4.getWidth() - 2 * MARGIN;
        }

        /** Одна исходная страница — минимум одна новая; длинный текст переносится на следующие. */
        void writePage(String text) throws IOException {
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                lines.addAll(wrap(sanitize(line)));
            }
            while (!lines.isEmpty() && lines.getLast().isBlank()) {
                lines.removeLast();
            }

            int linesPerPage = (int) ((PDRectangle.A4.getHeight() - 2 * MARGIN) / leading);
            int index = 0;
            do {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, fontSize);
                    stream.setLeading(leading);
                    stream.newLineAtOffset(MARGIN, PDRectangle.A4.getHeight() - MARGIN - fontSize);
                    for (int n = 0; n < linesPerPage && index < lines.size(); n++, index++) {
                        stream.showText(lines.get(index));
                        stream.newLine();
                    }
                    stream.endText();
                }
            } while (index < lines.size());
        }

        private List<String> wrap(String line) throws IOException {
            if (width(line) <= maxWidth) {
                return List.of(line);
            }
            List<String> result = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : line.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (width(candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                // Слово длиннее строки — режем по символам
                for (int cp : word.codePoints().toArray()) {
                    String next = current + Character.toString(cp);
                    if (width(next) > maxWidth && !current.isEmpty()) {
                        result.add(current.toString());
                        current.setLength(0);
                    }
                    current.appendCodePoint(cp);
                }
            }
            if (!current.isEmpty()) {
                result.add(current.toString());
            }
            return result;
        }

        private float width(String text) throws IOException {
            return font.getStringWidth(text) / 1000f * fontSize;
        }

        /** Символы, которых нет в шрифте, заменяются на «?», управляющие — на пробелы. */
        private String sanitize(String line) {
            StringBuilder sb = new StringBuilder(line.length());
            line.codePoints().forEach(cp -> {
                if (cp == '\t') {
                    sb.append("    ");
                } else if (Character.isISOControl(cp)) {
                    sb.append(' ');
                } else if (glyphCache.computeIfAbsent(cp, this::canEncode)) {
                    sb.appendCodePoint(cp);
                } else {
                    sb.append('?');
                }
            });
            return sb.toString();
        }

        private boolean canEncode(int codePoint) {
            try {
                font.encode(Character.toString(codePoint));
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        }
    }
}
