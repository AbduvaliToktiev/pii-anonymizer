package ru.pii.anonymizer.document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFHyperlink;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRegularTextRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.springframework.stereotype.Component;

import ru.pii.anonymizer.detect.TextAnonymizer;
import ru.pii.anonymizer.detect.TextAnonymizer.Replacement;
import ru.pii.anonymizer.pseudonym.PseudonymRegistry;

/**
 * Обезличивание PowerPoint (pptx) с сохранением оформления.
 *
 * <p>Обходятся надписи, заполнители, таблицы и группы фигур на слайдах, а также заметки докладчика.
 * Текст абзаца склеивается из фрагментов и раскладывается обратно ({@link TextRuns}): разметка
 * слайда не меняется. Ссылки, в адресе которых есть ПДн (mailto:ivanov@…), удаляются;
 * удаляется и миниатюра первого слайда в метаданных — на ней виден текст. Из метаданных удаляется автор.
 */
@Component
public class PowerPointProcessor implements DocumentProcessor {

    private static final Pattern THUMBNAIL = Pattern.compile("/docProps/thumbnail\\..*", Pattern.CASE_INSENSITIVE);

    private final TextAnonymizer anonymizer;

    public PowerPointProcessor(TextAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Override
    public boolean supports(DocumentFormat format) {
        return format == DocumentFormat.PPTX;
    }

    @Override
    public byte[] process(byte[] content, PseudonymRegistry registry) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(content))) {
            for (XSLFSlide slide : show.getSlides()) {
                processShapes(slide.getShapes(), slide, registry);
                XSLFNotes notes = slide.getNotes();
                if (notes != null) {
                    processShapes(notes.getShapes(), notes, registry);
                }
            }
            clearAuthor(show);
            removeThumbnail(show.getPackage());

            ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
            show.write(out);
            return out.toByteArray();
        } catch (EncryptedDocumentException e) {
            throw new DocumentProcessingException("Файл защищён паролем — снимите защиту и загрузите снова", e);
        }
    }

    private void processShapes(List<? extends XSLFShape> shapes, POIXMLDocumentPart part, PseudonymRegistry registry) {
        for (XSLFShape shape : shapes) {
            switch (shape) {
                case XSLFTextShape text -> processText(text, part, registry);
                case XSLFTable table -> {
                    for (XSLFTableRow row : table.getRows()) {
                        for (XSLFTableCell cell : row.getCells()) {
                            processText(cell, part, registry);
                        }
                    }
                }
                case XSLFGroupShape group -> processShapes(group.getShapes(), part, registry);
                default -> {
                    // картинки, диаграммы и видео не обрабатываются
                }
            }
        }
    }

    private void processText(XSLFTextShape shape, POIXMLDocumentPart part, PseudonymRegistry registry) {
        for (XSLFTextParagraph paragraph : shape.getTextParagraphs()) {
            List<XSLFTextRun> runs = paragraph.getTextRuns();
            if (runs.isEmpty()) {
                continue;
            }
            String[] texts = new String[runs.size()];
            for (int i = 0; i < runs.size(); i++) {
                String raw = runs.get(i).getRawText();
                texts[i] = raw == null ? "" : raw;
                removeLinkWithPii(runs.get(i), part);
            }
            List<Replacement> plan = anonymizer.plan(TextRuns.join(texts), registry);
            if (plan.isEmpty()) {
                continue;
            }
            String[] changed = TextRuns.apply(texts, plan);
            for (int i = 0; i < runs.size(); i++) {
                if (changed[i] != texts[i]) {
                    runs.get(i).setText(changed[i]);
                }
            }
        }
    }

    /** Ссылка вроде mailto:ivanov@example.ru: снимается с текста, а её адрес удаляется из файла. */
    private void removeLinkWithPii(XSLFTextRun run, POIXMLDocumentPart part) {
        XSLFHyperlink link = run.getHyperlink();
        if (link == null || link.getAddress() == null || anonymizer.detect(link.getAddress()).isEmpty()) {
            return;
        }
        if (run.getXmlObject() instanceof CTRegularTextRun r && r.isSetRPr()) {
            CTTextCharacterProperties props = r.getRPr();
            if (props.isSetHlinkClick()) {
                String id = props.getHlinkClick().getId();
                props.unsetHlinkClick();
                if (id != null && !id.isEmpty()) {
                    part.getPackagePart().removeRelationship(id);
                }
            }
        }
    }

    private static void clearAuthor(XMLSlideShow show) {
        POIXMLProperties.CoreProperties core = show.getProperties().getCoreProperties();
        core.setCreator("");
        core.setLastModifiedByUser("");
    }

    private static void removeThumbnail(OPCPackage pkg) {
        List<String> thumbnailRelations = new ArrayList<>();
        for (PackageRelationship rel : pkg.getRelationships()) {
            String target = rel.getTargetURI().toString();
            if (THUMBNAIL.matcher(target.startsWith("/") ? target : "/" + target).matches()) {
                thumbnailRelations.add(rel.getId());
            }
        }
        thumbnailRelations.forEach(pkg::removeRelationship);
        for (PackagePart thumb : pkg.getPartsByName(THUMBNAIL)) {
            pkg.removePart(thumb);
        }
    }
}
