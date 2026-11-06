package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import ru.pii.anonymizer.detect.BirthDateDetector;
import ru.pii.anonymizer.detect.PiiType;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание Excel (xlsx и xls).
 *
 * <ul>
 *   <li>Текстовые ячейки проверяются всеми детекторами.</li>
 *   <li>Числовые ячейки тоже: СНИЛС и ИНН часто хранятся как числа.</li>
 *   <li>Колонки по заголовку заменяются целиком: «ФИО», «Фамилия», «Сотрудник» — если значение
 *       похоже на имя; «Дата рождения» — если значение дата (в том числе ячейка формата «дата»);
 *       «Адрес», «Место жительства» — любой текст. Остальное в этих колонках проверяется детекторами.</li>
 *   <li>Формула, в кэше которой есть ПДн, заменяется обезличенным значением:
 *       кэш хранится в файле и прочитался бы любым парсером.</li>
 *   <li>Обрабатываются примечания к ячейкам, гиперссылки и автор документа в метаданных.</li>
 * </ul>
 */
@Component
public class ExcelProcessor implements DocumentProcessor {

    private static final Pattern PERSON_COLUMN_HEADER = Pattern.compile(
            "(?iu)\\s*(?:ф\\.?\\s*и\\.?\\s*о\\.?(?:\\s+\\S+)*|фамилия(?:[\\s,]+имя)?(?:[\\s,]+отчество)?"
                    + "|сотрудник|работник|full\\s*name|employee(?:\\s*name)?)\\s*");

    private static final Pattern BIRTHDATE_COLUMN_HEADER = Pattern.compile(
            "(?iu)\\s*(?:дат[аы]\\s*рожд.*|д\\.?\\s*р\\.?|др|birth\\s*date|date\\s+of\\s+birth|dob)\\s*");

    private static final Pattern ADDRESS_COLUMN_HEADER = Pattern.compile(
            "(?iu).*(?:адрес|address|место\\s+(?:жительства|проживания)|прописк|регистрац).*");

    private static final Pattern HAS_LETTERS = Pattern.compile("\\p{L}.*\\p{L}");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final Pattern NAME_LIKE = Pattern.compile("\\s*\\p{L}[\\p{L}.\\-' ]{1,80}");

    private final TextAnonymizer anonymizer;

    public ExcelProcessor(TextAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.XLSX || format == DocumentFormat.XLS;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : workbook) {
                processSheet(workbook, sheet, registry);
            }
            clearAuthor(workbook);
            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
            workbook.write(out);
            return out.toByteArray();
        } catch (EncryptedDocumentException e) {
            throw new DocumentProcessingException("Файл защищён паролем — снимите защиту и загрузите снова", e);
        }
    }

    private void processSheet(Workbook workbook, Sheet sheet, PseudonymRegistry registry) {
        Map<Integer, PiiType> piiColumns = new HashMap<>();
        Row headerRow = null;
        for (Row row : sheet) {
            if (headerRow == null && hasText(row)) {
                headerRow = row;
                collectPiiColumns(row, piiColumns);
            }
            for (Cell cell : row) {
                PiiType columnType = row == headerRow ? null : piiColumns.get(cell.getColumnIndex());
                processCell(workbook, cell, columnType, registry);
            }
        }
    }

    private void processCell(Workbook workbook, Cell cell, PiiType columnType, PseudonymRegistry registry) {
        String wholeValue = columnType == null ? null : wholeCellValue(cell, columnType);
        if (wholeValue != null) {
            cell.setCellValue(registry.tokenFor(columnType, wholeValue));
        } else {
            String original = valueAsText(cell);
            if (original != null) {
                String anonymized = anonymizer.anonymize(original, registry);
                if (!anonymized.equals(original)) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        cell.removeFormula();
                    }
                    cell.setCellValue(anonymized);
                }
            }
        }

        Hyperlink link = cell.getHyperlink();
        if (link != null && link.getAddress() != null && !anonymizer.detect(link.getAddress()).isEmpty()) {
            cell.removeHyperlink();
        }

        Comment comment = cell.getCellComment();
        if (comment != null && comment.getString() != null) {
            String text = comment.getString().getString();
            String anonymized = anonymizer.anonymize(text, registry);
            if (!anonymized.equals(text)) {
                comment.setString(workbook.getCreationHelper().createRichTextString(anonymized));
            }
            comment.setAuthor("");
        }
    }

    /** Текстовое представление значения для поиска ПДн или {@code null}, если искать нечего. */
    private static String valueAsText(Cell cell) {
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? null : integralAsText(cell.getNumericCellValue());
            default -> null;
        };
    }

    /**
     * Целое число без экспоненты: 1.1223344595E10 → «11223344595».
     * Дробные числа (суммы, зарплаты) не могут быть идентификаторами и пропускаются.
     */
    private static String integralAsText(double value) {
        if (value != Math.rint(value) || Double.isInfinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).toPlainString();
    }

    /**
     * Значение ячейки колонки ПДн, если его нужно заменить целиком, иначе {@code null}.
     * В колонке «ФИО» может оказаться не имя, а примечание или итоговая строка — такие
     * значения проверяются детекторами как обычный текст.
     */
    private static String wholeCellValue(Cell cell, PiiType columnType) {
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return columnType == PiiType.BIRTHDATE ? cell.getLocalDateTimeCellValue().toLocalDate().format(DATE) : null;
        }
        if (cell.getCellType() != CellType.STRING) {
            return null;
        }
        String value = cell.getStringCellValue();
        return switch (columnType) {
            case PERSON -> looksLikeName(value) ? value : null;
            case BIRTHDATE -> BirthDateDetector.isDate(value) ? value : null;
            case ADDRESS -> value.strip().length() >= 5 && HAS_LETTERS.matcher(value).find() ? value : null;
            default -> null;
        };
    }

    private static boolean looksLikeName(String value) {
        return NAME_LIKE.matcher(value).matches() && value.strip().split("\\s+").length <= 4;
    }

    private static boolean hasText(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void collectPiiColumns(Row header, Map<Integer, PiiType> columns) {
        for (Cell cell : header) {
            if (cell.getCellType() != CellType.STRING) {
                continue;
            }
            String name = cell.getStringCellValue();
            if (PERSON_COLUMN_HEADER.matcher(name).matches()) {
                columns.put(cell.getColumnIndex(), PiiType.PERSON);
            } else if (BIRTHDATE_COLUMN_HEADER.matcher(name).matches()) {
                columns.put(cell.getColumnIndex(), PiiType.BIRTHDATE);
            } else if (ADDRESS_COLUMN_HEADER.matcher(name).matches()) {
                columns.put(cell.getColumnIndex(), PiiType.ADDRESS);
            }
        }
    }

    private static void clearAuthor(Workbook workbook) {
        switch (workbook) {
            case XSSFWorkbook xssf -> {
                POIXMLProperties.CoreProperties core = xssf.getProperties().getCoreProperties();
                core.setCreator("");
                core.setLastModifiedByUser("");
            }
            case HSSFWorkbook hssf -> {
                SummaryInformation info = hssf.getSummaryInformation();
                if (info != null) {
                    info.setAuthor("");
                    info.setLastAuthor("");
                }
            }
            default -> {
            }
        }
    }
}
