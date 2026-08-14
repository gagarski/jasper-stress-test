/*
 * Proof of concept structurally based on JasperReports'
 * HtmlEditorKitMarkupProcessor (JasperReports 7.0.7, LGPL-3.0-or-later).
 */
package com.example.reports;

import net.sf.jasperreports.engine.base.JRBasePrintHyperlink;
import net.sf.jasperreports.engine.type.HyperlinkTypeEnum;
import net.sf.jasperreports.engine.util.JRStyledText;
import net.sf.jasperreports.engine.util.JRStyledTextParser;
import net.sf.jasperreports.engine.util.JRTextAttribute;
import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;
import net.sf.jasperreports.engine.util.StyledTextListInfo;
import net.sf.jasperreports.engine.util.StyledTextListItemInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Entities;
import org.xhtmlrenderer.css.constants.CSSName;
import org.xhtmlrenderer.css.parser.FSColor;
import org.xhtmlrenderer.css.parser.FSRGBColor;
import org.xhtmlrenderer.css.style.CalculatedStyle;
import org.xhtmlrenderer.css.value.FontSpecification;
import org.xhtmlrenderer.extend.UserInterface;
import org.xhtmlrenderer.layout.LayoutContext;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.resource.XMLResource;
import org.xhtmlrenderer.simple.extend.XhtmlNamespaceHandler;
import org.xhtmlrenderer.swing.Java2DFontContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Pattern;

/**
 * Experimental replacement for JasperReports' Swing-backed HTML markup
 * processor. Jsoup normalizes tolerant HTML to XHTML and Flying Saucer supplies
 * the parsed document and computed styles; the conversion state and recursive
 * traversal intentionally mirror {@code HtmlEditorKitMarkupProcessor}.
 */
public final class FlyingSaucerHtmlMarkupProcessor implements MarkupProcessor {

    private static final String JASPER_COMPATIBLE_SUB_SUP_RULE =
            "sub, sup { font-size: inherit; }";
    private static final float[] JASPER_LEGACY_FONT_SIZE_POINTS =
            {8f, 10f, 12f, 14f, 18f, 24f, 36f};
    private static final UserInterface NON_INTERACTIVE_USER_INTERFACE =
            new UserInterface() {
                @Override
                public boolean isHover(Element element) {
                    return false;
                }

                @Override
                public boolean isActive(Element element) {
                    return false;
                }

                @Override
                public boolean isFocus(Element element) {
                    return false;
                }
            };

