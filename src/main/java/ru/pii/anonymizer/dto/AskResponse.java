package ru.pii.anonymizer.dto;

import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

import ru.pii.anonymizer.detect.PiiType;

/**
 * Ответ {@code POST /api/ask}.
 *
 * @param answer        ответ модели с подставленными настоящими данными
 * @param model         модель, которая ответила
 * @param found         сколько разных значений каждого типа обезличено
 * @param restored      сколько меток в ответе заменено настоящими значениями
 * @param unknownTokens метки из ответа, которых не было в запросе (оставлены как есть)
 * @param inputTokens   токены запроса
 * @param outputTokens  токены ответа
 * @param truncated     ответ обрезан по лимиту max-tokens
 * @param sentToLlm     что увидела модель — только при {@code showAnonymized=true}
 * @param llmAnswer     ответ модели до восстановления — только при {@code showAnonymized=true}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskResponse(String answer, String model, Map<PiiType, Integer> found, int restored,
                          Set<String> unknownTokens, long inputTokens, long outputTokens, boolean truncated,
                          String sentToLlm, String llmAnswer) {
}
