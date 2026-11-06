package ru.pii.anonymizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/**
 * Тестовые документы с вымышленными персональными данными.
 * СНИЛС и ИНН сгенерированы с корректными контрольными суммами, но реальным людям не принадлежат.
 */
public final class SampleDocuments {

    /** Все значения, которых не должно остаться в обезличенных файлах. */
    public static final List<String> PII_VALUES = List.of(
            "Иванов", "Петрова", "Сидоров", "Кузнецов",
            "112-233-445 95", "11223344595", "12345678964", "987-654-321 83",
            "500100732259", "771234567859", "7707083893",
            "+7 916 123-45-67", "8 (903) 765-43-21", "+79035554433", "8-916-123-45-67",
            "ivanov@example.ru", "anna.petrova@example.ru", "hr@example.ru",
            "45 06 123456", "4111 1111 1111 1111",
            "1985", "05.07.1990", "1990", "1988-11-23", "Ленина", "Химки", "Тверская");

    /** Данные, которые должны сохраниться: без них LLM не сможет ничего посчитать. */
    public static final List<String> KEPT_VALUES = List.of("Продажи", "ИТ", "Менеджер", "Разработчик");

    private static final String[] HEADER = {"ФИО", "СНИЛС", "ИНН", "Телефон", "Email", "Отдел", "Должность", "Зарплата",
            "Дата рождения", "Адрес"};

    private static final Object[][] EMPLOYEES = {
            {"Иванов Иван Иванович", "112-233-445 95", 500100732259d, "+7 916 123-45-67", "ivanov@example.ru",
                    "Продажи", "Менеджер", 120000d, LocalDate.of(1985, 3, 12), "г. Москва, ул. Ленина, д. 5, кв. 12"},
            {"Петрова Анна Сергеевна", 12345678964d, 771234567859d, "8 (903) 765-43-21", "anna.petrova@example.ru",
                    "ИТ", "Разработчик", 250000d, "05.07.1990", "Московская обл., г. Химки, ул. Мира, д. 10"},
            {"Сидоров Пётр", "987-654-321 83", "", "+79035554433", "", "ИТ", "Тестировщик", 180000d,
                    "1988-11-23", "Тверская улица, 7"},
    };

    private SampleDocuments() {
    }

    public static byte[] salaryXlsx() {
        return salaryWorkbook(new XSSFWorkbook());
    }

    public static byte[] salaryXls() {
        return salaryWorkbook(new HSSFWorkbook());
    }

    private static byte[] salaryWorkbook(Workbook workbook) {
        try (workbook) {
            Sheet sheet = workbook.createSheet("Сотрудники");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd.mm.yyyy"));
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADER.length; i++) {
                header.createCell(i).setCellValue(HEADER[i]);
            }
            for (int r = 0; r < EMPLOYEES.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < EMPLOYEES[r].length; c++) {
                    Cell cell = row.createCell(c);
                    switch (EMPLOYEES[r][c]) {
                        case String s -> cell.setCellValue(s);
                        case Double d -> cell.setCellValue(d);
                        case LocalDate date -> {
                            cell.setCellValue(date);
                            cell.setCellStyle(dateStyle);
                        }
                        default -> throw new IllegalStateException();
                    }
                }
            }
            Row note = sheet.createRow(EMPLOYEES.length + 2);
            note.createCell(0).setCellValue(
                    "Премию согласовал Кузнецов А.В., вопросы по тел. 8-916-123-45-67, карта 4111 1111 1111 1111");

            CreationHelper helper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(7);
            anchor.setRow1(1);
            anchor.setCol2(9);
            anchor.setRow2(4);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(helper.createRichTextString("Уточнить у hr@example.ru"));
            comment.setAuthor("Иванов Иван Иванович");
            sheet.getRow(1).getCell(7).setCellComment(comment);

