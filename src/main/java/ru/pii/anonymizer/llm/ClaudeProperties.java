package ru.pii.anonymizer.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки Claude для /api/ask. Ключ API берётся из переменной
 * окружения {@code ANTHROPIC_API_KEY} и в настройках не хранится.
 *
 * @param model     идентификатор модели
 * @param maxTokens максимальная длина ответа
 * @param effort    глубина рассуждений: low, medium, high, xhigh, max
 */
@ConfigurationProperties("anonymizer.claude")
public record ClaudeProperties(String model, Long maxTokens, String effort) {

    public ClaudeProperties {
        if (model == null || model.isBlank()) {
            model = "claude-opus-5-5";
        }
        if (maxTokens == null || maxTokens <= 0) {
            maxTokens = 16_000L;
        }
        if (effort == null || effort.isBlank()) {
            effort = "medium";
        }
    }
}
