package ru.pii.anonymizer.service;

import org.springframework.stereotype.Service;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.document.DocumentAnonymizer;
import ru.pii.anonymizer.document.UnsupportedDocumentException;
import ru.pii.anonymizer.dto.TextResult;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory;

/**
 * Обезличивание без отправки в модель: файл целиком в своём формате или произвольный текст.
 * Метки вычисляются ключом сервиса — те же коды, что в {@code /api/ask} и после перезапуска.
 */
@Service
public class AnonymizationService {

    private final DocumentAnonymizer documentAnonymizer;
    private final TextAnonymizer textAnonymizer;
    private final PseudonymRegistryFactory registries;

    public AnonymizationService(DocumentAnonymizer documentAnonymizer, TextAnonymizer textAnonymizer,
                                PseudonymRegistryFactory registries) {
        this.documentAnonymizer = documentAnonymizer;
        this.textAnonymizer = textAnonymizer;
        this.registries = registries;
    }

    /** Обезличенный файл того же формата, его имя и статистика. */
    public DocumentAnonymizer.Result anonymizeFile(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw new UnsupportedDocumentException("Файл пустой");
        }
        return documentAnonymizer.anonymize(fileName, content, registries.create());
    }

    public TextResult anonymizeText(String text) {
        PseudonymRegistry registry = registries.create();
        String anonymized = textAnonymizer.anonymize(text == null ? "" : text, registry);
        return new TextResult(anonymized, registry.summary());
    }
}
