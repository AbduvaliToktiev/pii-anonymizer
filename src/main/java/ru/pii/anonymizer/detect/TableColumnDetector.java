package ru.pii.anonymizer.detect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Колонки таблиц в обычном тексте: вывод скриптов (pandas, openpyxl), CSV, markdown-таблицы.
 *
 * <p>Через шлюз Claude Code читает Excel скриптом — в его выводе нет «колонки ФИО», есть только
 * строки текста. Детектор ищет строку-заголовок с «ФИО», «Дата рождения» или «Адрес», определяет
 * разделитель колонок ({@code |}, табуляция, {@code ;}, выравнивание двумя и более пробелами или
 * запятая — CSV и строки-кортежи Python вида {@code ('ФИО', 'Дата рождения')}) и заменяет значения
 * этих колонок во всех строках ниже, до пустой строки или смены формата.
 */
public final class TableColumnDetector implements PiiDetector {

    private static final Map<PiiType, Pattern> HEADERS = new LinkedHashMap<>();

    static {
        HEADERS.put(PiiType.BIRTHDATE, Pattern.compile(
                "(?iu)дат[аы]\\s*рожд|^д\\.?\\s*р\\.?$|^др$|birth|^dob$"));
        HEADERS.put(PiiType.ADDRESS, Pattern.compile(
                "(?iu)адрес|address|место\\s+(?:жительства|проживания)|прописк|регистрац"));
        HEADERS.put(PiiType.PERSON, Pattern.compile(
                "(?iu)^(?:ф\\.?\\s*и\\.?\\s*о\\.?.*|фамилия.*|сотрудник|работник|клиент|full\\s*name|employee(?:\\s*name)?)$"));
    }

    /**
     * Запятая: CSV и вывод {@code print(row)} — кортежи и списки Python. Ячейки делятся только
     * запятыми вне кавычек и скобок, поэтому {@code datetime.datetime(1985, 3, 12, 0, 0)} и
     * {@code "Москва, ул. Ленина, д. 1"} остаются одной ячейкой; кавычки вокруг значения отбрасываются.
     */
    private static final Pattern SEQUENCE = Pattern.compile(",");

    /** Разделители по убыванию надёжности; пробелы и запятая — последними. */
    private static final List<Pattern> DELIMITERS = List.of(
            Pattern.compile("\\|"), Pattern.compile("\\t"), Pattern.compile(";"), Pattern.compile(" {2,}"), SEQUENCE);

    /** Номер строки, который Claude Code добавляет при чтении файла: «    12→текст» или «12\tтекст». */
    private static final Pattern READ_TOOL_PREFIX = Pattern.compile("^\\s*\\d+(?:→|\\t)");

    /** Типичные названия колонок — для таблиц из двух колонок. */
    private static final Pattern COMMON_HEADER = Pattern.compile(
            "(?iu)(?:телефон|тел\\.?|e-?mail|почта|отдел|подразделение|должность|зарплата|оклад|сумма|дата.*"
                    + "|возраст|паспорт|снилс|инн|пол|город|комментари.*|примечани.*|№|n|id|name|phone|department|salary)");

    private static final Pattern NAME_LIKE =Pattern.compile("[А-ЯЁA-Z][\\p{L}.\\-' ]{1,80}",
            Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern HAS_LETTERS = Pattern.compile("\\p{L}.*\\p{L}", Pattern.UNICODE_CHARACTER_CLASS);

    private record Cell(int start, int end) {
        String value(String text) {
            return text.substring(start, end);
        }
    }

    @Override
    public List<Finding> detect(String text) {
        List<int[]> lines = lines(text);
        List<Finding> findings = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            int next = i + 1;
            for (Pattern delimiter : DELIMITERS) {
                List<Cell> header = cells(text, lines.get(i), delimiter);
                if (header.size() < 2) {
                    continue;
                }
                Map<Integer, PiiType> columns = classify(text, header);
                if (columns.isEmpty() || !looksLikeHeader(text, header, columns)) {
                    continue;
                }
                next = scanRows(text, lines, i + 1, delimiter, header.size(), columns, findings);
                break;
            }
            i = next;
        }
        return findings;
    }

    /** Строки таблицы под заголовком; возвращает индекс первой строки после таблицы. */
    private int scanRows(String text, List<int[]> lines, int from, Pattern delimiter, int width,
                         Map<Integer, PiiType> columns, List<Finding> findings) {
        int j = from;
        for (; j < lines.size(); j++) {
            int[] line = lines.get(j);
            if (text.substring(line[0], line[1]).isBlank()) {
                break;
            }
            List<Cell> row = cells(text, line, delimiter);
            int shift;
            if (row.size() == width) {
                shift = 0;
            } else if (row.size() == width + 1) {
                shift = 1; // индекс строки слева, как в выводе pandas
            } else {
                break;
            }
            for (Map.Entry<Integer, PiiType> column : columns.entrySet()) {
                Cell cell = row.get(column.getKey() + shift);
                String value = cell.value(text);
                if (matches(column.getValue(), value)) {
                    findings.add(new Finding(column.getValue(), cell.start(), cell.end(), value));
                }
            }
        }
        return j;
    }

