package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Извлечение всего текста документа для проверок в тестах. */
final class DocumentText {

    private DocumentText() {
    }

    static String excel(byte[] content) {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        sb.append(formatter.formatCellValue(cell)).append('\t');
                        Comment comment = cell.getCellComment();
                        if (comment != null) {
                            sb.append(comment.getString().getString()).append(' ').append(comment.getAuthor()).append('\t');
                        }
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String word(byte[] content) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText() + "\n" + doc.getProperties().getCoreProperties().getCreator();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String pdf(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(doc) + "\n" + doc.getDocumentInformation().getAuthor();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
