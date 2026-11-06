package ru.pii.anonymizer.llm;

/**
 * Ответ модели.
 *
 * @param truncated ответ обрезан по лимиту {@code max-tokens}
 */
public record LlmReply(String text, String model, long inputTokens, long outputTokens, boolean truncated) {
}
