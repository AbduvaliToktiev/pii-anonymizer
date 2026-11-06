package ru.pii.anonymizer.detect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChecksumsTest {

    @ParameterizedTest
    @ValueSource(strings = {"11223344595", "12345678964", "98765432183"})
    void validSnils(String snils) {
        assertThat(Checksums.isValidSnils(snils)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11223344594", "12345678900", "1122334459", "abcdefghijk"})
    void invalidSnils(String snils) {
        assertThat(Checksums.isValidSnils(snils)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7707083893", "500100732259", "771234567859"})
    void validInn(String inn) {
        assertThat(Checksums.isValidInn(inn)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7707083890", "500100732250", "1234567890", "12345"})
    void invalidInn(String inn) {
        assertThat(Checksums.isValidInn(inn)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5555555555554444"})
    void validCard(String card) {
        assertThat(Checksums.isValidCard(card)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111112", "1234567812345678", "411111"})
    void invalidCard(String card) {
        assertThat(Checksums.isValidCard(card)).isFalse();
    }
}
