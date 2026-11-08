package ru.pii.anonymizer.dto;

import ru.pii.anonymizer.pseudonym.AnonymizationSummary;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличенный вопрос, ещё не отправленный в модель ({@code AskService.prepare}).
 *
 * @param message          всё, что уйдёт в модель: документ и вопрос
 * @param anonymizedPrompt только вопрос, уже с метками
 * @param registry         таблица «метка → значение» этого запроса; содержит ПДн, наружу не передаётся
 */
public record PreparedAsk(String message, String anonymizedPrompt, PseudonymRegistry registry) {

    public AnonymizationSummary summary() {
        return registry.summary();
    }
}
