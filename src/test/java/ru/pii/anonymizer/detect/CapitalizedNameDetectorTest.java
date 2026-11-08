package ru.pii.anonymizer.detect;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/** Имена без словаря — через полный набор детекторов, как в реальной работе. */
class CapitalizedNameDetectorTest {

    private final TextAnonymizer anonymizer = new TextAnonymizer(RussianPiiDetectors.all());

    private String anonymize(String text) {
        return Tokens.renumber(anonymizer.anonymize(text, new PseudonymRegistry()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // русские имена в любом порядке и падеже
            "Тестировщик Сидоров Пётр уволен           | Тестировщик [PERSON_1] уволен",
            "Тестировщик Пётр Сидоров уволен           | Тестировщик [PERSON_1] уволен",
            "Сидоров Петр, ИТ, 180000                  | [PERSON_1], ИТ, 180000",
            "Анна Петрова — руководитель               | [PERSON_1] — руководитель",
            "Выдать премию Петру Сидорову              | Выдать премию [PERSON_1]",
            "Письмо от Анны Петровой                   | Письмо от [PERSON_1]",
            "Согласовано с Ольгой Сидоровой-Петровой   | Согласовано с [PERSON_1]",
            // глагол или служебное слово в начале фразы — не часть имени
            "Позвоните Анне Петровой завтра            | Позвоните [PERSON_1] завтра",
            "Напиши Ивану Козлову письмо               | Напиши [PERSON_1] письмо",
            "Сегодня Вера Павлова в отпуске            | Сегодня [PERSON_1] в отпуске",
            // имена других народов
            "Нурлан Абенов принят на работу            | [PERSON_1] принят на работу",
            "Договор подписал Шавкат Мирзаев           | Договор подписал [PERSON_1]",
            "Отчёт от Гульнары Сафиной                 | Отчёт от [PERSON_1]",
            "Алиев Рашид оглы, водитель                | [PERSON_1], водитель",
            "Айгерім Қасымова в штате                  | [PERSON_1] в штате",
            "Call John Smith tomorrow                  | Call [PERSON_1] tomorrow",
            "Meeting with Mary-Jane O'Connor           | Meeting with [PERSON_1]",
            "Ludwig van Beethoven wrote it             | [PERSON_1] wrote it",
            "Contact Jean-Luc Picard                   | Contact [PERSON_1]",
            // одно имя — после должности или обращения
            "Директор Олег одобрил                     | Директор [PERSON_1] одобрил",
            "Привет, Саша!                             | Привет, [PERSON_1]!",
            "Dear Mr. Smith,                           | Dear Mr. [PERSON_1],",
            // с отчеством по-прежнему одна метка на всё ФИО
            "Сидоров Пётр Иванович                     | [PERSON_1]",
    })
    void findsNames(String input, String expected) {
        assertThat(anonymize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Вера в успех помогает",
            "Любовь к работе",
            "Отдел продаж, Москва, ул. Ленина",
            "Сотрудник отдела ИТ",
            "Итоговая таблица по отделам",
            "Филиал в г. Нижний Новгород",
            "Поставщик ООО Северный Ветер",
            "Компания «Золотой Ключ» работает",
            "# Трудовой Договор Сотрудника",
            "Позвоните завтра",
            "Excel is great",
    })
    void leavesOrdinaryWordsAlone(String text) {
        assertThat(anonymize(text)).isEqualTo(text);
    }
}
