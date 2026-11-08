package ru.pii.anonymizer.dto;

import java.util.Map;

import ru.pii.anonymizer.detect.PiiType;

/**
 * Ответ {@code POST /api/anonymize/text}.
 *
 * @param text         текст с метками вместо ПДн
 * @param found        сколько разных значений каждого типа найдено
 * @param replacements сколько всего фрагментов заменено
 */
public record TextResponse(String text, Map<PiiType, Integer> found, int replacements) {
}
