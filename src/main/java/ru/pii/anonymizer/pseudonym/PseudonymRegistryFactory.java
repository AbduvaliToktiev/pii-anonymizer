package ru.pii.anonymizer.pseudonym;

import java.time.Clock;

/** Создаёт реестры меток с настройками сервиса (режим дат рождения). */
public final class PseudonymRegistryFactory {

    private final BirthDateMode birthDateMode;
    private final Clock clock;

    public PseudonymRegistryFactory(BirthDateMode birthDateMode, Clock clock) {
        this.birthDateMode = birthDateMode == null ? BirthDateMode.DATE : birthDateMode;
        this.clock = clock;
    }

    /** Реестр на один запрос: разные написания одного значения получают одну метку. */
    public PseudonymRegistry create() {
        return create(false);
    }

    /** @param exactSpelling см. {@link PseudonymRegistry#PseudonymRegistry(boolean)} */
    public PseudonymRegistry create(boolean exactSpelling) {
        return new PseudonymRegistry(exactSpelling, birthDateMode, clock);
    }
}
