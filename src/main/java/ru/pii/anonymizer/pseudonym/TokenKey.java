package ru.pii.anonymizer.pseudonym;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Секретный ключ, из которого вычисляются коды меток: {@code [PERSON_K7M2QX9D]}.
 *
 * <p>Код — первые биты HMAC-SHA256(ключ, значение) в base32 (алфавит Крокфорда, без похожих
 * на цифры I, L, O, U). Одно значение всегда даёт один код — в любом запросе и после перезапуска,
 * поэтому модель видит, что во всех строках и файлах речь об одном человеке, и может считать по нему
 * суммы и группировки. Без ключа по коду нельзя ни восстановить значение, ни проверить догадку
 * («это Иванов?»): ключ хранится только у сервиса и в сеть не уходит.
 */
public final class TokenKey {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int KEY_BYTES = 32;

    /** Ключ процесса для реестров, созданных без настроек (тесты, утилиты). */
    private static final TokenKey EPHEMERAL = random();

    private final SecretKeySpec key;

    private TokenKey(byte[] key) {
        if (key.length < 16) {
            throw new IllegalArgumentException("Ключ меток должен быть не короче 16 байт");
        }
        this.key = new SecretKeySpec(key.clone(), "HmacSHA256");
    }

    public static TokenKey of(byte[] key) {
        return new TokenKey(key);
    }

    public static TokenKey random() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return new TokenKey(key);
    }

    static TokenKey ephemeral() {
        return EPHEMERAL;
    }

    /**
     * Ключ из настройки (base64) или из файла. Если файла нет, создаётся новый случайный ключ:
     * коды меток остаются прежними, пока файл не удалён.
     */
    public static TokenKey load(String base64, Path file) {
        if (base64 != null && !base64.isBlank()) {
            return new TokenKey(Base64.getDecoder().decode(base64.strip()));
        }
        try {
            if (Files.exists(file)) {
                return new TokenKey(Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip()));
            }
            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(key), StandardCharsets.US_ASCII);
            restrictToOwner(file);
            return new TokenKey(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать или создать ключ меток " + file, e);
        }
    }

    /** Код из {@code chars} символов base32 (5 бит на символ). */
    public String code(String material, int chars) {
        byte[] digest;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            digest = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 недоступен", e);
        }
        StringBuilder sb = new StringBuilder(chars);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; sb.length() < chars; i++) {
            buffer = (buffer << 8) | (digest[i] & 0xFF);
            bits += 8;
            while (bits >= 5 && sb.length() < chars) {
                bits -= 5;
                sb.append(ALPHABET[(buffer >> bits) & 0x1F]);
            }
        }
        return sb.toString();
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: права наследуются от папки пользователя
        }
    }
}
