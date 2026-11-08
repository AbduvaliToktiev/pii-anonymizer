package ru.pii.anonymizer.pseudonym;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.pii.anonymizer.detect.PersonNames;
import ru.pii.anonymizer.detect.PiiType;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;

class PseudonymRegistryTest {

    private static PseudonymRegistry registry(TokenKey key) {
        return new PseudonymRegistry(false, BirthDateMode.DATE, Clock.systemUTC(), key);
    }

    @Test
    void tokenIsTypeAndKeyedCode() {
        String token = new PseudonymRegistry().tokenFor(PiiType.PERSON, "Петрова Анна Сергеевна");

        assertThat(token).matches("\\[PERSON_[0-9A-HJKMNP-TV-Z]{8}]").matches(Restorer.TOKEN.pattern());
    }

    @Test
    void sameKeyGivesSameTokenInEveryRequestAndOtherKeyDoesNot() {
        TokenKey key = TokenKey.random();

        String first = registry(key).tokenFor(PiiType.SNILS, "112-233-445 95");
        String second = registry(key).tokenFor(PiiType.SNILS, "11223344595");
        String otherKey = registry(TokenKey.random()).tokenFor(PiiType.SNILS, "112-233-445 95");

        assertThat(second).isEqualTo(first);
        assertThat(otherKey).isNotEqualTo(first);
    }

    @Test
    void caseFormsOfOnePersonGetOneTokenAndRestoreToNominative() {
        PseudonymRegistry registry = new PseudonymRegistry();

        String dative = registry.tokenFor(PiiType.PERSON, "Иванову Ивану");
        String nominative = registry.tokenFor(PiiType.PERSON, "Иванов Иван");
        String instrumental = registry.tokenFor(PiiType.PERSON, "Ивановым Иваном");
        String prepositional = registry.tokenFor(PiiType.PERSON, "Иване Иванове");
        String reversed = registry.tokenFor(PiiType.PERSON, "Иван Иванов");

        assertThat(nominative).isEqualTo(dative).isEqualTo(instrumental).isEqualTo(prepositional).isEqualTo(reversed);
        assertThat(registry.originalOf(nominative)).isEqualTo("Иванов Иван");
    }

    @Test
    void caseFormsWithPatronymicAndInitialsGetOneToken() {
        PseudonymRegistry registry = new PseudonymRegistry();

        String full = registry.tokenFor(PiiType.PERSON, "Петрова Анна Сергеевна");

        assertThat(registry.tokenFor(PiiType.PERSON, "Петровой Анне Сергеевне")).isEqualTo(full);
        assertThat(registry.tokenFor(PiiType.PERSON, "Петровой Анной Сергеевной")).isEqualTo(full);
        assertThat(registry.tokenFor(PiiType.PERSON, "Петрову Анну Сергеевну")).isEqualTo(full);
        assertThat(registry.tokenFor(PiiType.PERSON, "Петровой А.С.")).isEqualTo(full);
        assertThat(registry.originalOf(full)).isEqualTo("Петрова Анна Сергеевна");
    }

    @Test
    void differentPeopleWithSameStemsStayApart() {
        PseudonymRegistry registry = new PseudonymRegistry();

        String evgeniy = registry.tokenFor(PiiType.PERSON, "Иванов Евгений");
        String evgeniya = registry.tokenFor(PiiType.PERSON, "Иванова Евгения");

        assertThat(evgeniya).isNotEqualTo(evgeniy);
        assertThat(registry.originalOf(evgeniy)).isEqualTo("Иванов Евгений");
        assertThat(registry.originalOf(evgeniya)).isEqualTo("Иванова Евгения");
    }

    @Test
    void exactSpellingKeepsEveryCaseFormSeparate() {
        PseudonymRegistry registry = new PseudonymRegistry(true);

        String nominative = registry.tokenFor(PiiType.PERSON, "Иванов Иван");
        String dative = registry.tokenFor(PiiType.PERSON, "Иванову Ивану");

        assertThat(dative).isNotEqualTo(nominative);
        assertThat(registry.originalOf(dative)).isEqualTo("Иванову Ивану");
    }

    @Test
    void caseFormsInTextGetOneToken() {
        PseudonymRegistry registry = new PseudonymRegistry();
        String text = new TextAnonymizer(RussianPiiDetectors.all()).anonymize(
                "Выдать премию Ивану Петрову. Иван Петров работает в ИТ. Согласовано с Иваном Петровым.", registry);

        assertThat(registry.summary().unique()).containsEntry(PiiType.PERSON, 1);
        assertThat(text).doesNotContain("Иван", "Петров");
    }

    @ParameterizedTest
    @CsvSource({
            "Иванову, иванов", "Ивановой, иванов", "Ивановым, иванов", "Анной, анн", "Анне, анн",
            "Андрея, андр", "Андрей, андр", "Марией, мар", "Мария, мар", "Павла, павл", "Павел, павл",
            "Ивановича, иванович", "Ивановной, ивановн", "Достоевского, достоевск", "Достоевским, достоевск",
            "Сидоровой-Петровой, сидоров-петров", "Шевченко, шевченк", "Smith, smith", "Ли, ли",
    })
    void stemDropsCaseEnding(String word, String stem) {
        assertThat(PersonNames.stem(word)).isEqualTo(stem);
    }
}
