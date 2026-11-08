package ru.pii.anonymizer.pseudonym;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import ru.pii.anonymizer.detect.BirthDateDetector;
import ru.pii.anonymizer.detect.Checksums;
import ru.pii.anonymizer.detect.PersonNames;
import ru.pii.anonymizer.detect.PiiType;

/**
 * Выдаёт метки вида {@code [PERSON_K7M2QX9D]}: тип и код, вычисленный из значения секретным ключом
 * ({@link TokenKey}). Настоящее значение наружу не уходит, а по коду его не восстановить без ключа.
 *
 * <p>Одно и то же значение всегда получает одну и ту же метку — в документе, во всех листах Excel,
 * в вопросе, в следующих запросах и после перезапуска. Разные написания тоже: «+7 916 123-45-67»
 * и «89161234567» — один телефон, «Иванов Иван», «Иванову Ивану» и «Иванов И.И.» — один человек.
 * Благодаря этому модель может считать по человеку суммы и группировки, а формулы Excel
 * (ВПР, СУММЕСЛИ по ФИО) продолжают работать на обезличенном файле.
 *
 * <p>Таблица «метка → исходное значение» хранится только в памяти и нужна для восстановления
 * ответа модели. Методы синхронизированы: шлюз использует один реестр для всех запросов.
 */
public final class PseudonymRegistry {

    /** Длина кода: 8 символов base32 = 40 бит, при совпадении кодов разных значений — длиннее. */
    private static final int CODE_LENGTH = 8;
    private static final int MAX_CODE_LENGTH = 16;

    private final boolean exactSpelling;
    private final BirthDateMode birthDateMode;
    private final Clock clock;
    private final TokenKey tokenKey;

    private final Map<String, String> tokenByKey = new HashMap<>();
    private final Map<String, String> originalByToken = new LinkedHashMap<>();
    /** Метка без хвоста возраста → из чего вычислен код: так ловится совпадение кодов разных значений. */
    private final Map<String, String> materialByToken = new HashMap<>();
    private final Map<PiiType, Integer> unique = new EnumMap<>(PiiType.class);
    private final Map<PiiType, Integer> replacements = new EnumMap<>(PiiType.class);

    /** Ключ ФИО без учёта падежа → метка: «Иванову Ивану» получает метку «Иванова Ивана». */
    private final Map<String, String> tokenByStem = new HashMap<>();
    private final Set<String> ambiguousStems = new HashSet<>();
    private final Map<String, List<String>> formsByToken = new HashMap<>();

    /** Ключ «фамилия + инициалы» → метка ФИО: связывает полную и сокращённую запись. */
    private final Map<String, String> tokenByInitials = new HashMap<>();
    private final Set<String> fullNameTokens = new HashSet<>();
    private final Set<String> ambiguousInitials = new HashSet<>();

    /** Реестр, в котором разные написания одного значения получают одну метку. */
    public PseudonymRegistry() {
        this(false);
    }

    /**
     * @param exactSpelling {@code true} — каждое написание получает свою метку. Нужно, когда
     *                      восстановленный текст попадает обратно в файлы: «8-916-…» должно
     *                      вернуться именно как «8-916-…», а «Иванову» — как «Иванову».
     */
    public PseudonymRegistry(boolean exactSpelling) {
        this(exactSpelling, BirthDateMode.DATE, Clock.systemDefaultZone(), TokenKey.ephemeral());
    }

    /**
     * @param birthDateMode {@link BirthDateMode#AGE} — метка даты рождения несёт возраст в полных годах
     * @param clock         «сегодня» для расчёта возраста
     * @param tokenKey      секретный ключ кодов меток
     */
    public PseudonymRegistry(boolean exactSpelling, BirthDateMode birthDateMode, Clock clock, TokenKey tokenKey) {
        this.exactSpelling = exactSpelling;
        this.birthDateMode = birthDateMode;
        this.clock = clock;
        this.tokenKey = tokenKey;
    }

    public synchronized String tokenFor(PiiType type, String value) {
        replacements.merge(type, 1, Integer::sum);
        String normalized = exactSpelling ? value : normalize(type, value);
        String key = type + ":" + normalized;
        String token = tokenByKey.get(key);
        if (token == null) {
            token = type == PiiType.PERSON && !exactSpelling ? personToken(value) : newToken(type, normalized, value);
            tokenByKey.put(key, token);
        }
        return token;
    }

