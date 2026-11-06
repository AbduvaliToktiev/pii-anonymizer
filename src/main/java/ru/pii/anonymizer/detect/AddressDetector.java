package ru.pii.anonymizer.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Почтовые адреса: «123456, Московская обл., г. Химки, ул. Мира, д. 10, кв. 5»,
 * «г. Москва, ул. 8 Марта, 5-12», «Ленинский проспект, д. 30, корп. 2».
 *
 * <p>Опорная точка — улица (ул., пр-т, пер., шоссе, бульвар, наб., пл., мкр, проезд…)
 * <b>с номером дома</b>. К ней присоединяются индекс, регион и город перед ней, корпус,
 * строение и квартира после. Улица без номера дома («офис на ул. Ленина») не считается
 * персональными данными и не заменяется. Адреса в колонке «Адрес» находит {@link TableColumnDetector}.
 */
public final class AddressDetector implements PiiDetector {

    private static final String WORD = "[А-ЯЁ][а-яё]+(?:-[А-ЯЁа-яё][а-яё]+)*";

    /** Индекс, регион, район, город перед улицей — каждый элемент с запятой после. */
    private static final String PREFIX = "(?:(?<!\\d)\\d{6}\\s*,\\s*)?"
            + "(?:(?:(?iu:г\\.|гор\\.|город|пос\\.|пгт|п\\.|с\\.|село|дер\\.|деревня|обл\\.|область|респ\\.|республика|р-н|район)\\s*"
            + WORD + "(?:\\s+" + WORD + ")?"
            + "|" + WORD + "(?:\\s+" + WORD + ")?\\s+(?iu:обл\\.|область|край|р-н|район|АО)"
            + ")\\s*,\\s*)*"
            + "(?:" + WORD + "\\s*,\\s*)?";

    private static final String STREET_MARKER = "(?iu:ул\\.|улица|пр-т|просп\\.|проспект|пер\\.|переулок|ш\\.|шоссе"
            + "|б-р|бул\\.|бульвар|наб\\.|набережная|пл\\.|площадь|мкр\\.?|микрорайон|проезд|туп\\.|тупик|аллея|линия)";

    /** Название улицы: до трёх слов, может начинаться с цифры («8 Марта», «1-я Тверская-Ямская»). */
    private static final String STREET_NAME = "[А-ЯЁ0-9][\\p{L}\\d.-]*(?:\\s+[А-ЯЁ0-9][\\p{L}\\d.-]*){0,2}";

    private static final String STREET = "(?:(?<!\\p{L})" + STREET_MARKER + "\\s*" + STREET_NAME
            + "|(?<!\\p{L})" + WORD + "\\s+" + STREET_MARKER + ")";

    /** Дом обязателен: «д. 5», «дом 5», «, 5», «5а», «5/2», «5-12» (дом-квартира). */
    private static final String HOUSE = "\\s*,?\\s*(?:(?iu:д\\.|дом)\\s*)?\\d{1,4}[а-яА-Я]?(?:/\\d{1,4})?(?:-\\d{1,4}(?!\\d))?"
            + "(?:\\s*,?\\s*(?iu:корп\\.?|корпус|к\\.|стр\\.?|строение)\\s*\\d{1,3}[а-яА-Я]?)*"
            + "(?:\\s*,?\\s*(?iu:кв\\.?|квартира|оф\\.?|офис|пом\\.?|помещение)\\s*\\d{1,5})?"
            + "(?![\\d\\p{L}])";

    private static final Pattern ADDRESS = Pattern.compile(
            "(?<![\\p{L}\\d])" + PREFIX + STREET + HOUSE, Pattern.UNICODE_CHARACTER_CLASS);

    @Override
    public List<Finding> detect(String text) {
        List<Finding> findings = new ArrayList<>();
        Matcher m = ADDRESS.matcher(text);
        while (m.find()) {
            findings.add(new Finding(PiiType.ADDRESS, m.start(), m.end(), m.group()));
        }
        return findings;
    }
}
