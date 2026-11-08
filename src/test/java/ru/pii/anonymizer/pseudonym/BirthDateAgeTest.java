package ru.pii.anonymizer.pseudonym;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.detect.PiiType;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;

/** Режим {@link BirthDateMode#AGE}: метка даты рождения несёт возраст, а восстанавливается в дату. */
class BirthDateAgeTest {

    /** «Сегодня» для всех тестов — 06.11.2026. */
    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);

    private final PseudonymRegistryFactory registries = new PseudonymRegistryFactory(BirthDateMode.AGE, TODAY, TokenKey.random());

    /** Метка в перенумерованном виде ({@link Tokens#renumber}). */
    private static String token(int index, int age) {
        return "[" + PiiType.BIRTHDATE + "_" + index + "_AGE" + age + "]";
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "12.03.1985                            | 41",
            "06.11.1990                            | 36", // день рождения сегодня
            "07.11.1990                            | 35", // завтра
            "1988-11-23                            | 37",
            "1988-11-23 00:00:00                   | 37",
            "datetime.datetime(1979, 2, 14, 0, 0)  | 47",
            "datetime.date(1979, 2, 14)            | 47",
            "5 июля 1990 г.                        | 36",
            "12/03/85                              | 41",
            "12.03.05                              | 21",
    })
    void tokenCarriesAgeInFullYears(String date, int age) {
        PseudonymRegistry registry = registries.create();

        String token = registry.tokenFor(PiiType.BIRTHDATE, date);

        assertThat(Tokens.renumber(token)).isEqualTo(token(1, age));
        assertThat(registry.originalOf(token)).isEqualTo(date);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1985", "31.02.1990", "12.03.2030", "12.13.1985"})
    void unparsableOrImpossibleDateGetsPlainToken(String date) {
        assertThat(registries.create().tokenFor(PiiType.BIRTHDATE, date)).doesNotContain("AGE").matches(Restorer.TOKEN.pattern());
    }

    @Test
    void dateModeKeepsPlainToken() {
        PseudonymRegistry registry = new PseudonymRegistryFactory(BirthDateMode.DATE, TODAY, TokenKey.random()).create();

        assertThat(registry.tokenFor(PiiType.BIRTHDATE, "12.03.1985")).doesNotContain("AGE");
    }

    @Test
    void tableIsAnonymizedWithAgesAndAnswerIsRestoredToDates() {
        PseudonymRegistry registry = registries.create();
        String table = """
                | ФИО | Дата рождения | Отдел |
                |---|---|---|
                | Смит Джон | 12.03.1985 | Продажи |
                | Ли Анна | 1990-07-05 | ИТ |
                """;

        String anonymized = new TextAnonymizer(RussianPiiDetectors.all()).anonymize(table, registry);

        assertThat(Tokens.renumber(anonymized)).contains(token(1, 41), token(2, 36));
        assertThat(anonymized).doesNotContain("1985", "1990");
        String answer = "Старше 40 только сотрудник с датой рождения " + Tokens.actual(anonymized, token(1, 41)) + ".";
        assertThat(Restorer.restore(answer, registry.mapping()).text())
                .isEqualTo("Старше 40 только сотрудник с датой рождения 12.03.1985.");
    }

    @Test
    void restorerRecognizesAgeSuffix() {
        String token = "[" + PiiType.BIRTHDATE + "_K7M2QX9D_AGE41]";
        Restorer.Result result = Restorer.restore("родился " + token, Map.of(token, "12.03.1985"));

        assertThat(result.text()).isEqualTo("родился 12.03.1985");
        assertThat(result.restored()).isEqualTo(1);
    }
}
