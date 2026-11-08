package ru.pii.anonymizer.dto;

import java.util.Set;

import ru.pii.anonymizer.pseudonym.AnonymizationSummary;

/**
 * Результат вопроса к модели ({@code AskService.ask}).
 *
 * @param answer        ответ модели с подставленными настоящими данными
 * @param model         модель, которая ответила
 * @param summary       что найдено и заменено
 * @param restored      сколько меток в ответе заменено настоящими значениями
 * @param unknownTokens метки из ответа, которых не было в запросе
 * @param inputTokens   токены запроса
 * @param outputTokens  токены ответа
 * @param truncated     ответ обрезан по лимиту max-tokens
 * @param exchange      что ушло в модель и что она ответила, с метками
 */
public record AskResult(String answer, String model, AnonymizationSummary summary, int restored,
                        Set<String> unknownTokens, long inputTokens, long outputTokens, boolean truncated,
                        LlmExchange exchange) {
}
