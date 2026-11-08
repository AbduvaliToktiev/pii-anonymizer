package ru.pii.anonymizer.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

class TextAnonymizerTest {

    private final TextAnonymizer anonymizer = new TextAnonymizer(RussianPiiDetectors.all());

    private String anonymize(String text) {
        return Tokens.renumber(anonymizer.anonymize(text, new PseudonymRegistry()));
    }

    @Test
    void replacesAllKindsOfPii() {
        String result = anonymize("Петрова Анна Сергеевна, СНИЛС 123-456-789 64, ИНН 771234567859, "
                + "паспорт 45 06 123456, тел. +7 (903) 765-43-21, anna@example.ru, карта 4111 1111 1111 1111");

        assertThat(result).isEqualTo("[PERSON_1], СНИЛС [SNILS_1], ИНН [INN_1], "
                + "паспорт [PASSPORT_1], тел. [PHONE_1], [EMAIL_1], карта [CARD_1]");
    }

    @Test
    void sameValueInDifferentFormatsGetsSameToken() {
        String result = anonymize("Звонить +7 916 123-45-67 или 8(916)1234567, а не +7 903 765-43-21");

        assertThat(result).isEqualTo("Звонить [PHONE_1] или [PHONE_1], а не [PHONE_2]");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Иванов Иван Иванович; приказ подписал Иванов И.И.   | [PERSON_1]; приказ подписал [PERSON_1]",
            "Подписал И. И. Иванов, исполнитель Иван Иванович Иванов | Подписал [PERSON_1], исполнитель [PERSON_1]",
            "Иванов Иван Иванович, Иванов И.И. и Иванов П.И.     | [PERSON_1], [PERSON_1] и [PERSON_2]",
    })
    void fullNameAndInitialsGetSameToken(String input, String expected) {
        assertThat(anonymize(input)).isEqualTo(expected);
    }

    @Test
    void abbreviationIsRestoredAsFullName() {
        PseudonymRegistry registry = new PseudonymRegistry();
        anonymizer.anonymize("Согласовал Иванов И.И., исполнитель Иванов Иван Иванович", registry);

        assertThat(registry.mapping()).hasSize(1).containsValue("Иванов Иван Иванович");
    }

    @Test
    void ambiguousInitialsKeepPeopleApart() {
        String result = anonymize("Иванов Иван Иванович и Иванов Игорь Ильич; отчёт сдал Иванов И.И.");

        assertThat(result).isEqualTo("[PERSON_1] и [PERSON_2]; отчёт сдал [PERSON_3]");
    }

    @Test
    void exactSpellingKeepsAbbreviationSeparate() {
        PseudonymRegistry registry = new PseudonymRegistry(true);

        assertThat(Tokens.renumber(anonymizer.anonymize("Иванов Иван Иванович, Иванов И.И.", registry)))
                .isEqualTo("[PERSON_1], [PERSON_2]");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Сотрудник Иванов Иван Иванович уволен        | Сотрудник [PERSON_1] уволен",
            "Сотрудник Иван Иванович Иванов уволен         | Сотрудник [PERSON_1] уволен",
            "Премия Петровой Анне Сергеевне одобрена       | Премия [PERSON_1] одобрена",
            "Подписал Салтыков-Щедрин Михаил Евграфович    | Подписал [PERSON_1]",
            "Исполнитель: Иванов И.И.                      | Исполнитель: [PERSON_1]",
            "Исполнитель: И. И. Иванов                     | Исполнитель: [PERSON_1]",
            "Подписал И. И. Иванов                         | Подписал [PERSON_1]",
            "Директор И.И. Кузнецова уволена               | Директор [PERSON_1] уволена",
            "Утвердил А.В. Достоевский                     | Утвердил [PERSON_1]",
            "Согласовал Иванов И.И. Приказ вступает в силу | Согласовал [PERSON_1] Приказ вступает в силу",
            "Отчёт сдал Шевченко П.П. Далее по тексту      | Отчёт сдал [PERSON_1] Далее по тексту",
            "Гражданин И.И. Иванов задержан                | Гражданин [PERSON_1] задержан",
            "Выдано гражданке А.С. Петровой                | Выдано гражданке [PERSON_1]",
            "Мужчина И.И. Сидоров ушёл                     | Мужчина [PERSON_1] ушёл",
            "Гражданин Иван Иванович пришёл                | Гражданин [PERSON_1] пришёл",
            "Господину Ивану Петровичу Смирнову            | Господину [PERSON_1]",
            "Анна Сергеевна просила перезвонить            | [PERSON_1] просила перезвонить",
    })
    void findsRussianNames(String input, String expected) {
        assertThat(anonymize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Оклад 120000 рублей, премия 15 000",
            "Договор № 1234567890 от 01.09.2026",
            "Отдел продаж, Москва, ул. Ленина",
            "Кол-во 11223344594 шт.",
    })
    void leavesOrdinaryTextAlone(String text) {
        assertThat(anonymize(text)).isEqualTo(text);
    }

    @Test
    void passportWithoutSpacesOnlyNearKeyword() {
        assertThat(anonymize("Паспорт: 4506123456")).isEqualTo("Паспорт: [PASSPORT_1]");
        // те же 10 цифр без слова «паспорт» и с неверной контрольной суммой ИНН — не трогаем
        assertThat(anonymize("Номер заявки 4506123456")).isEqualTo("Номер заявки 4506123456");
    }

    @Test
    void numbersGluedToLettersAreFound() {
        assertThat(anonymize("ИНН7707083893")).isEqualTo("ИНН[INN_1]");
    }
}
