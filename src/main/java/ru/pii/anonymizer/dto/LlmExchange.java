package ru.pii.anonymizer.dto;

/**
 * Что отправлено в модель и что получено — для отладки, содержит только метки.
 *
 * @param sentToLlm документ и вопрос с метками вместо ПДн
 * @param llmAnswer ответ модели до восстановления
 */
public record LlmExchange(String sentToLlm, String llmAnswer) {
}
