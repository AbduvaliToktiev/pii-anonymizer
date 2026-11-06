package ru.pii.anonymizer.llm;

/**
 * Внешняя языковая модель. Получает только обезличенный текст.
 */
public interface LlmClient {

    LlmReply ask(String systemPrompt, String userMessage);

    /** Задан ли ключ: без него консоль только обезличивает и ничего не отправляет. */
    default boolean isConfigured() {
        return true;
    }
}
