package ru.pii.anonymizer.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поиск имён без словаря — по заглавным буквам, на любом языке с прописными буквами
 * (русский, казахский, узбекский, английский…): «Анна Петрова», «Нурлан Абенов», «John Smith»,
 * «Ludwig van Beethoven», «Алиев Рашид оглы».
 *
 * <ul>
 *   <li>Два–четыре слова подряд с заглавной буквы в одной строке — это ФИО.</li>
 *   <li>Первое слово отбрасывается, если это начало фразы, а не имя: «Позвоните», «Напиши», «Сегодня»,
 *       должность или обращение («Директор», «Уважаемый»).</li>
 *   <li>Одно слово считается именем только после должности или обращения:
 *       «Директор Олег», «Привет, Саша», «Mr. Smith».</li>
 *   <li>Не трогаются названия после «г.», «ул.», «ООО» и т.п., слова в кавычках
 *       и заголовки markdown («# Трудовой Договор»).</li>
 * </ul>
 *
 * <p>Ограничения: одиночное имя или фамилия посреди фразы («позвони Саше», «Сидоров сказал»)
 * не находятся; два названия подряд с заглавной буквы («Нижний Новгород» без «г.», «Microsoft Excel»)
 * заменяются как имя — при восстановлении ответа они вернутся на место.
 */
public final class CapitalizedNameDetector implements PiiDetector {

    /** Слово с заглавной буквы: «Анна», «Jean-Luc», «O'Connor», «Сидорова-Петрова». */
    private static final Pattern WORD = Pattern.compile(
            "(?<![\\p{L}\\p{M}\\d'’])\\p{Lu}(?:[\\p{Ll}\\p{M}]+|(?=['’]\\p{Lu}))"
                    + "(?:['’-]\\p{Lu}?[\\p{Ll}\\p{M}]+)*(?![\\p{L}\\p{M}\\d])");

    /**
     * Между словами имени — один пробел или частица: «van», «де», «ибн».
     * Два пробела и табуляция разделяют колонки таблицы («Адрес  Зарплата»), а не части имени.
     */
    private static final Pattern BETWEEN = Pattern.compile(
            "[ \\u00A0](?:(?:van|von|der|den|de|da|di|du|del|della|la|le|bin|ibn|al|ван|фон|де|да|ибн|бин|аль)"
                    + "[ \\u00A0])?");

    /** Тюркское «сын/дочь» после имени: «Алиев Рашид оглы». */
    private static final Pattern TURKIC_SUFFIX = Pattern.compile(
            "[ \\t\\u00A0]+(?:оглы|кызы|улы|уулу|угли|ogly|oglu|kyzy|ugli)(?![\\p{L}\\p{M}])");

    /** Перед одиночным именем после обращения: «Привет, Саша», «Mr. Smith». */
    private static final Pattern AFTER_ADDRESS = Pattern.compile("[ \\t\\u00A0]*[,.]?[ \\t\\u00A0]+");

    private static final int MAX_WORDS = 4;

    /** Глагол в начале фразы: «Позвоните», «Передайте», «Начислить», «Получает». */
    private static final Pattern VERB_ENDING = Pattern.compile(
            "(?:ите|йте|ать|ять|ить|еть|уть|ает|яет|ует|ают|яют|уют|ешь|ишь)$");

    /**
     * Прошедшее время: «Подписал», «Утвердила». Совпадает с именами (Михаил, Павел, Алла),
     * поэтому отбрасывается только перед двумя и более словами с заглавной буквы.
     */
    private static final Pattern PAST_TENSE_ENDING = Pattern.compile("(?:ал|ял|ил|ел|ла|ли|ло|лся|лась)$");

    /** ФИО с отчеством или инициалами: границы у него точнее, группу с ним не трогаем. */
    private final PersonNameDetector exactNames = new PersonNameDetector();

    /** Слова в начале фразы, за которыми часто идёт имя, но сами они не имя. */
    private static final Set<String> NOT_NAMES = Set.of(
            // повелительное наклонение
            "напиши", "позвони", "отправь", "составь", "передай", "сообщи", "спроси", "найди", "проверь",
            "поздравь", "пригласи", "уведоми", "ответь", "скажи", "дай", "покажи", "выдай", "назначь", "уволь",
            "прими", "переведи", "начисли", "оформи", "свяжись", "подготовь", "напомни", "добавь", "удали",
            "поблагодари", "попроси", "прошу", "сообщаю", "направляю", "уведомляю", "спасибо", "пожалуйста",
            // местоимения, предлоги, союзы, наречия
            "во", "на", "по", "для", "от", "ко", "со", "об", "обо", "при", "из", "за", "до", "без", "про", "через",
            "это", "эти", "этот", "эта", "как", "кто", "что", "где", "когда", "куда", "почему", "зачем", "сколько",
            "мой", "моя", "мои", "наш", "наша", "наши", "ваш", "ваша", "ваши", "его", "её", "ее", "их",
            "он", "она", "они", "мы", "вы", "ты", "также", "однако", "если", "но", "так", "да", "нет", "все", "всё",
            "сегодня", "завтра", "вчера", "теперь", "потом", "затем", "здесь", "там", "ещё", "еще", "уже",
            "добрый", "доброе", "доброй", "итого", "итак", "кроме", "между", "после", "перед", "около",
            // английский
            "the", "an", "this", "that", "these", "those", "in", "on", "at", "for", "with", "from", "to", "by",
            "of", "our", "my", "your", "his", "her", "their", "we", "you", "he", "she", "they", "it", "if",
            "and", "but", "or", "so", "when", "where", "who", "what", "how", "why", "please", "thanks",
            "write", "call", "send", "ask", "tell", "email", "invite", "contact", "remind", "notify", "show",
            "find", "check", "give", "meet", "today", "tomorrow", "yesterday", "hello", "hi", "dear",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");

    /** Обращения на других языках; русские должности и обращения — в {@link Surnames#isNever}. */
    private static final Set<String> ADDRESS_WORDS = Set.of(
            "dear", "hello", "hi", "mr", "mrs", "ms", "miss", "dr", "sir", "madam", "prof", "mister");

    /** Перед названием, а не именем: «г. Нижний Новгород», «ООО Северный Ветер». */
    private static final Pattern PLACE_OR_COMPANY_BEFORE = Pattern.compile(
            "(?:(?<![\\p{L}])(?:г|гор|ул|пр|просп|пер|пл|обл|респ|с|пос|д|дер|ст|м|р-н|наб|бул|ш)\\.|"
                    + "(?<![\\p{L}])(?:город|городе|города|улица|улице|проспект|переулок|площадь|область|района?|"
                    + "республика|деревня|посёлок|поселок|станция|метро|ООО|ОАО|ЗАО|АО|ПАО|НКО|ГУП|МУП|ФГУП|"
                    + "LLC|Inc|Ltd|GmbH))"
                    + "[ \\t\\u00A0]*$");

    /** Сколько символов перед группой смотреть в поисках «г.», «ООО» и т.п. */
    private static final int LOOK_BEHIND_CHARS = 12;

    @Override
    public List<Finding> detect(String text) {
        List<int[]> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new int[] {m.start(), m.end()});
        }

        // «Премия Петровой Анне Сергеевне», «Согласовал Иванов И.И.»: ФИО выделит PersonNameDetector
        List<Finding> exact = exactNames.detect(text);

        List<Finding> findings = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            int j = i + 1;
            while (j < words.size() && BETWEEN.matcher(text).region(words.get(j - 1)[1], words.get(j)[0]).matches()) {
                j++;
            }
            int groupStart = words.get(i)[0];
            int groupEnd = words.get(j - 1)[1];
            if (exact.stream().noneMatch(f -> f.start() < groupEnd && groupStart < f.end())) {
                name(text, words.subList(i, j)).ifPresent(findings::add);
            }
            i = j;
        }
        return findings;
    }

    /** Имя внутри группы слов с заглавной буквы, стоящих подряд. */
    private Optional<Finding> name(String text, List<int[]> group) {
        int first = 0;
        // Отбрасываем слова-не-имена в начале: «Позвоните», «Директор», «Уважаемая»
        boolean sentenceStart = atSentenceStart(text, group.getFirst()[0]);
        while (first < group.size() && isNotName(text, group.get(first), first == 0 && sentenceStart)) {
            first++;
        }
        if (first == 0 && sentenceStart && group.size() >= 3 && isPastTense(text, group.getFirst())) {
            first = 1;
        }
        int count = group.size() - first;
        if (count == 0 || count > MAX_WORDS) {
            return Optional.empty();
        }
        int start = group.get(first)[0];
        int end = group.getLast()[1];
        if (count == 1 && !afterAddressWord(text, start)) {
            return Optional.empty();
        }
        if (isPlaceOrCompany(text, start) || inQuotes(text, start) || inMarkdownHeading(text, start)) {
            return Optional.empty();
        }
        Matcher suffix = TURKIC_SUFFIX.matcher(text).region(end, text.length());
        if (suffix.lookingAt()) {
            end = suffix.end();
        }
        return Optional.of(new Finding(PiiType.PERSON, start, end, text.substring(start, end)));
    }

    private static boolean isNotName(String text, int[] word, boolean sentenceStart) {
        String w = text.substring(word[0], word[1]);
        String lower = w.toLowerCase(Locale.ROOT);
        if (NOT_NAMES.contains(lower) || ADDRESS_WORDS.contains(lower) || Surnames.isNever(w)) {
            return true;
        }
        return sentenceStart && VERB_ENDING.matcher(lower).find();
    }

    private static boolean isPastTense(String text, int[] word) {
        return PAST_TENSE_ENDING.matcher(text.substring(word[0], word[1]).toLowerCase(Locale.ROOT)).find();
    }

    /** Перед словом — начало текста, конец предложения или перевод строки. */
    private static boolean atSentenceStart(String text, int start) {
        for (int k = start - 1; k >= 0; k--) {
            char c = text.charAt(k);
            if (c == '\n' || c == '\r' || c == '.' || c == '!' || c == '?' || c == '…') {
                return true;
            }
            if (!Character.isWhitespace(c) && "\"«“„'(-–—•*".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Перед словом стоит должность или обращение: «Директор Олег», «Привет, Саша», «Mr. Smith». */
    private static boolean afterAddressWord(String text, int start) {
        int k = start;
        while (k > 0 && !Character.isLetter(text.charAt(k - 1))) {
            k--;
        }
        if (!AFTER_ADDRESS.matcher(text).region(k, start).matches()) {
            return false;
        }
        int wordStart = k;
        while (wordStart > 0 && Character.isLetter(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        String previous = text.substring(wordStart, k);
        return !previous.isEmpty()
                && (Surnames.isNever(previous) || ADDRESS_WORDS.contains(previous.toLowerCase(Locale.ROOT)));
    }

    private static boolean isPlaceOrCompany(String text, int start) {
        int from = Math.max(0, start - LOOK_BEHIND_CHARS);
        Matcher before = PLACE_OR_COMPANY_BEFORE.matcher(text).region(from, start);
        before.useTransparentBounds(true);
        return before.find();
    }

    /** Название в кавычках: «Северный Ветер», "Golden Gate". */
    private static boolean inQuotes(String text, int start) {
        return start > 0 && "«\"“„".indexOf(text.charAt(start - 1)) >= 0;
    }

    private static boolean inMarkdownHeading(String text, int start) {
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        return text.startsWith("#", lineStart);
    }
}