            for (int i = 0; i < HEADER.length; i++) {
                sheet.autoSizeColumn(i);
            }
            return toBytes(workbook::write);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] contractDocx() {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun()
                    .setText("Конфиденциально. Исполнитель: Иванов И.И.");
            doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun()
                    .setText("Отдел кадров: hr@example.ru");

            XWPFRun title = doc.createParagraph().createRun();
            title.setBold(true);
            title.setFontSize(14);
            title.setText("Трудовой договор № 15");

            doc.createParagraph().createRun().setText(
                    "ООО «Ромашка» (ИНН 7707083893) в лице генерального директора Кузнецова Алексея Викторовича "
                            + "и гражданин Иванов Иван Иванович, паспорт 45 06 123456, СНИЛС 112-233-445 95, "
                            + "заключили настоящий договор. Оклад работника составляет 120 000 рублей.");

            doc.createParagraph().createRun().setText(
                    "Дата рождения работника: 12.03.1985. Адрес регистрации: 123456, г. Москва, ул. Ленина, д. 5, кв. 12. "
                            + "Договор вступает в силу 01.09.2026.");

            // ФИО разбито на фрагменты с разным оформлением — так часто бывает после правок в Word
            XWPFParagraph split = doc.createParagraph();
            split.createRun().setText("Ответственный: Пе");
            XWPFRun bold = split.createRun();
            bold.setBold(true);
            bold.setText("трова Анна");
            XWPFRun italic = split.createRun();
            italic.setItalic(true);
            italic.setText(" Сергеевна");
            split.createRun().setText(", тел. +7 903 765-43-21.");

            XWPFTable table = doc.createTable(3, 3);
            String[][] cells = {
                    {"ФИО", "Телефон", "Отдел"},
                    {"Петрова Анна Сергеевна", "8 (903) 765-43-21", "ИТ"},
                    {"Иванов Иван Иванович", "+7 916 123-45-67", "Продажи"},
            };
            for (int r = 0; r < cells.length; r++) {
                for (int c = 0; c < cells[r].length; c++) {
                    table.getRow(r).getCell(c).setText(cells[r][c]);
                }
            }
            doc.getProperties().getCoreProperties().setCreator("Иванов Иван Иванович");
            return toBytes(doc::write);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] memoPdf(Path font) {
        List<String> lines = List.of(
                "Служебная записка",
                "",
                "Прошу выплатить премию сотруднику Петровой Анне Сергеевне (СНИЛС 123-456-789 64)",
                "по итогам квартала в размере 50 000 рублей.",
                "Контактный телефон: 8 (903) 765-43-21, email anna.petrova@example.ru.",
                "",
                "Руководитель отдела ИТ: Иванов И.И., тел. +7 916 123-45-67",
                "",
                "Сотрудник 1990 г.р., адрес для корреспонденции: г. Химки, ул. Мира, д. 10.");
        try (PDDocument doc = new PDDocument()) {
            PDType0Font pdfFont = PDType0Font.load(doc, font.toFile());
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(pdfFont, 11);
                stream.setLeading(16);
                stream.newLineAtOffset(50, 780);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLine();
                }
                stream.endText();
            }
            doc.getDocumentInformation().setAuthor("Иванов Иван Иванович");
            return toBytes(doc::save);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Системный шрифт с кириллицей, если он есть (тот же список, что использует сервис). */
    public static Optional<Path> systemFont() {
        return List.of("C:/Windows/Fonts/arial.ttf",
                        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
                        "/usr/share/fonts/TTF/DejaVuSans.ttf",
                        "/System/Library/Fonts/Supplemental/Arial.ttf",
                        "/Library/Fonts/Arial.ttf")
                .stream().map(Path::of).filter(Files::isReadable).findFirst();
    }

    @FunctionalInterface
    private interface Writer {
        void writeTo(ByteArrayOutputStream out) throws IOException;
    }

    private static byte[] toBytes(Writer writer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeTo(out);
        return out.toByteArray();
    }
}
