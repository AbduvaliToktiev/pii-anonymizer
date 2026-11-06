package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/**
 * Превращает уже обезличенный документ в текст для LLM.
 * Таблицы Excel выводятся построчно с разделителем «|», чтобы модель видела колонки.
 */
@Component
public class DocumentTextExtractor {

    public String toText(DocumentFormat format, byte[] content) throws IOException {
        return switch (format) {
            case XLSX, XLS -> excel(content);
            case DOCX -> word(content);
            case PDF -> pdf(content);
        };
    }

    private static String excel(byte[] content) throws IOException {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : workbook) {
                sb.append("## Лист «").append(sheet.getSheetName()).append("»\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    int lastColumn = Math.max(row.getLastCellNum(), 0);
                    for (int c = 0; c < lastColumn; c++) {
                        Cell cell = row.getCell(c);
                        line.append(c == 0 ? "" : " | ").append(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    if (!line.toString().replace("|", "").isBlank()) {
                        sb.append(line).append('\n');
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static String word(byte[] content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText().strip();
        }
    }

    private static String pdf(byte[] content) throws IOException {
        try (PDDocument doc = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(doc).strip();
        }
    }
}
