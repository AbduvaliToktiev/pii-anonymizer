package ru.pii.anonymizer;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFHyperlink;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import ru.pii.anonymizer.detect.Checksums;

/**
 * Большие и многостраничные примеры с вымышленными ПДн: книга Excel с несколькими листами и формулами,
 * презентация, приказ (из него Word делает doc и odt). Данные детерминированы (seed), СНИЛС и ИНН — с верными
 * контрольными суммами, но реальным людям не принадлежат.
 */
public final class LargeSamples {

    /** Сгенерированная книга и все ПДн в ней — для проверки, что после обезличивания их не осталось. */
    public record Staff(byte[] content, List<String> names, List<String> pii) {
    }

    public static final String[] DEPARTMENTS = {"Продажи", "ИТ", "Бухгалтерия", "Логистика", "Маркетинг",
            "Производство", "Юридический", "Кадры"};
    private static final String[] POSITIONS = {"Специалист", "Ведущий специалист", "Менеджер", "Руководитель группы",
            "Инженер", "Аналитик", "Эксперт"};
    private static final String[] MALE_NAMES = {"Иван", "Пётр", "Алексей", "Сергей", "Дмитрий", "Андрей", "Михаил",
            "Николай", "Евгений", "Александр", "Олег", "Артём", "Максим", "Владимир", "Павел"};
    private static final String[] FEMALE_NAMES = {"Анна", "Мария", "Елена", "Ольга", "Наталья", "Татьяна", "Евгения",
            "Александра", "Ирина", "Светлана", "Юлия", "Екатерина", "Дарья", "Ксения", "Валентина"};
    private static final String[] SURNAMES = {"Иванов", "Петров", "Сидоров", "Кузнецов", "Смирнов", "Попов",
            "Васильев", "Соколов", "Михайлов", "Новиков", "Фёдоров", "Морозов", "Волков", "Алексеев", "Лебедев",
            "Семёнов", "Егоров", "Павлов", "Козлов", "Степанов", "Николаев", "Орлов", "Андреев", "Макаров",
            "Никитин", "Захаров", "Зайцев", "Соловьёв", "Борисов", "Яковлев"};
    private static final String[] FATHERS = {"Иван", "Пётр", "Алексей", "Сергей", "Дмитрий", "Андрей", "Михаил",
            "Николай", "Евгений", "Александр", "Олег", "Владимир", "Павел"};
    /** Имена других народов — без отчества, как их обычно и пишут. */
    private static final String[] OTHER_NAMES = {"Абенов Нурлан", "Қасымова Айгерім", "Мирзаев Шавкат",
            "Сафина Гульнара", "Алиев Рашид оглы", "Ким Виктория", "Smith John", "Garcia Maria", "Ли Мин Хо",
            "Мамедова Лейла", "Каримов Тимур", "Бекова Динара"};
    private static final String[] STREETS = {"ул. Ленина", "ул. Мира", "пр-т Победы", "ул. Гагарина",
            "ул. Садовая", "ул. Советская", "ул. Пушкина", "Тверская улица"};
    private static final String[] CITIES = {"г. Москва", "г. Казань", "г. Екатеринбург", "г. Новосибирск",
            "г. Самара", "Московская обл., г. Химки"};

    private LargeSamples() {
    }

    // ---------- Excel ----------

