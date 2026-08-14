package com.example.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import net.sf.jasperreports.engine.JRCommonText;
import net.sf.jasperreports.engine.JRPrintElement;
import net.sf.jasperreports.engine.JRPrintFrame;
import net.sf.jasperreports.engine.JRPrintText;
import net.sf.jasperreports.engine.JRStyledTextAttributeSelector;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.util.JRStyledText;
import net.sf.jasperreports.engine.util.JRStyledTextUtil;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HtmlPrintVerifier {

    private static final String REFERENCE_RESOURCE = "reference/html-print-reference.json";

    private final JRStyledTextUtil styledTextUtil;
    private final JRStyledTextAttributeSelector attributeSelector;
    private final ObjectMapper objectMapper;
    private final List<HtmlTextSnapshot> expectedHtml;

    private HtmlPrintVerifier(
            SimpleJasperReportsContext context,
            ObjectMapper objectMapper,
            List<HtmlTextSnapshot> expectedHtml
    ) {
        styledTextUtil = JRStyledTextUtil.getInstance(context);
        attributeSelector = JRStyledTextAttributeSelector.getAllSelector(context);
        this.objectMapper = objectMapper;
        this.expectedHtml = List.copyOf(expectedHtml);
    }

    public static HtmlPrintVerifier fromResource(
            SimpleJasperReportsContext context,
            ClassLoader classLoader
    ) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(classLoader, "classLoader");
        ObjectMapper objectMapper = createObjectMapper();

        try (InputStream input = classLoader.getResourceAsStream(REFERENCE_RESOURCE)) {
            if (input == null) {
                throw new IOException("HTML reference resource not found: " + REFERENCE_RESOURCE);
            }
            HtmlPrintReference reference = objectMapper.readValue(
                    input,
                    HtmlPrintReference.class
            );
            if (reference.htmlTextSnapshots() == null
                    || reference.htmlTextSnapshots().isEmpty()) {
                throw new IOException(
                        "HTML reference contains no snapshots: " + REFERENCE_RESOURCE
                );
            }
            return new HtmlPrintVerifier(
                    context,
                    objectMapper,
                    reference.htmlTextSnapshots()
            );
        }
    }

    public static HtmlPrintVerifier forGeneration(SimpleJasperReportsContext context) {
        Objects.requireNonNull(context, "context");
        return new HtmlPrintVerifier(context, createObjectMapper(), List.of());
    }

    public VerificationResult verify(JasperPrint print) {
        List<HtmlTextSnapshot> actualHtml = capture(print).htmlTextSnapshots();

        if (expectedHtml.equals(actualHtml)) {
            return VerificationResult.success();
        }
        return VerificationResult.mismatch(new HtmlMismatch(expectedHtml, actualHtml));
    }

    public HtmlPrintReference capture(JasperPrint print) {
        Objects.requireNonNull(print, "print");
        List<HtmlTextSnapshot> actualHtml = new ArrayList<>(
                Math.max(4, expectedHtml.size())
        );
        for (int pageIndex = 0; pageIndex < print.getPages().size(); pageIndex++) {
            collectHtml(
                    pageIndex,
                    print.getPages().get(pageIndex).getElements(),
                    actualHtml
            );
        }
        return new HtmlPrintReference(List.copyOf(actualHtml));
    }

    public HtmlPrintReference writeReference(JasperPrint print, Path output)
            throws IOException {
        Objects.requireNonNull(output, "output");
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        HtmlPrintReference reference = capture(print);
        objectMapper.writeValue(absoluteOutput.toFile(), reference);
        return reference;
    }

    public String toPrettyJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "<could not serialize mismatch as JSON: " + exception.getMessage() + ">";
        }
    }

    private void collectHtml(
            int pageIndex,
            List<JRPrintElement> elements,
            List<HtmlTextSnapshot> output
    ) {
        for (JRPrintElement element : elements) {
            if (element instanceof JRPrintText text
                    && JRCommonText.MARKUP_HTML.equals(text.getMarkup())) {
                output.add(snapshot(pageIndex, text));
            }
            if (element instanceof JRPrintFrame frame) {
                collectHtml(pageIndex, frame.getElements(), output);
            }
        }
    }

    private HtmlTextSnapshot snapshot(int pageIndex, JRPrintText printText) {
        JRStyledText styledText = styledTextUtil.getStyledText(
                printText,
                attributeSelector
        );
        List<StyleRun> runs = styledText.getRuns().stream()
                .map(run -> new StyleRun(
                        run.startIndex,
                        run.endIndex,
                        canonicalAttributes(run.attributes)
                ))
                .toList();
        return new HtmlTextSnapshot(
                pageIndex,
                printText.getX(),
                printText.getY(),
                printText.getWidth(),
                printText.getHeight(),
                styledText.getText(),
                canonicalAttributes(styledText.getGlobalAttributes()),
                runs
        );
    }

    private static List<TextAttributeSnapshot> canonicalAttributes(
            Map<AttributedCharacterIterator.Attribute, Object> attributes
    ) {
        return attributes.entrySet().stream()
                .map(entry -> attribute(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparing(TextAttributeSnapshot::attributeType)
                        .thenComparing(TextAttributeSnapshot::name))
                .toList();
    }

    private static TextAttributeSnapshot attribute(
            AttributedCharacterIterator.Attribute attribute,
            Object value
    ) {
        String attributeType = attribute.getClass().getName();
        String attributeName = shortAttributeName(attribute);
        if (value instanceof Color color) {
            return new TextAttributeSnapshot(
                    attributeType,
                    attributeName,
                    "Color",
                    String.format("#%08X", color.getRGB())
            );
        }
        if (value instanceof Font font) {
            return new TextAttributeSnapshot(
                    attributeType,
                    attributeName,
                    "Font",
                    font.getFamily() + "," + font.getName() + ","
                            + font.getStyle() + "," + font.getSize2D()
            );
        }
        return new TextAttributeSnapshot(
                attributeType,
                attributeName,
                value == null ? "null" : value.getClass().getSimpleName(),
                value == null ? null : String.valueOf(value)
        );
    }

    private static String shortAttributeName(
            AttributedCharacterIterator.Attribute attribute
    ) {
        String description = attribute.toString();
        int openingParenthesis = description.lastIndexOf('(');
        int closingParenthesis = description.endsWith(")")
                ? description.length() - 1
                : -1;
        return openingParenthesis >= 0 && closingParenthesis > openingParenthesis
                ? description.substring(openingParenthesis + 1, closingParenthesis)
                : description;
    }

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public record HtmlPrintReference(List<HtmlTextSnapshot> htmlTextSnapshots) {
    }

    public record VerificationResult(boolean matches, HtmlMismatch mismatch) {

        private static VerificationResult success() {
            return new VerificationResult(true, null);
        }

        private static VerificationResult mismatch(HtmlMismatch mismatch) {
            return new VerificationResult(false, mismatch);
        }
    }

    public record HtmlMismatch(
            List<HtmlTextSnapshot> expected,
            List<HtmlTextSnapshot> actual
    ) {
    }

    public record HtmlTextSnapshot(
            int pageIndex,
            int x,
            int y,
            int width,
            int height,
            String plainText,
            List<TextAttributeSnapshot> globalAttributes,
            List<StyleRun> runs
    ) {
    }

    public record StyleRun(
            int startIndex,
            int endIndex,
            List<TextAttributeSnapshot> attributes
    ) {
    }

    public record TextAttributeSnapshot(
            String attributeType,
            String name,
            String valueType,
            String value
    ) {
    }
}
