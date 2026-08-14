/*
 * Proof of concept structurally based on JasperReports'
 * HtmlEditorKitMarkupProcessor (JasperReports 7.0.7, LGPL-3.0-or-later).
 */
package com.example.reports;

import io.sf.carte.doc.dom.CSSDOMImplementation;
import io.sf.carte.doc.dom.DOMElement;
import io.sf.carte.doc.dom.HTMLDocument;
import io.sf.carte.doc.dom.XMLDocumentBuilder;
import io.sf.carte.doc.style.css.CSSColor;
import io.sf.carte.doc.style.css.CSSComputedProperties;
import io.sf.carte.doc.style.css.CSSDocument;
import io.sf.carte.doc.style.css.CSSNumberValue;
import io.sf.carte.doc.style.css.CSSStyleDeclaration;
import io.sf.carte.doc.style.css.CSSUnit;
import io.sf.carte.doc.style.css.CSSTypedValue;
import io.sf.carte.doc.style.css.om.BaseDocumentCSSStyleSheet;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.base.JRBasePrintHyperlink;
import net.sf.jasperreports.engine.type.HyperlinkTypeEnum;
import net.sf.jasperreports.engine.util.JRStyledText;
import net.sf.jasperreports.engine.util.JRStyledTextParser;
import net.sf.jasperreports.engine.util.JRTextAttribute;
import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;
import net.sf.jasperreports.engine.util.StyledTextListInfo;
import net.sf.jasperreports.engine.util.StyledTextListItemInfo;
import nu.validator.htmlparser.common.XmlViolationPolicy;
import nu.validator.htmlparser.sax.HtmlParser;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.awt.Color;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.StringReader;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;

/**
 * Experimental replacement for JasperReports' Swing-backed HTML markup
 * processor. CSS4J and Validator.nu replace only the parsed document and style
 * layer; the conversion state and recursive traversal intentionally mirror
 * {@code HtmlEditorKitMarkupProcessor}.
 */
public final class Css4jHtmlMarkupProcessor implements MarkupProcessor {

    private static final String JASPER_COMPATIBLE_SUB_SUP_RULE =
            "sub, sup { font-size: inherit; }";
    private static final float CSS_PIXELS_TO_POINTS = 72f / 96f;
    private static final float[] CSS4J_LEGACY_FONT_SIZE_PIXELS =
            {8f, 10f, 12f, 14f, 18f, 24f, 28f};
    private static final float[] JASPER_LEGACY_FONT_SIZE_POINTS =
            {8f, 10f, 12f, 14f, 18f, 24f, 36f};

    private HTMLDocument document;
    private CSSComputedProperties bodyStyle;
    private Map<DOMElement, CSSComputedProperties> computedStyles;

    private boolean bodyOccurred;
    private boolean isFirstContentTag;
    private boolean breaksFlow;
    private boolean suppressBreaksFlow;

    private Stack<StyledTextListInfo> htmlListStack;
    private boolean insideLi;
    private boolean liStart;
    private StyledTextListInfo justClosedList;

    public static final class Factory implements MarkupProcessorFactory {
        @Override
        public MarkupProcessor createMarkupProcessor() {
            return new Css4jHtmlMarkupProcessor();
        }
    }

    @Override
    public String convert(String srcText) {
        if (srcText.indexOf('<') >= 0 || srcText.indexOf('&') >= 0) {
            JRStyledText styledText = new JRStyledText();

            document = getDocument(srcText);
            computedStyles = new IdentityHashMap<>();
            bodyStyle = null;

            bodyOccurred = false;
            isFirstContentTag = true;
            breaksFlow = false;
            suppressBreaksFlow = false;

            htmlListStack = new Stack<>();
            insideLi = false;
            liStart = false;
            justClosedList = null;

            Node root = document.getDocumentElement();
            if (root != null) {
                processElement(styledText, root);
            }

            styledText.setGlobalAttributes(new HashMap<>());

            return JRStyledTextParser.getInstance().write(styledText);
        }

        return srcText;
    }