    /**
     * Книга «Сотрудники»: 4 листа и формулы.
     * <ul>
     *   <li>«Сотрудники» — ФИО, дата рождения, СНИЛС, ИНН, телефон, email, адрес, отдел, должность, оклад.</li>
     *   <li>«Начисления» — ФИО в другом порядке; отдел и оклад подтягиваются ВПР по ФИО, премия, НДФЛ, к выплате.</li>
     *   <li>«Итоги по отделам» — СЧЁТЕСЛИ, СУММЕСЛИ, средний оклад, итоговая строка.</li>
     *   <li>«Справка» — ИНДЕКС/ПОИСКПОЗ (ФИО с максимальным окладом), сцепка строки с ФИО,
     *       СЧЁТЕСЛИ с ФИО в кавычках, текст с ПДн, примечание и колонтитул.</li>
     * </ul>
     * Сохранённые результаты формул заполнены, как после пересчёта в Excel: в них тоже есть ПДн.
     */
    public static Staff staff(Workbook workbook, int count) {
        Random random = new Random(42);
        List<String> names = uniqueNames(random, count);
        List<String> pii = new ArrayList<>(names);
        int last = count + 1;
        String fio = "Сотрудники!$B$2:$B$" + last;
        String table = "Сотрудники!$B$2:$K$" + last;
        String departments = "Сотрудники!$I$2:$I$" + last;
        String salaries = "Сотрудники!$K$2:$K$" + last;

        try (workbook) {
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("dd.mm.yyyy"));
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));
            CellStyle percentStyle = workbook.createCellStyle();
            percentStyle.setDataFormat(helper.createDataFormat().getFormat("0%"));
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            // Лист 1: сотрудники
            Sheet staff = workbook.createSheet("Сотрудники");
            header(staff, headerStyle, "Таб. №", "ФИО", "Дата рождения", "СНИЛС", "ИНН", "Телефон", "Email", "Адрес",
                    "Отдел", "Должность", "Оклад", "Дата приёма");
            Map<String, Integer> salaryByName = new HashMap<>();
            Map<String, String> departmentByName = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String name = names.get(i);
                String snils = snils(random);
                String inn = inn(random);
                String phone = "+7 9%02d %03d-%02d-%02d".formatted(random.nextInt(100), random.nextInt(1000),
                        random.nextInt(100), random.nextInt(100));
                String email = "employee%05d@example.ru".formatted(i + 1);
                String address = CITIES[random.nextInt(CITIES.length)] + ", " + STREETS[random.nextInt(STREETS.length)]
                        + ", д. " + (1 + random.nextInt(120)) + ", кв. " + (1 + random.nextInt(300));
                String department = DEPARTMENTS[random.nextInt(DEPARTMENTS.length)];
                int salary = 60_000 + random.nextInt(37) * 5_000;
                LocalDate birth = LocalDate.of(1960 + random.nextInt(45), 1 + random.nextInt(12), 1 + random.nextInt(28));
                salaryByName.put(name, salary);
                departmentByName.put(name, department);
                pii.addAll(List.of(snils, inn, phone, email, address));

                Row row = staff.createRow(i + 1);
                row.createCell(0).setCellValue(1000 + i);
                row.createCell(1).setCellValue(name);
                date(row.createCell(2), birth, dateStyle);
                row.createCell(3).setCellValue(snils);
                row.createCell(4).setCellValue(inn);
                row.createCell(5).setCellValue(phone);
                row.createCell(6).setCellValue(email);
                row.createCell(7).setCellValue(address);
                row.createCell(8).setCellValue(department);
                row.createCell(9).setCellValue(POSITIONS[random.nextInt(POSITIONS.length)]);
                Cell salaryCell = row.createCell(10);
                salaryCell.setCellValue(salary);
                salaryCell.setCellStyle(moneyStyle);
                date(row.createCell(11), LocalDate.of(2010 + random.nextInt(16), 1 + random.nextInt(12), 1), dateStyle);
            }
            staff.createFreezePane(0, 1);

            // Лист 2: начисления (ФИО в другом порядке, отдел и оклад — ВПР по ФИО)
            Sheet payroll = workbook.createSheet("Начисления");
            header(payroll, headerStyle, "ФИО", "Месяц", "Отдел", "Оклад", "Премия, %", "Премия", "НДФЛ", "К выплате");
            List<String> shuffled = new ArrayList<>(names);
            Collections.shuffle(shuffled, random);
            Map<String, double[]> totalsByDepartment = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String name = shuffled.get(i);
                int r = i + 2;
                double salary = salaryByName.get(name);
                double percent = (1 + random.nextInt(6)) * 0.05;
                double bonus = salary * percent;
                double tax = Math.round((salary + bonus) * 0.13 * 100) / 100.0;
                double pay = salary + bonus - tax;
                double[] totals = totalsByDepartment.computeIfAbsent(departmentByName.get(name), _ -> new double[2]);
                totals[0] += bonus;
                totals[1] += pay;

                Row row = payroll.createRow(i + 1);
                row.createCell(0).setCellValue(name);
                row.createCell(1).setCellValue("Октябрь 2026");
                formula(row.createCell(2), "VLOOKUP($A" + r + "," + table + ",8,FALSE)", departmentByName.get(name));
                formula(row.createCell(3), "VLOOKUP($A" + r + "," + table + ",10,FALSE)", salary).setCellStyle(moneyStyle);
                Cell percentCell = row.createCell(4);
                percentCell.setCellValue(percent);
                percentCell.setCellStyle(percentStyle);
                formula(row.createCell(5), "D" + r + "*E" + r, bonus).setCellStyle(moneyStyle);
                formula(row.createCell(6), "ROUND((D" + r + "+F" + r + ")*0.13,2)", tax).setCellStyle(moneyStyle);
                formula(row.createCell(7), "D" + r + "+F" + r + "-G" + r, pay).setCellStyle(moneyStyle);
            }
            payroll.createFreezePane(0, 1);

            // Лист 3: итоги по отделам
            Sheet summary = workbook.createSheet("Итоги по отделам");
            header(summary, headerStyle, "Отдел", "Сотрудников", "ФОТ (оклады)", "Средний оклад", "Премии", "К выплате");
            String payDepartments = "Начисления!$C$2:$C$" + last;
            for (int d = 0; d < DEPARTMENTS.length; d++) {
                String department = DEPARTMENTS[d];
                int r = d + 2;
                long people = departmentByName.values().stream().filter(department::equals).count();
                double fund = salaryByName.entrySet().stream()
                        .filter(e -> department.equals(departmentByName.get(e.getKey())))
                        .mapToDouble(Map.Entry::getValue).sum();
                double[] totals = totalsByDepartment.getOrDefault(department, new double[2]);
                Row row = summary.createRow(d + 1);
                row.createCell(0).setCellValue(department);
                formula(row.createCell(1), "COUNTIF(" + departments + ",A" + r + ")", people);
                formula(row.createCell(2), "SUMIF(" + departments + ",A" + r + "," + salaries + ")", fund)
                        .setCellStyle(moneyStyle);
                formula(row.createCell(3), "IF(B" + r + "=0,0,C" + r + "/B" + r + ")", people == 0 ? 0 : fund / people)
                        .setCellStyle(moneyStyle);
                formula(row.createCell(4), "SUMIF(" + payDepartments + ",A" + r + ",Начисления!$F$2:$F$" + last + ")",
                        totals[0]).setCellStyle(moneyStyle);
                formula(row.createCell(5), "SUMIF(" + payDepartments + ",A" + r + ",Начисления!$H$2:$H$" + last + ")",
                        totals[1]).setCellStyle(moneyStyle);
            }
            int totalRow = DEPARTMENTS.length + 1;
            Row total = summary.createRow(totalRow);
            total.createCell(0).setCellValue("Итого");
            for (int c = 1; c <= 5; c++) {
                String col = String.valueOf((char) ('A' + c));
                double sum = 0;
                for (int d = 1; d <= DEPARTMENTS.length; d++) {
                    sum += summary.getRow(d).getCell(c).getNumericCellValue();
                }
                formula(total.createCell(c), "SUM(" + col + "2:" + col + totalRow + ")", sum).setCellStyle(moneyStyle);
            }
            int t = totalRow + 1;
            formula(total.getCell(3), "IF(B" + t + "=0,0,C" + t + "/B" + t + ")",
                    total.getCell(2).getNumericCellValue() / total.getCell(1).getNumericCellValue());

            // Лист 4: справка — формулы, которые возвращают ФИО, и ФИО прямо в формуле
            Sheet info = workbook.createSheet("Справка");
            String top = salaryByName.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            Row r1 = info.createRow(0);
            r1.createCell(0).setCellValue("Самый высокий оклад:");
            formula(r1.createCell(1), "INDEX(" + fio + ",MATCH(MAX(" + salaries + ")," + salaries + ",0))", top);
            Row r2 = info.createRow(1);
            r2.createCell(0).setCellValue("Его оклад:");
            formula(r2.createCell(1), "VLOOKUP(B1," + table + ",10,FALSE)", salaryByName.get(top));
            Row r3 = info.createRow(2);
            r3.createCell(0).setCellValue("Подпись:");
            formula(r3.createCell(1), "\"Ответственный: \"&Сотрудники!B2", "Ответственный: " + names.getFirst());
            Row r4 = info.createRow(3);
            r4.createCell(0).setCellValue("Начислений Иванову:");
            formula(r4.createCell(1), "COUNTIF(Начисления!$A$2:$A$" + last + ",\"" + names.getFirst() + "\")", 1);
            Row r5 = info.createRow(4);
            r5.createCell(0).setCellValue("Отчёт подготовила Смирнова Ольга Петровна, тел. +7 912 345-67-89. "
                    + "Вопросы по премиям — Козлову Дмитрию Андреевичу.");
            pii.addAll(List.of("Смирнова Ольга Петровна", "+7 912 345-67-89", "Козлову Дмитрию Андреевичу"));

            Drawing<?> drawing = info.createDrawingPatriarch();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(3);
            anchor.setRow1(0);
            anchor.setCol2(6);
            anchor.setRow2(4);
            Comment comment = drawing.createCellComment(anchor);
            comment.setString(helper.createRichTextString("Проверить у Петровой Анны Сергеевны, anna.petrova@example.ru"));
            comment.setAuthor("Козлов Д.А.");
            r1.getCell(1).setCellComment(comment);
            pii.addAll(List.of("Петровой Анны Сергеевны", "anna.petrova@example.ru"));
            info.getHeader().setCenter("Исполнитель: Козлов Д.А.");
            info.setColumnWidth(0, 30 * 256);
            info.setColumnWidth(1, 45 * 256);

            for (Sheet sheet : List.of(staff, payroll, summary)) {
                for (int c = 0; c < sheet.getRow(0).getLastCellNum(); c++) {
                    sheet.setColumnWidth(c, (c == 1 || c == 7 ? 40 : 16) * 256);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new Staff(out.toByteArray(), names, pii);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> uniqueNames(Random random, int count) {
        Set<String> names = new LinkedHashSet<>();
        names.add("Иванов Иван Иванович");
        names.add("Петрова Анна Сергеевна");
        names.add("Иванов Евгений Петрович");
        names.add("Иванова Евгения Петровна");
        for (String other : OTHER_NAMES) {
            if (names.size() < count) {
                names.add(other);
            }
        }
        while (names.size() < count) {
            boolean female = random.nextBoolean();
            String surname = SURNAMES[random.nextInt(SURNAMES.length)];
            String father = FATHERS[random.nextInt(FATHERS.length)];
            String name = female ? FEMALE_NAMES[random.nextInt(FEMALE_NAMES.length)] : MALE_NAMES[random.nextInt(MALE_NAMES.length)];
            names.add((female ? surname + "а" : surname) + " " + name + " " + patronymic(father, female));
        }
        return new ArrayList<>(names).subList(0, count);
    }

    private static String patronymic(String father, boolean female) {
        String base = switch (father) {
            case "Пётр" -> "Петр";
            case "Павел" -> "Павл";
            default -> father;
        };
        if (base.endsWith("й")) {
            String stem = base.substring(0, base.length() - 1);
            return stem + (female ? "евна" : "евич");
        }
        return base + (female ? "овна" : "ович");
    }

    private static void header(Sheet sheet, CellStyle style, String... titles) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(titles[i]);
            cell.setCellStyle(style);
        }
    }

    private static void date(Cell cell, LocalDate value, CellStyle style) {
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** Формула с сохранённым результатом — как в файле, пересчитанном Excel. */
    private static Cell formula(Cell cell, String formula, Object cached) {
        cell.setCellFormula(formula);
        switch (cached) {
            case String s -> cell.setCellValue(s);
            case Number n -> cell.setCellValue(n.doubleValue());
            default -> throw new IllegalArgumentException();
        }
        return cell;
    }

    /** СНИЛС с верным контрольным числом: «123-456-789 64». */
    static String snils(Random random) {
        while (true) {
            int[] d = new int[9];
            int sum = 0;
            for (int i = 0; i < 9; i++) {
                d[i] = random.nextInt(10);
                sum += d[i] * (9 - i);
            }
            int check = sum < 100 ? sum : sum % 101 == 100 ? 0 : sum % 101;
            String digits = "%d%d%d%d%d%d%d%d%d%02d".formatted(d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], check);
            if (Checksums.isValidSnils(digits)) {
                return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6, 9) + " "
                        + digits.substring(9);
            }
        }
    }

    /** ИНН физлица (12 цифр) с верными контрольными разрядами. */
    static String inn(Random random) {
        int[] k11 = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
        int[] k12 = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
        int[] d = new int[12];
        d[0] = 1 + random.nextInt(9);
        for (int i = 1; i < 10; i++) {
            d[i] = random.nextInt(10);
        }
        int s = 0;
        for (int i = 0; i < 10; i++) {
            s += d[i] * k11[i];
        }
        d[10] = s % 11 % 10;
        s = 0;
        for (int i = 0; i < 11; i++) {
            s += d[i] * k12[i];
        }
        d[11] = s % 11 % 10;
        StringBuilder sb = new StringBuilder();
        for (int digit : d) {
            sb.append(digit);
        }
        if (!Checksums.isValidInn(sb.toString())) {
            throw new IllegalStateException("ИНН с неверной контрольной суммой: " + sb);
        }
        return sb.toString();
    }

    // ---------- PowerPoint ----------

    /** Все ПДн презентации. */
    public static final List<String> PRESENTATION_PII = List.of("Иванов", "Петров", "Ивану", "Петровой", "Кузнецов",
            "Абенов", "Smith", "+7 916 123-45-67", "8 (903) 765-43-21", "ivanov@example.ru", "112-233-445 95");

    /** Презентация: заголовок, текст с ФИО в разных падежах и разбитым на фрагменты, таблица, заметки, ссылка. */
    public static byte[] presentationPptx() {
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.getProperties().getCoreProperties().setCreator("Иванов Иван Иванович");

            XSLFSlide first = show.createSlide();
            XSLFTextBox title = first.createTextBox();
            title.setAnchor(new Rectangle2D.Double(40, 30, 640, 60));
            XSLFTextRun titleRun = title.getTextParagraphs().getFirst().addNewTextRun();
            titleRun.setText("Итоги квартала: отдел продаж");
            titleRun.setFontSize(28d);
            titleRun.setBold(true);

            XSLFTextBox body = first.createTextBox();
            body.setAnchor(new Rectangle2D.Double(40, 110, 640, 300));
            XSLFTextParagraph p1 = body.getTextParagraphs().getFirst();
            p1.addNewTextRun().setText("Премию выплатить Ивану Ивановичу Иванову — 30 000 руб.");
            XSLFTextParagraph p2 = body.addNewTextParagraph();
            p2.addNewTextRun().setText("Благодарность ");
            XSLFTextRun split = p2.addNewTextRun();
            split.setText("Петровой Ан");
            split.setBold(true);
            XSLFTextRun split2 = p2.addNewTextRun();
            split2.setText("не Сергеевне");
            split2.setFontColor(Color.RED);
            p2.addNewTextRun().setText(" за проект, тел. 8 (903) 765-43-21");
            XSLFTextParagraph p3 = body.addNewTextParagraph();
            p3.addNewTextRun().setText("Новые сотрудники: Нурлан Абенов, John Smith");
            XSLFTextParagraph p4 = body.addNewTextParagraph();
            p4.addNewTextRun().setText("Вопросы: ");
            XSLFTextRun link = p4.addNewTextRun();
            link.setText("ivanov@example.ru");
            XSLFHyperlink hyperlink = link.createHyperlink();
            hyperlink.setAddress("mailto:ivanov@example.ru");
            notes(show, first, "Позвонить Кузнецову Алексею Викторовичу по номеру +7 916 123-45-67 до пятницы.");

            XSLFSlide second = show.createSlide();
            XSLFTextBox caption = second.createTextBox();
            caption.setAnchor(new Rectangle2D.Double(40, 20, 640, 40));
            caption.getTextParagraphs().getFirst().addNewTextRun().setText("Лучшие сотрудники");
            XSLFTable table = second.createTable();
            table.setAnchor(new Rectangle2D.Double(40, 80, 640, 200));
            String[][] rows = {
                    {"ФИО", "Отдел", "Продажи, руб.", "СНИЛС"},
                    {"Иванов Иван Иванович", "Продажи", "1 250 000", "112-233-445 95"},
                    {"Петрова Анна Сергеевна", "Продажи", "980 000", ""},
                    {"Абенов Нурлан", "Продажи", "870 000", ""},
            };
            for (String[] values : rows) {
                XSLFTableRow row = table.addRow();
                row.setHeight(40);
                for (String value : values) {
                    row.addCell().setText(value);
                }
            }
            for (int c = 0; c < 4; c++) {
                table.setColumnWidth(c, 160);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void notes(XMLSlideShow show, XSLFSlide slide, String text) {
        var notes = show.getNotesSlide(slide);
        for (var shape : notes.getPlaceholders()) {
            if (shape.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                shape.clearText();
                shape.setText(text);
                return;
            }
        }
    }

    // ---------- Word: приказ (из него делаются doc и odt) ----------

    /** Все ПДн приказа. */
    public static final List<String> ORDER_PII = List.of("Иванов", "Ивану", "Петров", "Анне", "Кузнецов",
            "Абенов", "Нурлан", "Smith", "112-233-445 95", "+7 916 123-45-67", "hr@example.ru", "45 06 123456");

    /** Приказ о премировании: ФИО в разных падежах, таблица, колонтитулы, ФИО, разбитое на фрагменты. */
    public static byte[] orderDocx() {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun()
                    .setText("ООО «Ромашка». Исполнитель: Кузнецов А.В.");
            doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun()
                    .setText("Отдел кадров: hr@example.ru, +7 916 123-45-67");
            XWPFParagraph title = doc.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(14);
            titleRun.setText("Приказ № 42 о премировании по итогам квартала");

            doc.createParagraph().createRun().setText("1. Выплатить премию Иванову Ивану Ивановичу (СНИЛС 112-233-445 95, "
                    + "паспорт 45 06 123456) в размере 30 000 рублей.");
            XWPFParagraph split = doc.createParagraph();
            split.createRun().setText("2. Выплатить премию Петр");
            XWPFRun bold = split.createRun();
            bold.setBold(true);
            bold.setText("овой Анне Серге");
            XWPFRun italic = split.createRun();
            italic.setItalic(true);
            italic.setText("евне в размере 25 000 рублей.");
            doc.createParagraph().createRun().setText("3. Выплатить премию Нурлану Абенову и John Smith по 20 000 рублей.");
            doc.createParagraph().createRun().setText("4. Контроль за исполнением приказа возложить на Кузнецова "
                    + "Алексея Викторовича. С приказом ознакомить Иванова И.И. и Петрову А.С.");

            XWPFTable table = doc.createTable(5, 3);
            String[][] rows = {
                    {"ФИО", "Отдел", "Премия, руб."},
                    {"Иванов Иван Иванович", "Продажи", "30 000"},
                    {"Петрова Анна Сергеевна", "ИТ", "25 000"},
                    {"Абенов Нурлан", "Продажи", "20 000"},
                    {"Smith John", "Маркетинг", "20 000"},
            };
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < 3; c++) {
                    table.getRow(r).getCell(c).setText(rows[r][c]);
                }
            }
            doc.createParagraph().createRun().setText("Генеральный директор Кузнецов А.В.");
            doc.getProperties().getCoreProperties().setCreator("Кузнецов Алексей Викторович");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
