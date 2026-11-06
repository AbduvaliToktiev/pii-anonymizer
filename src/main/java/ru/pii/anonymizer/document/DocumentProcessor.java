package ru.pii.anonymizer.document;

import java.io.IOException;

import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивает документ одного формата и возвращает новый файл того же формата.
 * Все значения одного документа заменяются через общий {@link PseudonymRegistry}.
 */
public interface DocumentProcessor {

    boolean supports(DocumentFormat format);

    byte[] process(byte[] content, PseudonymRegistry registry) throws IOException;
}
