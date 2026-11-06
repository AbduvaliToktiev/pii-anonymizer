package ru.pii.anonymizer.document;

import java.io.IOException;
import java.util.List;

import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.pii.anonymizer.pseudonym.AnonymizationSummary;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;

/**
 * Точка входа обезличивания файла: определяет формат, выбирает обработчик,
 * возвращает новый файл и статистику. В лог пишется только статистика — без значений.
 */
@Service
public class DocumentAnonymizer {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnonymizer.class);

    /** Результат обработки. Таблица соответствия меток пока остаётся на сервере и не возвращается. */
    public record Result(byte[] content, String fileName, DocumentFormat format, AnonymizationSummary summary) {
    }

    private final List<DocumentProcessor> processors;
    private final PseudonymRegistryFactory registries;

    public DocumentAnonymizer(List<DocumentProcessor> processors, PseudonymRegistryFactory registries) {
        this.processors = List.copyOf(processors);
        this.registries = registries;
    }

    public Result anonymize(String fileName, byte[] content) {
        return anonymize(fileName, content, registries.create());
    }

    /**
     * Обезличивание с внешним реестром меток: так файл и текст запроса к LLM
     * получают общие метки, и «Иванов» в вопросе совпадёт с «Ивановым» в таблице.
     */
    public Result anonymize(String fileName, byte[] content, PseudonymRegistry registry) {
        DocumentFormat format = DocumentFormat.detect(fileName, content);
        DocumentProcessor processor = processors.stream()
                .filter(p -> p.supports(format))
                .findFirst()
                .orElseThrow(() -> new UnsupportedDocumentException("Нет обработчика для формата " + format));

        byte[] result;
        try {
            result = processor.process(content, registry);
        } catch (IOException | UnsupportedFileFormatException | POIXMLException e) {
            throw new DocumentProcessingException("Не удалось прочитать файл: он повреждён или имеет неверный формат", e);
        }

        AnonymizationSummary summary = registry.summary();
        log.info("Обработан {}: {} КБ, замен {}, найдено [{}]",
                format, content.length / 1024, summary.totalReplacements(), summary.toHeaderValue());
        return new Result(result, "anonymized_" + baseName(fileName) + "." + format.extension(), format, summary);
    }

    private static String baseName(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
