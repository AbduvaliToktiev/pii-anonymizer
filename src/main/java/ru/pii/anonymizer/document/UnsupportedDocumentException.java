package ru.pii.anonymizer.document;

/** Файл неподдерживаемого формата или не соответствует заявленному расширению. */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}