    private static boolean matches(PiiType type, String value) {
        return switch (type) {
            case BIRTHDATE -> BirthDateDetector.isDate(value);
            case ADDRESS -> value.length() >= 5 && HAS_LETTERS.matcher(value).find()
                    && !HEADERS.get(PiiType.ADDRESS).matcher(value).find();
            case PERSON -> NAME_LIKE.matcher(value).matches() && value.split("\\s+").length <= 4
                    && !HEADERS.get(PiiType.PERSON).matcher(value).matches();
            default -> false;
        };
    }

    /**
     * Строка «ФИО  Иванов Иван» — это пара «ключ — значение», а не заголовок таблицы.
     * Поэтому заголовок из двух колонок принимается, только если обе похожи на названия колонок.
     */
    private static boolean looksLikeHeader(String text, List<Cell> header, Map<Integer, PiiType> columns) {
        if (header.size() >= 3) {
            return true;
        }
        for (int c = 0; c < header.size(); c++) {
            if (!columns.containsKey(c) && !COMMON_HEADER.matcher(header.get(c).value(text)).matches()) {
                return false;
            }
        }
        return true;
    }

    private static Map<Integer, PiiType> classify(String text, List<Cell> header) {
        Map<Integer, PiiType> columns = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c).value(text);
            for (Map.Entry<PiiType, Pattern> h : HEADERS.entrySet()) {
                if (h.getValue().matcher(name).find()) {
                    columns.put(c, h.getKey());
                    break;
                }
            }
        }
        return columns;
    }

    /** Ячейки строки без пробелов по краям; пустые ячейки в начале и в конце отбрасываются. */
    private static List<Cell> cells(String text, int[] line, Pattern delimiter) {
        int start = line[0];
        Matcher prefix = READ_TOOL_PREFIX.matcher(text).region(line[0], line[1]);
        prefix.useAnchoringBounds(true);
        if (prefix.lookingAt()) {
            start = prefix.end();
        }
        if (delimiter == SEQUENCE) {
            return sequenceCells(text, start, line[1]);
        }
        List<Cell> cells = new ArrayList<>();
        Matcher m = delimiter.matcher(text).region(start, line[1]);
        int cellStart = start;
        while (m.find()) {
            cells.add(trim(text, cellStart, m.start()));
            cellStart = m.end();
        }
        cells.add(trim(text, cellStart, line[1]));
        while (!cells.isEmpty() && cells.getFirst().start() == cells.getFirst().end()) {
            cells.removeFirst();
        }
        while (!cells.isEmpty() && cells.getLast().start() == cells.getLast().end()) {
            cells.removeLast();
        }
        return cells;
    }

    /**
     * Ячейки строки CSV или кортежа/списка Python: {@code ('ФИО', 'Дата рождения')},
     * {@code [('ФИО', ...),}, {@code ФИО,"Москва, ул. Ленина"}. Внешние скобки строки
     * отбрасываются; запятые внутри кавычек и вложенных скобок ячейки не делят.
     */
    private static List<Cell> sequenceCells(String text, int start, int end) {
        Cell line = trim(text, start, end);
        int s = line.start();
        int e = line.end();
        if (e > s && text.charAt(e - 1) == ',') {
            e = trim(text, s, e - 1).end();
        }
        int opened = 0;
        while (opened < 2 && s < e && (text.charAt(s) == '(' || text.charAt(s) == '[')) {
            s++;
            opened++;
        }
        while (opened > 0 && e > s && (text.charAt(e - 1) == ')' || text.charAt(e - 1) == ']')) {
            e--;
            opened--;
            if (opened > 0 && e > s && text.charAt(e - 1) == ',') {
                e--;
            }
        }
        List<Cell> cells = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int cellStart = s;
        for (int i = s; i < e; i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if ((ch == ')' || ch == ']' || ch == '}') && depth > 0) {
                depth--;
            } else if (ch == ',' && depth == 0) {
                cells.add(unquote(text, trim(text, cellStart, i)));
                cellStart = i + 1;
            }
        }
        if (quote != 0 || depth != 0) {
            return List.of(); // незакрытая кавычка или скобка — это не строка таблицы
        }
        cells.add(unquote(text, trim(text, cellStart, e)));
        return cells;
    }

    /** {@code 'значение'} или {@code "значение"} → границы значения без кавычек. */
    private static Cell unquote(String text, Cell cell) {
        if (cell.end() - cell.start() >= 2) {
            char first = text.charAt(cell.start());
            if ((first == '\'' || first == '"') && text.charAt(cell.end() - 1) == first) {
                return new Cell(cell.start() + 1, cell.end() - 1);
            }
        }
        return cell;
    }

    private static Cell trim(String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new Cell(start, end);
    }

    private static List<int[]> lines(String text) {
        List<int[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                lines.add(new int[] {start, end});
                start = i + 1;
            }
        }
        return lines;
    }
}
