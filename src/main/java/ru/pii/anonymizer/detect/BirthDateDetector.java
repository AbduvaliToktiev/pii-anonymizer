package ru.pii.anonymizer.detect;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Даты рождения. Обычные даты (договоров, выплат, приёма на работу) не трогаются — они
 * нужны модели для расчётов. Датой рождения считается дата рядом со словом-признаком:
 * «дата рождения 12.03.1985», «родилась 5 июля 1990 г.», «д.р. 12.03.85», «1985 г.р.»,
 * «1985 года рождения». Даты в колонке «Дата рождения» находит {@link TableColumnDetector}.
 */
public final class BirthDateDetector implements PiiDetector {

    private static final String MONTHS =
            "января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря";

    /**
     * Дата целиком: 12.03.1985, 12/03/85, 1985-03-12, 1985-03-12 00:00:00, 12 марта 1985 г.,
     * а также в виде, в котором её печатает Python: datetime.datetime(1985, 3, 12, 0, 0), datetime.date(1985, 3, 12).
     */
    static final String DATE = "(?:(?:datetime\\.)?date(?:time)?\\(\\d{4},\\s*\\d{1,2},\\s*\\d{1,2}(?:,\\s*\\d{1,6})*\\)"
            + "|\\d{1,2}[./-]\\d{1,2}[./-](?:\\d{4}|\\d{2})"
            + "|\\d{4}-\\d{2}-\\d{2}(?:[ T]00:00(?::00)?)?"
            + "|\\d{1,2}\\s+(?:" + MONTHS + ")\\s+\\d{4}(?:\\s*(?:г\\.|года))?)";

    static final Pattern WHOLE_DATE = Pattern.compile("\\s*" + DATE + "\\s*",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);

    /** Признак перед датой; между ними — до 25 символов без цифр и переносов строк («: », « — »). */
    private static final Pattern KEYWORD_BEFORE = Pattern.compile(
            "(?<!\\p{L})(?:дата\\s+рождения|дат[аы]\\s+рожд\\.?|д\\.\\s?р\\.?|др\\.?|рожд(?:ения|\\.)|"
                    + "родил(?:ся|ась)|born|birth\\s*date|date\\s+of\\s+birth|dob)"
                    + "[^\\d\\n]{0,25}?(?<!\\d)(" + DATE + ")",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);

    /** Признак после даты или года: «1985 г.р.», «12.03.1985 года рождения». */
    private static final Pattern KEYWORD_AFTER = Pattern.compile(
            "(?<![\\d.])(" + DATE + "|\\d{4})\\s*(?:г\\.\\s?р\\.?|года\\s+рождения|г\\.\\s?рожд\\.)",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);

    private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("(?<!\\d)(\\d{4})(?!\\d)");

    @Override
    public List<Finding> detect(String text) {
        List<Finding> findings = new ArrayList<>();
        for (Pattern pattern : List.of(KEYWORD_BEFORE, KEYWORD_AFTER)) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                String value = m.group(1);
                if (plausibleBirthYear(value)) {
                    findings.add(new Finding(PiiType.BIRTHDATE, m.start(1), m.end(1), value));
                }
            }
        }
        return findings;
    }

    /** Год рождения от 1900 до текущего; двузначный год принимается без проверки. */
    static boolean plausibleBirthYear(String date) {
        Matcher year = FOUR_DIGIT_YEAR.matcher(date);
        if (!year.find()) {
            return true;
        }
        int y = Integer.parseInt(year.group(1));
        return y >= 1900 && y <= Year.now().getValue();
    }

    private static final Pattern PARSE_DMY = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4}|\\d{2})");
    private static final Pattern PARSE_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})(?:[ T]\\d{2}:\\d{2}(?::\\d{2})?)?");
    private static final Pattern PARSE_WORDS = Pattern.compile("(\\d{1,2})\\s+(\\p{L}+)\\s+(\\d{4})(?:\\s*(?:г\\.|года))?",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);
    private static final Pattern PARSE_PYTHON = Pattern.compile(
            "(?:datetime\\.)?date(?:time)?\\((\\d{4}),\\s*(\\d{1,2}),\\s*(\\d{1,2})(?:,\\s*\\d{1,6})*\\)");
    private static final List<String> MONTH_NAMES = List.of(MONTHS.split("\\|"));

    /**
     * Возраст в полных годах на дату {@code today}. Пусто, если значение не разбирается как полная дата
     * (например, записан только год), дата не существует или лежит в будущем.
     */
    public static OptionalInt age(String value, LocalDate today) {
        LocalDate birth = parse(value.strip(), today);
        if (birth == null || birth.isAfter(today) || birth.getYear() < 1900) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Period.between(birth, today).getYears());
    }

    private static LocalDate parse(String value, LocalDate today) {
        try {
            Matcher m;
            if ((m = PARSE_PYTHON.matcher(value)).matches() || (m = PARSE_ISO.matcher(value)).matches()) {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
            }
            if ((m = PARSE_DMY.matcher(value)).matches()) {
                int year = Integer.parseInt(m.group(3));
                if (m.group(3).length() == 2) {
                    // 85 → 1985, 05 → 2005: двузначный год не может быть в будущем
                    year += 2000;
                    if (year > today.getYear()) {
                        year -= 100;
                    }
                }
                return LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            }
            if ((m = PARSE_WORDS.matcher(value)).matches()) {
                int month = MONTH_NAMES.indexOf(m.group(2).toLowerCase(Locale.ROOT)) + 1;
                return month == 0 ? null
                        : LocalDate.of(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)));
            }
        } catch (DateTimeException e) {
            return null; // 31.02.1990 и подобное
        }
        return null;
    }

    /** Значение ячейки — дата (для колонки «Дата рождения»). */
    public static boolean isDate(String value) {
        return WHOLE_DATE.matcher(value).matches() && plausibleBirthYear(value);
    }
}
