package ru.pii.anonymizer.detect;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Проверка контрольных сумм российских идентификаторов и банковских карт.
 * Отсекает ложные срабатывания: не каждые 11 цифр являются СНИЛС.
 */
public final class Checksums {

    private static final int[] INN10_WEIGHTS = {2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] INN12_WEIGHTS_11 = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] INN12_WEIGHTS_12 = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    private Checksums() {
    }

    /** СНИЛС: 11 цифр, последние две — контрольное число. */
    public static boolean isValidSnils(String digits) {
        if (!isDigits(digits, 11)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += digit(digits, i) * (9 - i);
        }
        int expected;
        if (sum < 100) {
            expected = sum;
        } else if (sum <= 101) {
            expected = 0;
        } else {
            expected = sum % 101;
            if (expected == 100) {
                expected = 0;
            }
        }
        return expected == Integer.parseInt(digits.substring(9));
    }

    /** ИНН юридического лица (10 цифр) или физического лица (12 цифр). */
    public static boolean isValidInn(String digits) {
        if (isDigits(digits, 10)) {
            return innCheckDigit(digits, INN10_WEIGHTS) == digit(digits, 9);
        }
        if (isDigits(digits, 12)) {
            return innCheckDigit(digits, INN12_WEIGHTS_11) == digit(digits, 10)
                    && innCheckDigit(digits, INN12_WEIGHTS_12) == digit(digits, 11);
        }
        return false;
    }

    /** Номер банковской карты: 13–19 цифр, алгоритм Луна. */
    public static boolean isValidCard(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19 || !isDigits(digits, digits.length())) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digit(digits, i);
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    public static String digitsOnly(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Цифры идентификатора с учётом записи числом: {@code 7707083893.0} и {@code 7.707083893E9}
     * дают «7707083893», а не цифры вместе с дробной частью или показателем степени.
     */
    public static String identifierDigits(String value) {
        String s = value.strip();
        if (NUMBER.matcher(s).matches()) {
            try {
                BigDecimal number = new BigDecimal(s);
                if (number.stripTrailingZeros().scale() <= 0) {
                    return number.toBigInteger().toString();
                }
            } catch (NumberFormatException e) {
                // не число — обычный разбор ниже
            }
        }
        return digitsOnly(value);
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+\\.0+|\\d\\.\\d+[Ee]\\+?\\d{1,2}");

    private static int innCheckDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += digit(digits, i) * weights[i];
        }
        return sum % 11 % 10;
    }

    private static boolean isDigits(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int digit(String digits, int index) {
        return digits.charAt(index) - '0';
    }
}
