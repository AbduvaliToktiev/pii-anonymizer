package ru.pii.anonymizer.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.pii.anonymizer.LargeSamples;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;
import ru.pii.anonymizer.pseudonym.Restorer;

/**
 * Книга с несколькими листами и формулами: после обезличивания формулы на месте, ПДн нет нигде в файле,
 * а пересчёт на обезличенных данных даёт те же итоги — ВПР и СЧЁТЕСЛИ по ФИО находят тех же людей,
 * потому что одно ФИО на всех листах получило одну метку.
 */
class ExcelFormulasTest {

    private static final int EMPLOYEES = 300;

    private final ExcelProcessor processor = new ExcelProcessor(new TextAnonymizer(RussianPiiDetectors.all()));

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void formulasSurviveAndRecalculateToSameTotals(String format) throws IOException {
        Supplier<Workbook> workbook = format.equals("xlsx") ? XSSFWorkbook::new : HSSFWorkbook::new;
        LargeSamples.Staff staff = LargeSamples.staff(workbook.get(), EMPLOYEES);

        byte[] result = processor.process(staff.content(), new PseudonymRegistry());

        assertThat(RawBytes.leaked(result, staff.pii())).isEmpty();
        try (Workbook before = WorkbookFactory.create(new ByteArrayInputStream(staff.content()));
             Workbook after = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            assertThat(formulas(after)).isEqualTo(formulas(before));

            FormulaEvaluator evaluator = after.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateAll();

            Sheet summary = after.getSheet("Итоги по отделам");
            Sheet expected = before.getSheet("Итоги по отделам");
            for (int r = 1; r <= LargeSamples.DEPARTMENTS.length + 1; r++) {
                for (int c = 1; c <= 5; c++) {
                    assertThat(summary.getRow(r).getCell(c).getNumericCellValue())
                            .as("Итоги по отделам, строка %d, колонка %d", r + 1, c + 1)
                            .isCloseTo(expected.getRow(r).getCell(c).getNumericCellValue(), org.assertj.core.data.Offset.offset(0.01));
                }
            }

            // ВПР по ФИО нашёл отдел и оклад каждого сотрудника на обезличенном листе
            Sheet payroll = after.getSheet("Начисления");
            for (int r = 1; r <= EMPLOYEES; r++) {
                Cell department = payroll.getRow(r).getCell(2);
                assertThat(department.getCachedFormulaResultType()).as("ВПР в строке %d", r + 1).isEqualTo(CellType.STRING);
                assertThat(LargeSamples.DEPARTMENTS).contains(department.getStringCellValue());
                assertThat(payroll.getRow(r).getCell(0).getStringCellValue()).matches(Restorer.TOKEN.pattern());
            }

            // ИНДЕКС/ПОИСКПОЗ вернул метку, ВПР по ней — оклад, СЧЁТЕСЛИ с ФИО в кавычках — тот же человек
            Sheet info = after.getSheet("Справка");
            assertThat(info.getRow(0).getCell(1).getStringCellValue()).matches(Restorer.TOKEN.pattern());
            assertThat(info.getRow(1).getCell(1).getNumericCellValue())
                    .isEqualTo(before.getSheet("Справка").getRow(1).getCell(1).getNumericCellValue());
            assertThat(info.getRow(2).getCell(1).getStringCellValue()).startsWith("Ответственный: [PERSON_");
            assertThat(info.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(1d);
        }
    }

    /**
     * 10 000 строк: после замены ФИО в общей таблице строк не остаётся ни исходных, ни пустых записей —
     * иначе файл сжимается так сильно, что POI принимает его за zip-бомбу и не читает.
     */
    @org.junit.jupiter.api.Test
    void largeWorkbookStaysReadableAndSharedStringsAreCompacted() throws IOException {
        LargeSamples.Staff staff = LargeSamples.staff(new XSSFWorkbook(), 10_000);

        byte[] result = processor.process(staff.content(), new PseudonymRegistry());

        String text = new DocumentTextExtractor().toText(DocumentFormat.XLSX, result);
        assertThat(text).contains("## Лист «Начисления»", "Октябрь 2026").doesNotContain(staff.names().getFirst());
        String sst;
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(result))) {
            java.util.zip.ZipEntry entry;
            do {
                entry = zip.getNextEntry();
            } while (!entry.getName().equals("xl/sharedStrings.xml"));
            sst = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(sst).doesNotContain("<si><t></t></si>", "<si><t/></si>");
    }

    /** Все формулы книги по порядку: лист, адрес, текст формулы без строк в кавычках. */
    private static List<String> formulas(Workbook workbook) {
        List<String> result = new ArrayList<>();
        for (Sheet sheet : workbook) {
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        result.add(sheet.getSheetName() + "!" + cell.getAddress() + "="
                                + cell.getCellFormula().replaceAll("\"[^\"]*\"", "\"…\""));
                    }
                }
            }
        }
        return result;
    }
}
