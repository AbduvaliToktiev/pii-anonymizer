package ru.pii.anonymizer.detect;

import static ru.pii.anonymizer.detect.PersonNameDetector.PATRONYMIC;
import static ru.pii.anonymizer.detect.PersonNameDetector.SURNAME;
import static ru.pii.anonymizer.detect.PersonNameDetector.UPPER;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        return (surname + " " + name.charAt(0) + patronymic.charAt(0)).toLowerCase(Locale.ROOT);
    }
}
