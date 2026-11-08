package ru.pii.anonymizer.dto;

import ru.pii.anonymizer.pseudonym.AnonymizationSummary;

/**
 * Результат обезличивания текста ({@code AnonymizationService.anonymizeText}).
 *
 * @param text    текст с метками вместо ПДн
 * @param summary что найдено и заменено
 */
public record TextResult(String text, AnonymizationSummary summary) {
}