    private HTMLDocument getDocument(String srcText) {
        CSSDOMImplementation implementation = new CSSDOMImplementation();
        implementation.setDefaultHTMLUserAgentSheet();
        configureJasperCompatibleUserAgentStyles(implementation);

        HtmlParser parser = new HtmlParser(XmlViolationPolicy.ALTER_INFOSET);
        parser.setCommentPolicy(XmlViolationPolicy.ALLOW);
        parser.setXmlnsPolicy(XmlViolationPolicy.ALLOW);

        XMLDocumentBuilder builder = new XMLDocumentBuilder(implementation);
        builder.setHTMLProcessing(true);
        builder.setXMLReader(parser);

        try {
            return (HTMLDocument) builder.parse(
                    new InputSource(new StringReader(srcText))
            );
        } catch (IOException | SAXException exception) {
            throw new JRRuntimeException(exception);
        }
    }

    private void configureJasperCompatibleUserAgentStyles(
            CSSDOMImplementation implementation
    ) {
        for (CSSDocument.ComplianceMode mode : CSSDocument.ComplianceMode.values()) {
            BaseDocumentCSSStyleSheet userAgentStyleSheet =
                    implementation.getUserAgentStyleSheet(mode);
            userAgentStyleSheet.insertRule(
                    JASPER_COMPATIBLE_SUB_SUP_RULE,
                    userAgentStyleSheet.getCssRules().getLength()
            );
        }
    }

    private void processElement(JRStyledText styledText, Node parentNode) {
        NodeList children = parentNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);

