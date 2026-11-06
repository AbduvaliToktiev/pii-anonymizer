package ru.pii.anonymizer.pseudonym;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.pii.anonymizer.detect.PiiType;

class RestorerTest {

    @Test
    void replacesKnownTokensAndKeepsUnknown() {
        PseudonymRegistry registry = new PseudonymRegistry();
        registry.tokenFor(PiiType.PERSON, "Петрова Анна Сергеевна");
        registry.tokenFor(PiiType.PHONE, "+7 903 765-43-21");

        Restorer.Result result = Restorer.restore(
                "[PERSON_1] получает больше всех, телефон [PHONE_1]. Ещё [PERSON_7] и [PERSON_1].",
                registry.mapping());

        assertThat(result.text()).isEqualTo("Петрова Анна Сергеевна получает больше всех, "
                + "телефон +7 903 765-43-21. Ещё [PERSON_7] и Петрова Анна Сергеевна.");
        assertThat(result.restored()).isEqualTo(3);
        assertThat(result.unknown()).containsExactly("[PERSON_7]");
    }

    @Test
    void restoresFirstSpellingOfValue() {
        PseudonymRegistry registry = new PseudonymRegistry();
        registry.tokenFor(PiiType.PHONE, "+7 916 123-45-67");
        registry.tokenFor(PiiType.PHONE, "8-916-123-45-67");

        assertThat(Restorer.restore("[PHONE_1]", registry.mapping()).text()).isEqualTo("+7 916 123-45-67");
    }

    @Test
    void textWithoutTokensIsUnchanged() {
        Restorer.Result result = Restorer.restore("Средняя зарплата 183 333 ₽ [примечание]", Map.of());

        assertThat(result.text()).isEqualTo("Средняя зарплата 183 333 ₽ [примечание]");
        assertThat(result.restored()).isZero();
        assertThat(result.unknown()).isEmpty();
    }
}
