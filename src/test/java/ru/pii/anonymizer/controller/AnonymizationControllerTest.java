package ru.pii.anonymizer.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.SampleDocuments;

@SpringBootTest
class AnonymizationControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void fileIsAnonymizedWithServiceKeyCodes() throws Exception {
        var file = new MockMultipartFile("file", "Приказ.pptx", "application/octet-stream",
                ru.pii.anonymizer.LargeSamples.presentationPptx());

        MvcResult result = mvc.perform(multipart("/api/anonymize").file(file))
                .andExpect(status().isOk())
                .andReturn();

        // Коды вычислены ключом сервиса: те же, что выдаёт реестр из настроек
        String expected = registries.create().tokenFor(ru.pii.anonymizer.detect.PiiType.PHONE, "+7 916 123-45-67");
        assertThat(new String(new ru.pii.anonymizer.document.DocumentTextExtractor().toText(
                ru.pii.anonymizer.document.DocumentFormat.PPTX, result.getResponse().getContentAsByteArray())))
                .contains(expected);
    }

    @Autowired
    private ru.pii.anonymizer.pseudonym.PseudonymRegistryFactory registries;

    @Test
    void uploadXlsxReturnsAnonymizedFileWithSummary() throws Exception {
        var file = new MockMultipartFile("file", "Зарплаты.xlsx",
                "application/octet-stream", SampleDocuments.salaryXlsx());

        MvcResult result = mvc.perform(multipart("/api/anonymize").file(file))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(AnonymizationController.SUMMARY_HEADER,
                        "EMAIL=3, CARD=1, SNILS=3, INN=2, PHONE=3, BIRTHDATE=3, ADDRESS=3, PERSON=4"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("anonymized_%D0%97%D0%B0%D1%80%D0%BF%D0%BB%D0%B0%D1%82%D1%8B.xlsx");
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    void unsupportedFormatIsRejected() throws Exception {
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "Иванов Иван Иванович".getBytes());

        mvc.perform(multipart("/api/anonymize").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("не поддерживается")));
    }

    @Test
    void renamedFileIsRejected() throws Exception {
        var file = new MockMultipartFile("file", "fake.docx", "application/octet-stream", "просто текст".getBytes());

        mvc.perform(multipart("/api/anonymize").file(file))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void textEndpointAnonymizesText() throws Exception {
        mvc.perform(post("/api/anonymize/text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Звонить Иванову Ивану Ивановичу: +7 916 123-45-67\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(Tokens.renumberedEqualTo("Звонить [PERSON_1]: [PHONE_1]")))
                .andExpect(jsonPath("$.replacements").value(2));
    }
}
