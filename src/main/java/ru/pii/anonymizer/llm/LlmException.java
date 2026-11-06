package ru.pii.anonymizer.llm;

/** Модель недоступна или отказалась отвечать. Сообщение не содержит текста запроса. */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
