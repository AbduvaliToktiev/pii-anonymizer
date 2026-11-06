package ru.pii.anonymizer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ru.pii.anonymizer.SampleDocuments;
import ru.pii.anonymizer.llm.LlmClient;
import ru.pii.anonymizer.llm.LlmException;
import ru.pii.anonymizer.llm.LlmReply;

/**
 * Полный цикл без реального Claude: вместо модели — заглушка, которая запоминает,
 * что ей прислали, и отвечает метками.
 */
@SpringBootTest
class AskControllerTest {

    /** Заглушка LLM: сохраняет последнее сообщение и возвращает заданный ответ. */
    static final class FakeLlm implements LlmClient {
        final AtomicReference<String> lastMessage = new AtomicReference<>();
        volatile String answer = "";
        volatile boolean fail;

        @Override
        public LlmReply ask(String systemPrompt, String userMessage) {
            if (fail) {
                throw new LlmException("Claude API вернул ошибку 529");
            }
            lastMessage.set(userMessage);
            return new LlmReply(answer, "fake-model", 100, 20, false);
        }
    }

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FakeLlm llm;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        llm.fail = false;
        llm.lastMessage.set(null);
    }

    @Test
    void llmSeesOnlyTokensAndUserGetsRealNames() throws Exception {
        // Петрова — второй человек в таблице, её телефон — второй телефон
        llm.answer = "Больше всех получает [PERSON_2] — 250000, телефон [PHONE_2]. А вот [PERSON_9] нет в данных.";
        var file = new MockMultipartFile("file", "Зарплаты Иванова.xlsx",
                "application/octet-stream", SampleDocuments.salaryXlsx());

        mvc.perform(multipart("/api/ask").file(file)
                        .param("prompt", "Кто получает больше всех? Сколько получает Иванов Иван Иванович?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Больше всех получает Петрова Анна Сергеевна — 250000, "
                        + "телефон 8 (903) 765-43-21. А вот [PERSON_9] нет в данных."))
                .andExpect(jsonPath("$.restored").value(2))
                .andExpect(jsonPath("$.unknownTokens[0]").value("[PERSON_9]"))
                .andExpect(jsonPath("$.found.PERSON").value(4))
                .andExpect(jsonPath("$.sentToLlm").doesNotExist());

        String sent = llm.lastMessage.get();
        assertThat(sent).doesNotContain(SampleDocuments.PII_VALUES);
        assertThat(sent).doesNotContain("Зарплаты Иванова");
        // Вопрос и файл используют общие метки: Иванов из вопроса — это [PERSON_1] из таблицы
        assertThat(sent).contains("Сколько получает [PERSON_1]?", "[PERSON_1] | [SNILS_1]", "250000", "Разработчик");
    }

    @Test
    void showAnonymizedReturnsWhatLlmSaw() throws Exception {
        llm.answer = "Звоните [PHONE_1]";

        mvc.perform(multipart("/api/ask")
                        .param("prompt", "Позвони Петровой Анне Сергеевне по номеру +7 903 765-43-21")
                        .param("showAnonymized", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Звоните +7 903 765-43-21"))
                .andExpect(jsonPath("$.sentToLlm").value("Позвони [PERSON_1] по номеру [PHONE_1]"))
                .andExpect(jsonPath("$.sentToLlm").value(not(containsString("Петров"))))
                .andExpect(jsonPath("$.llmAnswer").value("Звоните [PHONE_1]"));
    }

    @Test
    void missingPromptIsBadRequest() throws Exception {
        mvc.perform(multipart("/api/ask"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("prompt")));
    }

    @Test
    void llmFailureIsBadGateway() throws Exception {
        llm.fail = true;

        mvc.perform(multipart("/api/ask").param("prompt", "Привет"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Claude API вернул ошибку 529"));
    }
}
