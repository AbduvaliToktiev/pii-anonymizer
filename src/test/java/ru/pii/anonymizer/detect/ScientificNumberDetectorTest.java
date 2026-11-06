package ru.pii.anonymizer.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/** Идентификаторы в научной нотации — так их хранит Excel внутри xlsx. */
class ScientificNumberDetectorTest {

    private final TextAnonymizer anonymizer = new TextAnonymizer(RussianPiiDetectors.all());

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<c r=\"B3\"><v>1.2345678964E10</v></c>    | <c r=\"B3\"><v>[SNILS_1]</v></c>",
            "<v>5.00100732259E11</v>                    | <v>[INN_1]</v>",
            "СНИЛС 1.1223344595E+10                     | СНИЛС [SNILS_1]",
            "карта 4.111111111111111E15                 | карта [CARD_1]",
    })
    void identifiersInScientificNotationAreFound(String input, String expected) {
        assertThat(anonymizer.anonymize(input, new PseudonymRegistry())).isEqualTo(expected);
    }

    @Test
    void floatPrintedByPythonIsReplacedWithoutTrailingZero() {
        String row = "('Отдел ИТ', 7707083893.0, 11223344595.0, 120000.0)";

        String result = anonymizer.anonymize(row, new PseudonymRegistry());

        assertThat(result).doesNotContain("7707083893", "11223344595", "].0")
                .contains("[INN_", "[SNILS_", "120000.0");
    }

    @Test
    void sameIdentifierInDifferentNumberFormatsGetsOneToken() {
        String text = "a 7707083893.0 b 7707083893 c 7.707083893E9";

        String result = anonymizer.anonymize(text, new PseudonymRegistry());

        List<String> tokens = Pattern.compile("\\[INN_\\d+]").matcher(result).results()
                .map(MatchResult::group).toList();
        assertThat(tokens).hasSize(3).containsOnly(tokens.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Итого 1234567890.0",
            "Зарплата 120000.0",
    })
    void ordinaryFloatsAreKept(String text) {
        assertThat(anonymizer.anonymize(text, new PseudonymRegistry())).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<v>1.2E5</v>",
            "<v>2.5E-3</v>",
            "<v>1.2345678900E10</v>",
            "коэффициент 6.02E23",
    })
    void ordinaryNumbersAreKept(String text) {
        assertThat(anonymizer.anonymize(text, new PseudonymRegistry())).isEqualTo(text);
    }
}
