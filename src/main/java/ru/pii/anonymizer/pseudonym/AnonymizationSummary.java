package ru.pii.anonymizer.pseudonym;

import java.util.Map;
import java.util.stream.Collectors;

import ru.pii.anonymizer.detect.PiiType;

/**
 * Статистика обработки без самих значений — её безопасно логировать и отдавать клиенту.
 *
 * @param replacements сколько раз заменялись данные каждого типа
 * @param unique       сколько разных значений каждого типа найдено
 */
public record AnonymizationSummary(Map<PiiType, Integer> replacements, Map<PiiType, Integer> unique) {

    public AnonymizationSummary {
        replacements = Map.copyOf(replacements);
        unique = Map.copyOf(unique);
    }

    public int totalReplacements() {
        return replacements.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Компактная строка для HTTP-заголовка и логов: {@code PERSON=2, SNILS=2}. */
    public String toHeaderValue() {
        return unique.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
