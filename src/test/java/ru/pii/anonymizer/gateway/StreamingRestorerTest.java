package ru.pii.anonymizer.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

class StreamingRestorerTest {

    private final Map<String, String> mapping = Map.of(
            "[PERSON_00000001]", "Иванов Иван Иванович",
            "[PHONE_00000001]", "8-916-123-45-67",
            "[PERSON_00000002]", "Анна \"Аня\" Петрова");

    @Test
    void tokenSplitBetweenChunksIsRestored() {
        StreamingRestorer restorer = new StreamingRestorer(mapping::get, UnaryOperator.identity());

        String out = restorer.push("Больше всех получает [PERS")
                + restorer.push("ON_00000001], телефон [")
                + restorer.push("PHONE_")
                + restorer.push("00000001].")
                + restorer.flush();

        assertThat(out).isEqualTo("Больше всех получает Иванов Иван Иванович, телефон 8-916-123-45-67.");
    }

    @Test
    void longTokenTypeSplitBetweenChunksIsRestored() {
        StreamingRestorer restorer = new StreamingRestorer(Map.of("[BIRTHDATE_00000001]", "12.03.1985")::get,
                UnaryOperator.identity());

        String out = restorer.push("Родился [BIRTHDA") + restorer.push("TE_00000001] в Москве") + restorer.flush();

        assertThat(out).isEqualTo("Родился 12.03.1985 в Москве");
    }

    @Test
    void ageSuffixSplitBetweenChunksIsRestored() {
        String token = "[BIRTHDATE" + "_00000001_AGE41]";
        StreamingRestorer restorer = new StreamingRestorer(Map.of(token, "D-1985")::get, UnaryOperator.identity());

        String out = restorer.push("Родился " + token.substring(0, 22)) + restorer.push(token.substring(22) + ".")
                + restorer.flush();

        assertThat(out).isEqualTo("Родился D-1985.");
    }

    @Test
    void holdsOnlyWhatLooksLikeTokenStart() {
        StreamingRestorer restorer = new StreamingRestorer(mapping::get, UnaryOperator.identity());

        assertThat(restorer.push("Итого: 250 000 ₽ [PER")).isEqualTo("Итого: 250 000 ₽ ");
        assertThat(restorer.push("SON_00000001]")).isEqualTo("Иванов Иван Иванович");
        assertThat(restorer.push("массив a[i] и [ссылка]")).isEqualTo("массив a[i] и [ссылка]");
    }

    @Test
    void unknownTokenAndUnfinishedTailAreKept() {
        StreamingRestorer restorer = new StreamingRestorer(mapping::get, UnaryOperator.identity());

        assertThat(restorer.push("[PERSON_00000009] и [PERSON_")).isEqualTo("[PERSON_00000009] и ");
        assertThat(restorer.flush()).isEqualTo("[PERSON_");
    }

    @Test
    void valuesInsideJsonAreEscaped() {
        StreamingRestorer restorer = new StreamingRestorer(mapping::get,
                v -> v.replace("\\", "\\\\").replace("\"", "\\\""));

        assertThat(restorer.push("{\"content\":\"[PERSON_00000002]\"}")).isEqualTo("{\"content\":\"Анна \\\"Аня\\\" Петрова\"}");
    }
}