    private Document document;
    private SharedContext flyingSaucerContext;
    private LayoutContext cssContext;
    private Graphics2D fontGraphics;
    private CalculatedStyle bodyStyle;
    private Map<Element, CalculatedStyle> computedStyles;

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
            return new FlyingSaucerHtmlMarkupProcessor();
        }
    }

    @Override
    public String convert(String srcText) {
        if (srcText.indexOf('<') >= 0 || srcText.indexOf('&') >= 0) {
            try {
                JRStyledText styledText = new JRStyledText();

                document = getDocument(srcText);
                initializeFlyingSaucer(document);
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
            } finally {
                if (fontGraphics != null) {
                    fontGraphics.dispose();
                    fontGraphics = null;
                }
            }
        }

        return srcText;
    }

    private Document getDocument(String srcText) {
        org.jsoup.nodes.Document normalized = Jsoup.parse(srcText);
        normalized.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .prettyPrint(false);

        addJasperCompatibilityStyles(normalized);
        normalizeLegacyFontElements(normalized);

        return XMLResource.load(new StringReader(normalized.outerHtml()))
                .getDocument();
    }

    private void addJasperCompatibilityStyles(
            org.jsoup.nodes.Document normalized
    ) {
        normalized.head()
                .appendElement("style")
                .attr("type", "text/css")
                .append(JASPER_COMPATIBLE_SUB_SUP_RULE);
    }

    private void normalizeLegacyFontElements(
            org.jsoup.nodes.Document normalized
    ) {
        for (org.jsoup.nodes.Element font : normalized.select("font")) {
            StringBuilder style = new StringBuilder(font.attr("style"));
            appendLegacyStyle(style, "color", font.attr("color"));
            appendLegacyStyle(style, "font-family", font.attr("face"));

            String size = legacyFontSize(font.attr("size"));
            appendLegacyStyle(style, "font-size", size);

            font.attr("style", style.toString());
        }
    }

    private String legacyFontSize(String sizeAttribute) {
        if (sizeAttribute == null || sizeAttribute.isBlank()) {
            return null;
        }
        try {
            int index = Integer.parseInt(sizeAttribute.trim()) - 1;
            if (index >= 0 && index < JASPER_LEGACY_FONT_SIZE_POINTS.length) {
                return JASPER_LEGACY_FONT_SIZE_POINTS[index] + "pt";
            }
        } catch (NumberFormatException ignored) {
            // Flying Saucer receives the unmodified attribute if it is unsupported.
        }
        return null;
    }

    private void appendLegacyStyle(
            StringBuilder style,
            String property,
            String value
    ) {
        if (value == null || value.isBlank() || containsCssProperty(style, property)) {
            return;
        }
        if (!style.isEmpty() && style.charAt(style.length() - 1) != ';') {
            style.append(';');
        }
        style.append(property).append(':').append(value).append(';');
    }

    private boolean containsCssProperty(StringBuilder style, String property) {
        return style.toString().toLowerCase(Locale.ROOT)
                .matches("(?s).*(?:^|;)\\s*" + property + "\\s*:.*");
    }

    private void initializeFlyingSaucer(Document parsedDocument) {
        flyingSaucerContext = new SharedContext();
        flyingSaucerContext.setDPI(72f);
        flyingSaucerContext.setDotsPerPixel(1);
        flyingSaucerContext.setPrint(false);
        flyingSaucerContext.reset();
        flyingSaucerContext.setBaseURL("");

        XhtmlNamespaceHandler namespaceHandler = new XhtmlNamespaceHandler();
        flyingSaucerContext.setNamespaceHandler(namespaceHandler);
        flyingSaucerContext.getCss().setDocumentContext(
                flyingSaucerContext,
                namespaceHandler,
                parsedDocument,
                NON_INTERACTIVE_USER_INTERFACE
        );

        fontGraphics = new BufferedImage(
                1,
                1,
                BufferedImage.TYPE_INT_ARGB
        ).createGraphics();
        cssContext = flyingSaucerContext.newLayoutContextInstance(
                new Java2DFontContext(fontGraphics)
        );
    }

    private void processElement(JRStyledText styledText, Node parentNode) {
        NodeList children = parentNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);

            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) node;
                String htmlTag = element.getTagName().toLowerCase(Locale.ROOT);

                suppressBreaksFlow |= isListTag(htmlTag);
                // Keep the same aggregate flag used by Jasper's processor. The
                // Flying Saucer's computed display replaces Swing HTML.Tag metadata.
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
        Element element = parentElement(textNode);
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

            Element anchor = ancestor(element, "a");
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

    private Map<Attribute, Object> getAttributes(Element element) {
        Map<Attribute, Object> attrMap = new HashMap<>();
        CalculatedStyle style = getComputedStyle(element);
        FontSpecification font = style.getFont(cssContext);
        FontSpecification bodyFont = bodyStyle == null
                ? null
                : bodyStyle.getFont(cssContext);

        if (bodyFont != null
                && font.families.length != 0
                && !sameFirstFontFamily(font, bodyFont)) {
            attrMap.put(
                    TextAttribute.FAMILY,
                    font.families[0]
            );
        }

        if (differsFromBody(style, CSSName.FONT_WEIGHT)) {
            attrMap.put(
                    TextAttribute.WEIGHT,
                    isBold(font.fontWeight.toString())
                            ? TextAttribute.WEIGHT_BOLD
                            : TextAttribute.WEIGHT_REGULAR
            );
        }

        if (differsFromBody(style, CSSName.FONT_STYLE)) {
            attrMap.put(
                    TextAttribute.POSTURE,
                    isItalic(font.fontStyle.toString())
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

        if (bodyFont != null && Math.abs(font.size - bodyFont.size) > 0.01f) {
            attrMap.put(TextAttribute.SIZE, font.size);
        }

        if (differsFromBody(style, CSSName.COLOR)) {
            Color foreground = toAwtColor(style.getColor());
            if (foreground != null) {
                attrMap.put(TextAttribute.FOREGROUND, foreground);
            }
        }

        Element backgroundElement = backgroundElement(element);
        if (backgroundElement != null) {
            Color background = toAwtColor(
                    getComputedStyle(backgroundElement).getBackgroundColor()
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

    private CalculatedStyle getComputedStyle(Element element) {
        return computedStyles.computeIfAbsent(
                element,
                flyingSaucerContext::getStyle
        );
    }

    private boolean differsFromBody(CalculatedStyle style, CSSName property) {
        return bodyStyle != null
                && !style.asString(property).equals(bodyStyle.asString(property));
    }

    private boolean sameFirstFontFamily(
            FontSpecification left,
            FontSpecification right
    ) {
        return left.families.length != 0
                && right.families.length != 0
                && left.families[0].equals(right.families[0]);
    }

    private String effectiveTextDecoration(Element element) {
        StringBuilder decoration = new StringBuilder();
        for (Element current = element;
             current != null && !"body".equalsIgnoreCase(current.getTagName());
             current = parentElement(current)) {
            String value = getComputedStyle(current)
                    .asString(CSSName.TEXT_DECORATION);
            decoration.append(' ').append(value);
        }
        return decoration.toString();
    }

    private String effectiveVerticalAlign(Element element) {
        for (Element current = element;
             current != null && !"body".equalsIgnoreCase(current.getTagName());
             current = parentElement(current)) {
            String value = getComputedStyle(current)
                    .asString(CSSName.VERTICAL_ALIGN);
            if ("super".equals(value) || "sub".equals(value)) {
                return value;
            }
        }
        return "baseline";
    }

    private Element backgroundElement(Element element) {
        for (Element current = element;
             current != null && !"body".equalsIgnoreCase(current.getTagName());
             current = parentElement(current)) {
            FSColor background = getComputedStyle(current).getBackgroundColor();
            if (background != null) {
                return current;
            }
        }
        return null;
    }

    private Color toAwtColor(FSColor cssColor) {
        if (!(cssColor instanceof FSRGBColor rgb)) {
            return null;
        }
        return new Color(rgb.getRed(), rgb.getGreen(), rgb.getBlue());
    }

    private boolean breaksFlow(Element element) {
        String display = getComputedStyle(element).getDisplay().toString();
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
            Element element
    ) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String whiteSpace = getComputedStyle(element)
                .asString(CSSName.WHITE_SPACE);
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
            if (current instanceof Element element
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
        return Pattern.compile(
                "(^|[^\\p{Alnum}_-])" + Pattern.quote(word)
                        + "($|[^\\p{Alnum}_-])"
        ).matcher(value).find();
    }

    private String attributeOrNull(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private Element ancestor(Element element, String tagName) {
        for (Element current = element; current != null; current = parentElement(current)) {
            if (tagName.equalsIgnoreCase(current.getTagName())) {
                return current;
            }
        }
        return null;
    }

    private Element parentElement(Node node) {
        Node parent = node.getParentNode();
        return parent instanceof Element element ? element : null;
    }

    private void resizeRuns(List<JRStyledText.Run> runs, int startIndex, int count) {
        for (JRStyledText.Run run : runs) {
            if (run.startIndex <= startIndex && run.endIndex > startIndex - count) {
                run.endIndex += count;
            }
        }
    }
}
