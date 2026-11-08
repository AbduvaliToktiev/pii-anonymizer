package ru.pii.anonymizer.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Эвристический поиск ФИО на русском языке без ML-модели.
 *
 * <p>Опорная точка — пара «Имя Отчество»: отчество узнаётся по суффиксам
 * -вич, -вн, -ич, -ичн (с падежными окончаниями). К паре присоединяется фамилия
 * после неё («Иван Иванович Иванов») или, если её нет, перед ней («Иванов Иван Иванович»).
 * Отдельно распознаются формы с инициалами: «Иванов И.И.» и «И.И. Иванов»; при выборе между ними
 * («Подписал И. И. Иванов») фамилия узнаётся по окончанию.
 *
 * <p>Ограничение: ФИО без отчества («Иван Иванов») в свободном тексте не распознаются —
 * для этого нужен NER. В таблицах такие значения покрываются колонками «ФИО».
 */
public final class PersonNameDetector implements PiiDetector {

    static final String UPPER = "[А-ЯЁ]";
    static final String LOWER = "[а-яё]";
    static final String SURNAME = UPPER + LOWER + "+(?:-" + UPPER + LOWER + "+)?";
    private static final String NAME = UPPER + LOWER + "+";
    static final String PATRONYMIC = UPPER + LOWER + "+(?:вич|вн|ичн|ич)" + LOWER + "{0,3}";
    private static final String NOT_LETTER_BEFORE = "(?<!\\p{L})";
    private static final String NOT_LETTER_AFTER = "(?!\\p{L})";

    private static final Pattern NAME_AND_PATRONYMIC = compile(
            NOT_LETTER_BEFORE + NAME + "\\s+" + PATRONYMIC + NOT_LETTER_AFTER);
    /**
     * Фамилия — только в той же строке: в таблице, выведенной построчно, следующая ячейка
     * («Иван Иванович» ↵ «Продажи») — не фамилия, а настоящая фамилия стоит перед именем.
     */
    private static final Pattern SURNAME_AFTER = compile(
            "\\h+" + SURNAME + NOT_LETTER_AFTER);
    private static final Pattern SURNAME_BEFORE = compile(
            NOT_LETTER_BEFORE + SURNAME + "\\h+$");
    private static final String INITIALS = UPPER + "\\.\\s?" + UPPER + "\\.";
    private static final Pattern SURNAME_THEN_INITIALS = compile(
            NOT_LETTER_BEFORE + "(?<surname>" + SURNAME + ")\\s+" + INITIALS);
    private static final Pattern INITIALS_THEN_SURNAME = compile(
            NOT_LETTER_BEFORE + INITIALS + "\\s?(?<surname>" + SURNAME + ")" + NOT_LETTER_AFTER);

    /** Насколько далеко назад искать фамилию перед «Имя Отчество». */
    private static final int LOOK_BEHIND_CHARS = 40;

    private static Pattern compile(String regex) {
        // UNICODE_CHARACTER_CLASS: \s покрывает неразрывный пробел, который часто встречается в Word
        return Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
    }

    @Override
    public List<Finding> detect(String text) {
        List<Finding> findings = new ArrayList<>();

        Matcher core = NAME_AND_PATRONYMIC.matcher(text);
        while (core.find()) {
            int start = core.start();
            int end = core.end();

            Matcher after = SURNAME_AFTER.matcher(text).region(end, text.length());
            boolean hasAfter = after.lookingAt();
            int from = Math.max(0, start - LOOK_BEHIND_CHARS);
            Matcher before = SURNAME_BEFORE.matcher(text).region(from, start);
            before.useTransparentBounds(true);
            boolean hasBefore = before.find() && !Surnames.isNever(before.group().strip());
            // «Иванов Иван Иванович Продажи» (строка таблицы): фамилия — то слово, что похоже на неё по окончанию
            boolean preferBefore = hasBefore && Surnames.looksLike(before.group().strip())
                    && !(hasAfter && Surnames.looksLike(after.group().strip()));
            if (hasAfter && !preferBefore) {
                end = after.end();
            } else if (hasBefore) {
                start = before.start();
            }
            findings.add(new Finding(PiiType.PERSON, start, end, text.substring(start, end)));
            core.region(end, text.length());
        }

        List<MatchResult> surnameFirst = SURNAME_THEN_INITIALS.matcher(text).results()
                .filter(PersonNameDetector::hasPossibleSurname).toList();
        List<MatchResult> initialsFirst = new ArrayList<>(INITIALS_THEN_SURNAME.matcher(text).results()
                .filter(PersonNameDetector::hasPossibleSurname).toList());
        for (MatchResult a : surnameFirst) {
            MatchResult rival = initialsFirst.stream()
                    .filter(b -> b.start() < a.end() && a.start() < b.end())
                    .findFirst().orElse(null);
            if (rival != null && prefersSurnameAfter(a.group("surname"), rival.group("surname"))) {
                continue;
            }
            initialsFirst.remove(rival);
            findings.add(new Finding(PiiType.PERSON, a.start(), a.end(), a.group()));
        }
        for (MatchResult b : initialsFirst) {
            findings.add(new Finding(PiiType.PERSON, b.start(), b.end(), b.group()));
        }
        return findings;
    }

    private static boolean hasPossibleSurname(MatchResult match) {
        return !Surnames.isNever(match.group("surname"));
    }

    /**
     * «Подписал И. И. Иванов» и «Иванов И.И. Приказ…» записаны одинаково: слово, инициалы, слово.
     * Фамилией считаем то слово, которое на неё похоже по окончанию; если похожи оба или ни одно —
     * слово перед инициалами, как в «Иванов И.И.».
     */
    private static boolean prefersSurnameAfter(String before, String after) {
        return Surnames.looksLike(after) && !Surnames.looksLike(before);
    }
}
