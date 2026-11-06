package ru.pii.anonymizer.llm;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.MessageCreateParams;

import jakarta.annotation.PreDestroy;

/**
 * Обращение к Claude через официальный Java SDK.
 *
 * <p>Включён серверный fallback ({@code fallbacks: "default"}): если модель отклонит запрос
 * по соображениям безопасности, API сам повторит его на подходящей резервной модели.
 *
 * <p>Клиент создаётся при первом запросе: без ключа сервис запускается, и обезличивание
 * без отправки в LLM ({@code /api/anonymize}) продолжает работать.
 */
@Component
public class ClaudeLlmClient implements LlmClient {

    private final ClaudeProperties properties;
    private volatile AnthropicClient client;

    public ClaudeLlmClient(ClaudeProperties properties) {
        this.properties = properties;
    }

    @Override
    public LlmReply ask(String systemPrompt, String userMessage) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .system(systemPrompt)
                .addUserMessage(userMessage)
                .outputConfig(BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.of(properties.effort()))
                        .build())
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacks(BetaFallbacksParam.ofDefault())
                .build();

        BetaMessage message;
        try {
            message = client().beta().messages().create(params);
        } catch (UnauthorizedException e) {
            throw new LlmException("Claude API не принял ключ ANTHROPIC_API_KEY: он неверный или отозван, "
                    + "создайте новый в console.anthropic.com → API Keys", e);
        } catch (RateLimitException e) {
            throw new LlmException("Превышен лимит запросов к Claude API, повторите позже", e);
        } catch (AnthropicServiceException e) {
            throw new LlmException("Claude API вернул ошибку " + e.statusCode() + apiErrorMessage(e), e);
        } catch (AnthropicException e) {
            throw new LlmException("Не удалось обратиться к Claude API: " + e.getClass().getSimpleName(), e);
        }

        BetaStopReason stopReason = message.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stopReason)) {
            throw new LlmException("Модель отказалась отвечать на запрос");
        }
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(BetaTextBlock::text)
                .collect(Collectors.joining());
        return new LlmReply(text, message.model().asString(),
                message.usage().inputTokens(), message.usage().outputTokens(),
                BetaStopReason.MAX_TOKENS.equals(stopReason));
    }

    /**
     * Текст ошибки из ответа API: {@code {"error": {"type": ..., "message": ...}}}.
     * Это сообщение Anthropic о проблеме с запросом (баланс, параметры), содержимое запроса в нём нет.
     */
    private static String apiErrorMessage(AnthropicServiceException e) {
        try {
            if (e.body().convert(Map.class) instanceof Map<?, ?> body
                    && body.get("error") instanceof Map<?, ?> error
                    && error.get("message") instanceof String message) {
                return error.get("type") instanceof String type ? ": " + type + " — " + message : ": " + message;
            }
        } catch (RuntimeException ignored) {
            // тело ответа не JSON — показываем только код
        }
        return "";
    }

    @Override
    public boolean isConfigured() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    private AnthropicClient client() {
        AnthropicClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    // Без ключа SDK всё равно отправит запрос и получит 401 — сообщаем причину сразу
                    if (!isConfigured()) {
                        throw new LlmException("Не задан ключ Claude API: задайте переменную ANTHROPIC_API_KEY "
                                + "(Docker — в файле .env, IDEA — в Run → Edit Configurations → Environment variables)");
                    }
                    try {
                        current = AnthropicOkHttpClient.fromEnv();
                    } catch (RuntimeException e) {
                        throw new LlmException("Не задан ключ Claude API: установите переменную ANTHROPIC_API_KEY", e);
                    }
                    client = current;
                }
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        AnthropicClient current = client;
        if (current != null) {
            current.close();
        }
    }
}
