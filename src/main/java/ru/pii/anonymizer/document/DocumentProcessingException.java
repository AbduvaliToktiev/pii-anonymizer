package ru.pii.anonymizer.document;

/** Файл нужного формата, но обработать его не удалось: повреждён, зашифрован, нет текстового слоя. */
public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(String message) {
        super(message);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
