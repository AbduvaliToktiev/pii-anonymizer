package ru.pii.anonymizer.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import ru.pii.anonymizer.Tokens;
import ru.pii.anonymizer.SampleDocuments;
import ru.pii.anonymizer.detect.RussianPiiDetectors;
import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

class ExcelProcessorTest {

    private final ExcelProcessor processor = new ExcelProcessor(new TextAnonymizer(RussianPiiDetectors.all()));

    @Test
    void xlsxContainsNoPiiAfterProcessing() throws IOException {
        String text = Tokens.renumber(DocumentText.excel(processor.process(SampleDocuments.salaryXlsx(), new PseudonymRegistry())));

        assertThat(text).doesNotContain(SampleDocuments.PII_VALUES);
        assertThat(text).contains(SampleDocuments.KEPT_VALUES);
        assertThat(text).contains("[PERSON_1]", "[PERSON_2]", "[PERSON_3]", "[SNILS_3]", "[INN_2]", "[EMAIL_2]");
    }

    @Test
    void xlsContainsNoPiiAfterProcessing() throws IOException {
        String text = DocumentText.excel(processor.process(SampleDocuments.salaryXls(), new PseudonymRegistry()));

        assertThat(text).doesNotContain(SampleDocuments.PII_VALUES);
        assertThat(text).contains(SampleDocuments.KEPT_VALUES);
    }

    @Test
    void salariesStayNumbersSoLlmCanCalculate() throws IOException {
        byte[] result = processor.process(SampleDocuments.salaryXlsx(), new PseudonymRegistry());

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            Row first = workbook.getSheetAt(0).getRow(1);
            assertThat(first.getCell(7).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(7).getNumericCellValue()).isEqualTo(120000d);
        }
    }

    @Test
    void phoneFromNoteGetsSameTokenAsInTable() throws IOException {
        String text = Tokens.renumber(DocumentText.excel(processor.process(SampleDocuments.salaryXlsx(), new PseudonymRegistry())));

        // +7 916 123-45-67 в таблице и 8-916-123-45-67 в примечании — один и тот же номер
        assertThat(text).contains("тел. [PHONE_1]");
    }

    @Test
    void formulaWithPiiInCachedValueKeepsFormulaAndGetsAnonymizedResult() throws IOException {
        byte[] source;
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("Контакт: ivanov@example.ru");
            sheet.createRow(1).createCell(0).setCellFormula("A1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            source = out.toByteArray();
        }

        byte[] result = processor.process(source, new PseudonymRegistry());

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(cell.getCellFormula()).isEqualTo("A1");
            assertThat(Tokens.renumber(cell.getStringCellValue())).isEqualTo("Контакт: [EMAIL_1]");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo(cell.getStringCellValue());
        }
    }
}
