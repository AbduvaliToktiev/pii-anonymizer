package ru.pii.anonymizer.detect;

import static ru.pii.anonymizer.detect.PersonNameDetector.PATRONYMIC;
import static ru.pii.anonymizer.detect.PersonNameDetector.SURNAME;
import static ru.pii.anonymizer.detect.PersonNameDetector.UPPER;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сведение разных записей одного ФИО к общему ключу «фамилия + инициалы»,
 * чтобы «Иванов Иван Иванович» и «Иванов И.И.» получали одну метку.
 *
 * <p>Падежи не учитываются: «Иванову И.И.» и «Иванов Иван Иванович» дадут разные ключи.
 */
public final class PersonNames {

    private static final Pattern WORD = Pattern.compile(SURNAME);
    private static final Pattern PATRONYMIC_WORD = Pattern.compile(PATRONYMIC);
    private static final Pattern SURNAME_THEN_INITIALS = Pattern.compile(
            "(" + SURNAME + ")\\s+(" + UPPER + ")\\.\\s?(" + UPPER + ")\\.", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern INITIALS_THEN_SURNAME = Pattern.compile(
            "(" + UPPER + ")\\.\\s?(" + UPPER + ")\\.\\s?(" + SURNAME + ")", Pattern.UNICODE_CHARACTER_CLASS);

    private PersonNames() {
    }

    /**
     * Ключ вида «иванов ии» для полного ФИО (в любом порядке) или формы с инициалами;
     * {@code null}, если фамилию и оба инициала выделить нельзя (например, «Анна Сергеевна»).
     */
    public static String surnameAndInitials(String name) {
        String value = name.strip();
        Matcher m = SURNAME_THEN_INITIALS.matcher(value);
        if (m.matches()) {
            return key(m.group(1), m.group(2), m.group(3));
        }
        m = INITIALS_THEN_SURNAME.matcher(value);
        if (m.matches()) {
            return key(m.group(3), m.group(1), m.group(2));
        }
        String[] words = value.split("\\s+");
        if (words.length != 3 || !allWords(words)) {
            return null;
        }
        // Сначала «Имя Отчество Фамилия»: так фамилия на -вич («Шостакович») не примется за отчество
        if (PATRONYMIC_WORD.matcher(words[1]).matches()) {
            return key(words[2], words[0], words[1]);
        }
        if (PATRONYMIC_WORD.matcher(words[2]).matches()) {
            return key(words[0], words[1], words[2]);
        }
        return null;
    }

    /** {@code true} для «Иванов И.И.» и «И. И. Иванов». */
    public static boolean isAbbreviated(String name) {
        String value = name.strip();
        return SURNAME_THEN_INITIALS.matcher(value).matches() || INITIALS_THEN_SURNAME.matcher(value).matches();
    }

    private static boolean allWords(String[] words) {
        for (String word : words) {
            if (!WORD.matcher(word).matches()) {
                return false;
            }
        }
        return true;
    }

    private static String key(String surname, String name, String patronymic) {
        return stem(surname) + " " + (name.charAt(0) + "" + patronymic.charAt(0)).toLowerCase(Locale.ROOT);
    }

    // ---------- падежи ----------

    /** Окончания падежей, от длинных к коротким. «им» — только после «ск/цк»: «Ким» не трогаем. */
    private static final String[] ENDINGS = {
            "ыми", "ией", "иею", "ием", "ого", "ому", "ему",
            "ия", "ии", "ию", "ий", "ая", "ую", "ой", "ою", "ей", "ею", "ем", "ом", "ым", "ых",
            "а", "я", "у", "ю", "е", "ы", "и", "ь", "й"};

    /** Беглая гласная: «Павел — Павла», «Лев — Льва». */
    private static final Map<String, String> FLEETING = Map.of("павел", "павл", "лев", "льв");

    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");
    /** Окончания косвенных падежей: «Иванову», «Иваном», «Петровой», «Анне», «Анны», «Марии». «-им» не берём: Максим, Рахим. */
    private static final Pattern OBLIQUE_WORD = Pattern.compile("(?:ым|ом|ем|ой|ою|ею|ую|ему|ому|ого|у|ю|е|ы|ии)$");
    private static final Pattern NOMINATIVE_WORD = Pattern.compile(
            "(?:ов|ев|ин|ын|ова|ева|ина|ына|ский|ская|цкий|цкая|ая|ий|ия|ко|ых|их|[бвгджзклмнпрстфхцчшщ]|а|я|й|ь)$");

    /**
     * Ключ ФИО без учёта падежа, регистра и порядка слов:
     * «Иванову Ивану», «Ивановым Иваном» и «Иван Иванов» → «иван иванов».
     */
    public static String caseInsensitiveKey(String name) {
        return Arrays.stream(name.strip().split("\\s+"))
                .map(PersonNames::stem)
                .sorted()
                .collect(Collectors.joining(" "));
    }

    /**
     * Основа слова без падежного окончания: «Иванову» → «иванов», «Анной» → «анн»,
     * «Ивановича» → «иванович», «Достоевского» → «достоевск». Двойная фамилия — по частям.
     * Слова не на кириллице («Smith») только приводятся к нижнему регистру.
     */
    public static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (w.indexOf('-') > 0) {
            return Arrays.stream(w.split("-")).map(PersonNames::stem)
                    .collect(Collectors.joining("-"));
        }
        if (!CYRILLIC.matcher(w).find() || w.indexOf('.') >= 0) {
            return w;
        }
        String fleeting = FLEETING.get(w);
        if (fleeting != null) {
            return fleeting;
        }
        String s = w;
        if (s.length() > 4 && (s.endsWith("ским") || s.endsWith("цким"))) {
            s = s.substring(0, s.length() - 2);
        } else {
            for (String ending : ENDINGS) {
                if (s.endsWith(ending) && s.length() - ending.length() >= 2) {
                    s = s.substring(0, s.length() - ending.length());
                    break;
                }
            }
        }
        // «Андрей — Андрея — Андреем»: хвостовые гласные основы тоже отбрасываем
        while (s.length() > 2 && "аеиоуыэюяйь".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Похоже на именительный падеж: каждое слово кончается как имя или фамилия в именительном
     * («Иванов Иван», «Иванова Анна», «John Smith»). Две разные такие записи с общей основой —
     * скорее разные люди («Евгений Иванов» и «Евгения Иванова»), их метки не склеиваются.
     */
    public static boolean looksNominative(String name) {
        for (String word : name.strip().split("\\s+")) {
            String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
            if (w.indexOf('.') >= 0 || !CYRILLIC.matcher(w).find()) {
                continue;
            }
            for (String part : w.split("-")) {
                if (OBLIQUE_WORD.matcher(part).find() || !NOMINATIVE_WORD.matcher(part).find()) {
                    return false;
                }
            }
        }
        return true;
    }
}