    /**
     * Метка ФИО с учётом падежей и сокращений.
     *
     * <ul>
     *   <li>«Иванову Ивану», «Ивановым Иваном» получают метку «Иванова Ивана» — общая основа слов.</li>
     *   <li>Две разные записи в именительном падеже с общей основой — разные люди («Евгений Иванов»
     *       и «Евгения Иванова»): метки разные, а записи с этой основой дальше не склеиваются.</li>
     *   <li>«Иванов И.И.» получает метку «Иванова Ивана Ивановича» и наоборот. Если инициалы совпадают
     *       у двух разных полных ФИО, сокращение неоднозначно: второй человек получает свою метку,
     *       а сокращения, встреченные дальше, — отдельную.</li>
     * </ul>
     */
    private String personToken(String value) {
        String stemKey = PersonNames.caseInsensitiveKey(value);
        String byStem = tokenByStem.get(stemKey);
        if (byStem != null && !ambiguousStems.contains(stemKey)) {
            if (!conflicts(byStem, value)) {
                addForm(byStem, value);
                return byStem;
            }
            ambiguousStems.add(stemKey);
        }
        if (byStem != null) {
            return addForm(newToken(PiiType.PERSON, "spelling:" + normalize(PiiType.PERSON, value), value), value);
        }

        String initials = PersonNames.surnameAndInitials(value);
        if (initials == null) {
            String created = newToken(PiiType.PERSON, "stem:" + stemKey, value);
            tokenByStem.put(stemKey, created);
            return addForm(created, value);
        }
        boolean full = !PersonNames.isAbbreviated(value);
        String byInitials = tokenByInitials.get(initials);
        if (byInitials != null && !ambiguousInitials.contains(initials)) {
            if (!full || !fullNameTokens.contains(byInitials)) {
                if (full) {
                    fullNameTokens.add(byInitials);
                }
                tokenByStem.put(stemKey, byInitials);
                return addForm(byInitials, value);
            }
            ambiguousInitials.add(initials);
        }
        // Код из «фамилия + инициалы»: полная и сокращённая запись дают один код в любом запросе
        String created = newToken(PiiType.PERSON, byInitials == null ? "initials:" + initials : "stem:" + stemKey, value);
        if (byInitials == null) {
            tokenByInitials.put(initials, created);
        }
        if (full) {
            fullNameTokens.add(created);
        }
        tokenByStem.put(stemKey, created);
        return addForm(created, value);
    }

    /**
     * Новая запись в именительном падеже, отличная от уже известной в именительном, — другой человек.
     * Сокращения («Иванова И.И.») не сравниваются: их падеж не определить, связь с полным ФИО — по инициалам.
     */
    private boolean conflicts(String token, String value) {
        if (!PersonNames.looksNominative(value) || PersonNames.isAbbreviated(value)) {
            return false;
        }
        for (String form : formsByToken.getOrDefault(token, List.of())) {
            if (!PersonNames.isAbbreviated(form) && PersonNames.looksNominative(form) && !sameWords(form, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameWords(String a, String b) {
        return Arrays.equals(words(a), words(b));
    }

    private static String[] words(String name) {
        String[] words = name.strip().toLowerCase(Locale.ROOT).replace('ё', 'е').split("\\s+");
        Arrays.sort(words);
        return words;
    }

    /**
     * Запоминает запись человека и выбирает, какой из них восстанавливать ответ:
     * полную (а не сокращение), затем в именительном падеже, затем встреченную первой.
     */
    private String addForm(String token, String value) {
        List<String> forms = formsByToken.computeIfAbsent(token, _ -> new ArrayList<>());
        forms.add(value);
        String best = forms.getFirst();
        for (String form : forms) {
            if (rank(form) > rank(best)) {
                best = form;
            }
        }
        originalByToken.put(token, best);
        return token;
    }

    private static int rank(String form) {
        return (PersonNames.isAbbreviated(form) ? 0 : 2) + (PersonNames.looksNominative(form) ? 1 : 0);
    }

    private String newToken(PiiType type, String material, String value) {
        String fullMaterial = type + ":" + material;
        String base = null;
        for (int length = CODE_LENGTH; length <= MAX_CODE_LENGTH; length += 2) {
            String candidate = "[" + type + "_" + tokenKey.code(fullMaterial, length);
            String owner = materialByToken.get(candidate);
            if (owner == null || owner.equals(fullMaterial)) {
                base = candidate;
                break;
            }
        }
        if (base == null) {
            throw new IllegalStateException("Не удалось подобрать уникальный код метки");
        }
        materialByToken.put(base, fullMaterial);
        unique.merge(type, 1, Integer::sum);

        String suffix = "";
        if (type == PiiType.BIRTHDATE && birthDateMode == BirthDateMode.AGE) {
            OptionalInt age = BirthDateDetector.age(value, LocalDate.now(clock));
            if (age.isPresent()) {
                suffix = "_AGE" + age.getAsInt();
            }
        }
        String token = base + suffix + "]";
        originalByToken.put(token, value);
        return token;
    }

    /** Таблица соответствия для будущего восстановления. Содержит ПДн — не логировать. */
    public synchronized Map<String, String> mapping() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(originalByToken));
    }

    /** Исходное значение метки или {@code null}, если такой метки не выдавали. */
    public synchronized String originalOf(String token) {
        return originalByToken.get(token);
    }

    public synchronized AnonymizationSummary summary() {
        return new AnonymizationSummary(new EnumMap<>(replacements), new EnumMap<>(unique));
    }

    private static String normalize(PiiType type, String value) {
        return switch (type) {
            case PHONE -> {
                String digits = Checksums.digitsOnly(value);
                yield digits.startsWith("8") ? "7" + digits.substring(1) : digits;
            }
            case SNILS, INN, PASSPORT, CARD -> Checksums.identifierDigits(value);
            case EMAIL -> value.strip().toLowerCase(Locale.ROOT);
            case PERSON, ADDRESS -> value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            case BIRTHDATE -> value.strip().replaceAll("\\s+", " ");
        };
    }
}
