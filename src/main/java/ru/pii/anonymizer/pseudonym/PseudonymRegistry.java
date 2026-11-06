package ru.pii.anonymizer.pseudonym;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import ru.pii.anonymizer.detect.BirthDateDetector;
import ru.pii.anonymizer.detect.Checksums;
import ru.pii.anonymizer.detect.PersonNames;
import ru.pii.anonymizer.detect.PiiType;

/**
 * Выдаёт метки вида {@code [PERSON_1]} в рамках одного документа.
 *
 * <p>Одно и то же значение всегда получает одну и ту же метку, даже если записано
 * по-разному: «+7 916 123-45-67» и «89161234567» — это один телефон.
 * Благодаря этому внешняя LLM видит, что во всех строках речь об одном человеке.
 *
 * <p>Таблица соответствия «метка → исходное значение» хранится только в памяти
 * и нужна для обратного восстановления ответа LLM. Методы синхронизированы:
 * шлюз использует один реестр для всех запросов.
 */
public final class PseudonymRegistry {

    private final boolean exactSpelling;
    private final BirthDateMode birthDateMode;
    private final Clock clock;

    private final Map<String, String> tokenByKey = new HashMap<>();
    private final Map<String, String> originalByToken = new LinkedHashMap<>();
    private final Map<PiiType, Integer> nextIndex = new EnumMap<>(PiiType.class);
    private final Map<PiiType, Integer> replacements = new EnumMap<>(PiiType.class);

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
     *                      вернуться именно как «8-916-…», а не в формате первого встреченного номера.
     */
    public PseudonymRegistry(boolean exactSpelling) {
        this(exactSpelling, BirthDateMode.DATE, Clock.systemDefaultZone());
    }

    /**
     * @param birthDateMode {@link BirthDateMode#AGE} — метка даты рождения несёт возраст в полных годах
     * @param clock         «сегодня» для расчёта возраста
     */
    public PseudonymRegistry(boolean exactSpelling, BirthDateMode birthDateMode, Clock clock) {
        this.exactSpelling = exactSpelling;
        this.birthDateMode = birthDateMode;
        this.clock = clock;
    }

    public synchronized String tokenFor(PiiType type, String value) {
        replacements.merge(type, 1, Integer::sum);
        String key = type + ":" + (exactSpelling ? value : normalize(type, value));
        String token = tokenByKey.get(key);
        if (token == null) {
            token = type == PiiType.PERSON && !exactSpelling ? personToken(value) : newToken(type, value);
            tokenByKey.put(key, token);
        }
        return token;
    }

    /**
     * «Иванов И.И.» получает метку «Иванова Ивана Ивановича» и наоборот, в каком бы порядке они ни встретились.
     * Если инициалы совпадают у двух разных полных ФИО, сокращение неоднозначно: второй человек получает свою
     * метку, а сокращения, встреченные дальше, — отдельную.
     */
    private String personToken(String value) {
        String initials = PersonNames.surnameAndInitials(value);
        if (initials == null) {
            return newToken(PiiType.PERSON, value);
        }
        boolean full = !PersonNames.isAbbreviated(value);
        String token = tokenByInitials.get(initials);
        if (token != null && !ambiguousInitials.contains(initials)) {
            if (!full) {
                return token;
            }
            if (!fullNameTokens.contains(token)) {
                // Метку выдали сокращению — восстанавливать лучше в полном виде
                originalByToken.put(token, value);
                fullNameTokens.add(token);
                return token;
            }
            ambiguousInitials.add(initials);
        }
        String created = newToken(PiiType.PERSON, value);
        if (token == null) {
            tokenByInitials.put(initials, created);
        }
        if (full) {
            fullNameTokens.add(created);
        }
        return created;
    }

    private String newToken(PiiType type, String value) {
        int index = nextIndex.merge(type, 1, Integer::sum);
        String suffix = "";
        if (type == PiiType.BIRTHDATE && birthDateMode == BirthDateMode.AGE) {
            OptionalInt age = BirthDateDetector.age(value, LocalDate.now(clock));
            if (age.isPresent()) {
                suffix = "_AGE" + age.getAsInt();
            }
        }
        String token = "[" + type + "_" + index + suffix + "]";
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
        Map<PiiType, Integer> unique = new EnumMap<>(PiiType.class);
        nextIndex.forEach(unique::put);
        return new AnonymizationSummary(new EnumMap<>(replacements), unique);
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
