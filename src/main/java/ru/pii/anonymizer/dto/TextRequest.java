package ru.pii.anonymizer.dto;

/**
 * Запрос {@code POST /api/anonymize/text}.
 *
 * @param text текст, в котором нужно обезличить ПДн
 */
public record TextRequest(String text) {
}
