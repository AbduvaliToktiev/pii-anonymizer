package ru.pii.anonymizer.detect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/** Адреса, даты рождения и колонки таблиц — через полный набор детекторов. */
class AddressAndBirthDateTest {

    private final TextAnonymizer anonymizer = new TextAnonymizer(RussianPiiDetectors.all());

    private String anonymize(String text) {
        return anonymizer.anonymize(text, new PseudonymRegistry());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Адрес: 123456, г. Москва, ул. Ленина, д. 5, кв. 12.          | Адрес: [ADDRESS_1].",
            "Проживает: Московская обл., г. Химки, ул. Мира, д. 10        | Проживает: [ADDRESS_1]",
            "Доставка на ул. 8 Марта, 5-12 до обеда                       | Доставка на [ADDRESS_1] до обеда",
            "Офис: Ленинский проспект, д. 30, корп. 2, оф. 415            | Офис: [ADDRESS_1]",
            "Склад: Москва, Тверская улица, 7                             | Склад: [ADDRESS_1]",
            "Прописка: пос. Новый, пер. Садовый, дом 3                    | Прописка: [ADDRESS_1]",
    })
    void findsAddresses(String input, String expected) {
        assertThat(anonymize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Дата рождения: 12.03.1985                  | Дата рождения: [BIRTHDATE_1]",
            "Родилась 5 июля 1990 г. в Казани           | Родилась [BIRTHDATE_1] в Казани",
            "Менеджер, 1985 г.р., стаж 10 лет           | Менеджер, [BIRTHDATE_1] г.р., стаж 10 лет",
            "д.р. 12.03.85                              | д.р. [BIRTHDATE_1]",
            "DOB: 1985-03-12                            | DOB: [BIRTHDATE_1]",
            "12.03.1985 года рождения                   | [BIRTHDATE_1] года рождения",
    })
    void findsBirthDates(String input, String expected) {
        assertThat(anonymize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Договор от 12.03.2024, оплата до 01.04.2024",
            "Принят на работу 01.09.2015",
            "Офис на ул. Ленина",
            "Отдел продаж, Москва, ул. Ленина",
            "См. пункт 5 договора, п. 3",
            "Рождественская распродажа 25.12.2025",
    })
    void leavesOrdinaryDatesAndPlacesAlone(String text) {
        assertThat(anonymize(text)).isEqualTo(text);
    }

    @Test
    void markdownTableColumnsAreAnonymized() {
        String table = """
                | ФИО | Дата рождения | Адрес | Зарплата |
                |---|---|---|---|
                | Смит Джон | 1988-11-23 | Казань, Баумана 5 | 180000 |
                | Ли Анна | 05.07.1990 | Ереван | 250000 |
                """;

        assertThat(anonymize(table)).isEqualTo("""
                | ФИО | Дата рождения | Адрес | Зарплата |
                |---|---|---|---|
                | [PERSON_1] | [BIRTHDATE_1] | [ADDRESS_1] | 180000 |
                | [PERSON_2] | [BIRTHDATE_2] | [ADDRESS_2] | 250000 |
                """);
    }

    @Test
    void pandasOutputWithIndexColumnIsAnonymized() {
        String pandas = """
                         ФИО  Дата рождения       Адрес  Зарплата
                0  Смит Джон     1988-11-23  Казань     180000
                1    Ли Анна     1990-07-05  Ереван     250000""";

        String result = anonymize(pandas);

        assertThat(result).doesNotContain("Смит", "Ли Анна", "1988-11-23", "1990-07-05", "Казань", "Ереван");
        assertThat(result).contains("180000", "250000", "Зарплата");
    }

    @Test
    void csvReadByClaudeCodeWithLineNumbersIsAnonymized() {
        String read = """
                     1→ФИО;Дата рождения;Отдел
                     2→Смит Джон;1988-11-23;ИТ
                     3→Ли Анна;05.07.1990;Продажи""";

        assertThat(anonymize(read)).isEqualTo("""
                     1→ФИО;Дата рождения;Отдел
                     2→[PERSON_1];[BIRTHDATE_1];ИТ
                     3→[PERSON_2];[BIRTHDATE_2];Продажи""");
    }

    @Test
    void openpyxlRowTuplesAreAnonymized() {
        String rows = """
                ('ФИО', 'Телефон', 'Отдел', 'Зарплата', 'Дата рождения', 'Адрес')
                ('Смит Джон', '+7 900 111-22-33', 'Продажи', 120000.0, datetime.datetime(1979, 2, 14, 0, 0), 'Казань, ул. Баумана, д. 7')
                ('Ли Анна', '', 'ИТ', 250000.0, '23.08.1992', 'Ереван, пр. Маштоца, д. 3')
                ('Ким Олег', None, 'ИТ', 180000.0, '1991-06-30', None)""";

        String result = anonymize(rows);

        assertThat(result).doesNotContain("1979", "23.08.1992", "1991-06-30", "Казань", "Ереван",
                "Смит Джон", "Ли Анна", "Ким Олег");
        assertThat(result).contains("[BIRTHDATE_", "'Продажи', 120000.0", "'ИТ', 250000.0", "'Дата рождения'");
    }

    @Test
    void listOfTuplesAndCsvWithQuotedCommasAreAnonymized() {
        String listOfTuples = """
                [('ФИО', 'Дата рождения', 'Отдел'),
                 ('Смит Джон', datetime.date(1979, 2, 14), 'Продажи'),
                 ('Ли Анна', '23.08.1992', 'ИТ')]""";
        String csv = """
                ФИО,Дата рождения,Адрес,Отдел
                Смит Джон,14.02.1979,"Казань, ул. Баумана, д. 7",Продажи""";

        assertThat(anonymize(listOfTuples)).doesNotContain("1979", "23.08.1992").contains("'Продажи'", "'ИТ'");
        assertThat(anonymize(csv)).doesNotContain("14.02.1979", "Казань", "Баумана").endsWith(",Продажи");
    }

    @Test
    void pythonDatetimeNextToKeywordIsAnonymized() {
        String result = anonymize("{'Дата рождения': datetime.datetime(1979, 2, 14, 0, 0), 'Отдел': 'ИТ'}");

        assertThat(result).doesNotContain("1979").contains("[BIRTHDATE_", "'Отдел': 'ИТ'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Отдел, должность, зарплата указаны ниже",
            "('Договор', datetime.date(2024, 3, 12), 'оплачен')",
            "Итого: 120000, 250000, 180000",
    })
    void ordinaryCommaSeparatedTextIsLeftAlone(String text) {
        assertThat(anonymize(text)).isEqualTo(text);
    }

    @Test
    void keyValueLinesAreNotTreatedAsTable() {
        String text = """
                ФИО  Иванов Иван
                Дата  12.03.2024
                Отдел  Продажи""";

        assertThat(anonymize(text)).isEqualTo("""
                ФИО  [PERSON_1]
                Дата  12.03.2024
                Отдел  Продажи""");
    }
}