            if (node.getNodeType() == Node.ELEMENT_NODE) {
                DOMElement element = (DOMElement) node;
                String htmlTag = element.getTagName().toLowerCase(Locale.ROOT);

                suppressBreaksFlow |= isListTag(htmlTag);
                // Keep the same aggregate flag used by Jasper's processor. The
                // CSS4J computed display value replaces Swing HTML.Tag metadata.
                breaksFlow |= element.hasChildNodes() && breaksFlow(element);

                if ("body".equals(htmlTag)) {
                    bodyOccurred = true;
                    bodyStyle = getComputedStyle(element);
                    processElement(styledText, element);
                } else if ("br".equals(htmlTag)) {
                    if (
                            bodyOccurred && !isFirstContentTag
                                    && breaksFlow && !suppressBreaksFlow
                                    && i == 0
                    ) {
                        styledText.append("\n");
                        resizeRuns(styledText.getRuns(), styledText.length(), 1);
                    }

                    if (hasFollowingContent(element)) {
                        styledText.append("\n");

                        int startIndex = styledText.length();
                        resizeRuns(styledText.getRuns(), startIndex, 1);

                        processElement(styledText, element);
                        styledText.addRun(new JRStyledText.Run(
                                new HashMap<>(),
                                startIndex,
                                styledText.length()
                        ));

                        if (startIndex < styledText.length()) {
                            styledText.append("\n");
                            resizeRuns(styledText.getRuns(), startIndex, 1);
                        }

                        breaksFlow = false;
                        suppressBreaksFlow = true;
                    }
                } else if ("ol".equals(htmlTag) || "ul".equals(htmlTag)) {
                    String type = attributeOrNull(element, "type");
                    String start = attributeOrNull(element, "start");

                    StyledTextListInfo htmlList = new StyledTextListInfo(
                            "ol".equals(htmlTag),
                            "ol".equals(htmlTag) ? type : null,
                            "ol".equals(htmlTag) && start != null
                                    ? Integer.valueOf(start)
                                    : null,
                            insideLi
                    );

                    htmlList.setAtLiStart(liStart);
                    htmlListStack.push(htmlList);
                    insideLi = false;

                    Map<Attribute, Object> styleAttrs = new HashMap<>();
                    styleAttrs.put(
                            JRTextAttribute.HTML_LIST,
                            htmlListStack.toArray(
                                    new StyledTextListInfo[htmlListStack.size()]
                            )
                    );
                    styleAttrs.put(
                            JRTextAttribute.HTML_LIST_ITEM,
                            StyledTextListItemInfo.NO_LIST_ITEM_FILLER
                    );

                    int startIndex = styledText.length();
                    processElement(styledText, element);
                    styledText.addRun(new JRStyledText.Run(
                            styleAttrs,
                            startIndex,
                            styledText.length()
                    ));

                    justClosedList = htmlListStack.pop();
                } else if ("li".equals(htmlTag)) {
                    Map<Attribute, Object> styleAttrs = new HashMap<>();

                    StyledTextListInfo htmlList;
                    boolean ulAdded = false;
                    if (htmlListStack.isEmpty()) {
                        htmlList = new StyledTextListInfo(
                                false,
                                null,
                                null,
                                false
                        );
                        htmlListStack.push(htmlList);
                        styleAttrs.put(
                                JRTextAttribute.HTML_LIST,
                                htmlListStack.toArray(
                                        new StyledTextListInfo[htmlListStack.size()]
                                )
                        );
                        styleAttrs.put(
                                JRTextAttribute.HTML_LIST_ITEM,
                                StyledTextListItemInfo.NO_LIST_ITEM_FILLER
                        );
                        ulAdded = true;
                    } else {
                        htmlList = htmlListStack.peek();
                    }
                    htmlList.setItemCount(htmlList.getItemCount() + 1);
                    insideLi = true;
                    liStart = true;
                    justClosedList = null;

                    styleAttrs.put(
                            JRTextAttribute.HTML_LIST_ITEM,
                            new StyledTextListItemInfo(htmlList.getItemCount() - 1)
                    );

                    int startIndex = styledText.length();
                    processElement(styledText, element);
                    styledText.addRun(new JRStyledText.Run(
                            styleAttrs,
                            startIndex,
                            styledText.length()
                    ));

                    insideLi = false;
                    liStart = false;
                    if (justClosedList != null) {
                        justClosedList.setAtLiEnd(true);
                    }

                    if (ulAdded) {
                        htmlListStack.pop();
                    }
                } else if ("p".equals(htmlTag) && !hasMeaningfulChild(element)) {
                    styledText.append("\n");
                    resizeRuns(styledText.getRuns(), styledText.length(), 1);
                } else if (bodyOccurred) {
                    processElement(styledText, element);
                }

                suppressBreaksFlow |= isListTag(htmlTag);
            } else if (node.getNodeType() == Node.TEXT_NODE && bodyOccurred) {
                processContent(styledText, node);
            }
        }
    }

    private void processContent(JRStyledText styledText, Node textNode) {
        DOMElement element = parentElement(textNode);
        if (element == null) {
            return;
        }

        String chunk = normalizeText(styledText, textNode.getNodeValue(), element);
        if (chunk != null && !"\n".equals(chunk)) {
            if (bodyOccurred && !isFirstContentTag && breaksFlow && !suppressBreaksFlow) {
                styledText.append("\n");
                resizeRuns(styledText.getRuns(), styledText.length(), 1);
            }

            isFirstContentTag = false;
            breaksFlow = false;
            suppressBreaksFlow = false;

            liStart = false;
            justClosedList = null;

            int startIndex = styledText.length();
            styledText.append(chunk);

            Map<Attribute, Object> styleAttributes = getAttributes(element);

            DOMElement anchor = ancestor(element, "a");
            if (anchor != null) {
                JRBasePrintHyperlink hyperlink = new JRBasePrintHyperlink();
                hyperlink.setHyperlinkType(HyperlinkTypeEnum.REFERENCE);
                hyperlink.setHyperlinkReference(attributeOrNull(anchor, "href"));
                hyperlink.setLinkTarget(attributeOrNull(anchor, "target"));
                styleAttributes.put(JRTextAttribute.HYPERLINK, hyperlink);
            }

            styledText.addRun(new JRStyledText.Run(
                    styleAttributes,
                    startIndex,
                    styledText.length()
            ));
        }
    }

    private Map<Attribute, Object> getAttributes(DOMElement element) {
        Map<Attribute, Object> attrMap = new HashMap<>();
        CSSComputedProperties style = getComputedStyle(element);

        if (differsFromBody(style, "font-family")) {
            attrMap.put(
                    TextAttribute.FAMILY,
                    firstFontFamily(style.getPropertyValue("font-family"))
            );
        }

        if (differsFromBody(style, "font-weight")) {
            attrMap.put(
                    TextAttribute.WEIGHT,
                    isBold(style.getFontWeight())
                            ? TextAttribute.WEIGHT_BOLD
                            : TextAttribute.WEIGHT_REGULAR
            );
        }

        if (differsFromBody(style, "font-style")) {
            attrMap.put(
                    TextAttribute.POSTURE,
                    isItalic(style.getFontStyle())
                            ? TextAttribute.POSTURE_OBLIQUE
                            : TextAttribute.POSTURE_REGULAR
            );
        }

        String textDecoration = effectiveTextDecoration(element);
        if (containsCssWord(textDecoration, "underline")) {
            attrMap.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        }
        if (containsCssWord(textDecoration, "line-through")) {
            attrMap.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        }

        if (differsFromBody(style, "font-size")) {
            Float legacyFontSize = jasperLegacyFontSize(element, style);
            attrMap.put(
                    TextAttribute.SIZE,
                    legacyFontSize != null
                            ? legacyFontSize
                            : style.getComputedFontSize()
            );
        }

        if (differsFromBody(style, "color")) {
            Color foreground = toAwtColor(style.getCSSColor());
            if (foreground != null) {
                attrMap.put(TextAttribute.FOREGROUND, foreground);
            }
        }

        DOMElement backgroundElement = backgroundElement(element);
        if (backgroundElement != null) {
            Color background = toAwtColor(
                    getComputedStyle(backgroundElement).getCSSBackgroundColor()
            );
            if (background != null && background.getAlpha() != 0) {
                attrMap.put(TextAttribute.BACKGROUND, background);
            }
        }

        String verticalAlign = effectiveVerticalAlign(element);
        if ("super".equals(verticalAlign)) {
            attrMap.put(TextAttribute.SUPERSCRIPT, TextAttribute.SUPERSCRIPT_SUPER);
        }
        if ("sub".equals(verticalAlign)) {
            attrMap.put(TextAttribute.SUPERSCRIPT, TextAttribute.SUPERSCRIPT_SUB);
        }

        return attrMap;
    }

    private Float jasperLegacyFontSize(
            DOMElement element,
            CSSComputedProperties style
    ) {
        DOMElement fontElement = ancestor(element, "font");
        if (fontElement == null || hasExplicitFontSize(element, fontElement)) {
            return null;
        }

        String sizeAttribute = attributeOrNull(fontElement, "size");
        if (sizeAttribute == null) {
            return null;
        }

        final int legacyIndex;
        try {
            legacyIndex = Integer.parseInt(sizeAttribute.trim()) - 1;
        } catch (NumberFormatException exception) {
            return null;
        }
        if (legacyIndex < 0 || legacyIndex >= JASPER_LEGACY_FONT_SIZE_POINTS.length) {
            return null;
        }

        float css4jHintSize = CSS4J_LEGACY_FONT_SIZE_PIXELS[legacyIndex]
                * CSS_PIXELS_TO_POINTS;
        if (Math.abs(style.getComputedFontSize() - css4jHintSize) > 0.01f) {
            return null;
        }

        return JASPER_LEGACY_FONT_SIZE_POINTS[legacyIndex];
    }

    private boolean hasExplicitFontSize(
            DOMElement element,
            DOMElement fontElement
    ) {
        for (DOMElement current = element;
             current != null;
             current = parentElement(current)) {
            CSSStyleDeclaration inlineStyle = current.getStyle();
            if (inlineStyle != null
                    && !inlineStyle.getPropertyValue("font-size").isEmpty()) {
                return true;
            }
            if (current == fontElement) {
                return false;
            }
        }
        return false;
    }

    private CSSComputedProperties getComputedStyle(DOMElement element) {
        return computedStyles.computeIfAbsent(
                element,
                key -> key.getComputedStyle(null)
        );
    }

    private boolean differsFromBody(CSSComputedProperties style, String property) {
        return bodyStyle != null && !Objects.equals(
                style.getPropertyValue(property),
                bodyStyle.getPropertyValue(property)
        );
    }

    private String effectiveTextDecoration(DOMElement element) {
        StringBuilder decoration = new StringBuilder();
        for (DOMElement current = element;
             current != null && !"body".equalsIgnoreCase(current.getTagName());
             current = parentElement(current)) {
            String value = getComputedStyle(current)
                    .getPropertyValue("text-decoration-line");
            if (value.isEmpty()) {
                value = getComputedStyle(current).getTextDecoration();
            }
            decoration.append(' ').append(value);
        }
        return decoration.toString();
    }

    private String effectiveVerticalAlign(DOMElement element) {
        for (DOMElement current = element;
             current != null && !"body".equalsIgnoreCase(current.getTagName());
             current = parentElement(current)) {
            String value = getComputedStyle(current).getVerticalAlign();
            if ("super".equals(value) || "sub".equals(value)) {
                return value;
            }
        }
        return "baseline";
    }

    private DOMElement backgroundElement(DOMElement element) {
        for (DOMElement current = element;
             current != null && !"body".equalsIgnoreCase(current.getTagName());
             current = parentElement(current)) {
            String background = getComputedStyle(current)
                    .getPropertyValue("background-color");
            if (!background.isEmpty() && !"transparent".equals(background)) {
                return current;
            }
        }
        return null;
    }

    private Color toAwtColor(CSSTypedValue cssValue) {
        if (cssValue == null) {
            return null;
        }
        try {
            CSSColor color = cssValue.toRGBColor();
            double[] components = color.toNumberArray();
            float alpha = ((CSSNumberValue) color.getAlpha())
                    .getFloatValue(CSSUnit.CSS_NUMBER);
            return new Color(
                    clampColor(components[0]),
                    clampColor(components[1]),
                    clampColor(components[2]),
                    clampColor(alpha)
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int clampColor(double component) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, component)) * 255.0);
    }

    private boolean breaksFlow(DOMElement element) {
        String display = getComputedStyle(element).getDisplay();
        return !display.isEmpty()
                && !"inline".equals(display)
                && !"inline-block".equals(display)
                && !"inline-flex".equals(display)
                && !"inline-grid".equals(display)
                && !"contents".equals(display)
                && !"none".equals(display);
    }

    private String normalizeText(
            JRStyledText styledText,
            String text,
            DOMElement element
    ) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String whiteSpace = getComputedStyle(element).getWhiteSpace();
        String normalized;
        if (
                "pre".equals(whiteSpace)
                        || "pre-wrap".equals(whiteSpace)
                        || "break-spaces".equals(whiteSpace)
        ) {
            normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        } else {
            normalized = text.replaceAll("[\\t\\n\\f\\r ]+", " ");
            if (
                    normalized.startsWith(" ")
                            && (styledText.length() == 0
                            || endsWithWhitespace(styledText.getText()))
            ) {
                normalized = normalized.substring(1);
            }
        }

        return normalized.isEmpty() ? null : normalized;
    }

    private boolean endsWithWhitespace(String text) {
        if (text.isEmpty()) {
            return true;
        }
        char last = text.charAt(text.length() - 1);
        return last == ' ' || last == '\n' || last == '\t' || last == '\r';
    }

    private boolean hasFollowingContent(Node node) {
        Node current = node;
        while (current != null) {
            for (Node sibling = current.getNextSibling();
                 sibling != null;
                 sibling = sibling.getNextSibling()) {
                if (isMeaningful(sibling)) {
                    return true;
                }
            }
            current = current.getParentNode();
            if (current instanceof DOMElement element
                    && "body".equalsIgnoreCase(element.getTagName())) {
                return false;
            }
        }
        return false;
    }

    private boolean hasMeaningfulChild(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (isMeaningful(children.item(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningful(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            return !node.getNodeValue().isBlank();
        }
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            return true;
        }
        return false;
    }

    private boolean isListTag(String tag) {
        return "ul".equals(tag) || "ol".equals(tag) || "li".equals(tag);
    }

    private boolean isBold(String fontWeight) {
        if ("bold".equals(fontWeight) || "bolder".equals(fontWeight)) {
            return true;
        }
        try {
            return Float.parseFloat(fontWeight) >= 600f;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isItalic(String fontStyle) {
        return "italic".equals(fontStyle) || "oblique".equals(fontStyle);
    }

    private boolean containsCssWord(String value, String word) {
        for (String part : value.trim().split("\\s+")) {
            if (word.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private String firstFontFamily(String fontFamily) {
        int comma = fontFamily.indexOf(',');
        String first = comma < 0 ? fontFamily : fontFamily.substring(0, comma);
        first = first.trim();
        if (first.length() >= 2
                && ((first.startsWith("\"") && first.endsWith("\""))
                || (first.startsWith("'") && first.endsWith("'")))) {
            return first.substring(1, first.length() - 1);
        }
        return first;
    }

    private String attributeOrNull(DOMElement element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private DOMElement ancestor(DOMElement element, String tagName) {
        for (DOMElement current = element; current != null; current = parentElement(current)) {
            if (tagName.equalsIgnoreCase(current.getTagName())) {
                return current;
            }
        }
        return null;
    }

    private DOMElement parentElement(Node node) {
        Node parent = node.getParentNode();
        return parent instanceof DOMElement element ? element : null;
    }

    private void resizeRuns(List<JRStyledText.Run> runs, int startIndex, int count) {
        for (JRStyledText.Run run : runs) {
            if (run.startIndex <= startIndex && run.endIndex > startIndex - count) {
                run.endIndex += count;
            }
        }
    }
}
