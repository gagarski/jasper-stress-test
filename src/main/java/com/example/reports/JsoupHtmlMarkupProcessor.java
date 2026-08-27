package com.example.reports;

import net.sf.jasperreports.engine.util.JRStyledText;
import net.sf.jasperreports.engine.util.JRStyledTextParser;
import net.sf.jasperreports.engine.util.JRTextAttribute;
import net.sf.jasperreports.engine.util.MarkupProcessor;
import net.sf.jasperreports.engine.util.MarkupProcessorFactory;
import net.sf.jasperreports.engine.util.StyledTextListInfo;
import net.sf.jasperreports.engine.util.StyledTextListItemInfo;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.text.AttributedCharacterIterator.Attribute;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Stateless DOM-to-Jasper traversal. Jsoup supplies the tolerant HTML tree,
 * {@link JsoupCssStyleResolver} supplies parsed and cascaded ph-css values, and
 * {@link JasperTextStyle} maps those values to Jasper styled-text attributes.
 */
public final class JsoupHtmlMarkupProcessor implements MarkupProcessor {

    public static final class Factory implements MarkupProcessorFactory {
        @Override
        public MarkupProcessor createMarkupProcessor() {
            return new JsoupHtmlMarkupProcessor();
        }
    }

    @Override
    public String convert(String source) {
        if (source.indexOf('<') < 0 && source.indexOf('&') < 0) {
            return source;
        }

        org.jsoup.nodes.Document document = Jsoup.parseBodyFragment(source);
        JsoupCssStyleResolver styleResolver = JsoupCssStyleResolver.from(document);
        JRStyledText styledText = new JRStyledText();
        Element body = document.body();
        new Traversal(styledText, styleResolver).processChildren(
                body,
                JasperTextStyle.forElement(
                        JasperTextStyle.EMPTY, body, styleResolver.resolve(body)
                )
        );
        styledText.setGlobalAttributes(new HashMap<>());
        return JRStyledTextParser.getInstance().write(styledText);
    }

    private static final class Traversal {
        private final JRStyledText styledText;
        private final JsoupCssStyleResolver styleResolver;
        private final Stack<StyledTextListInfo> listStack = new Stack<>();

        private boolean insideListItem;
        private boolean listItemStart;
        private StyledTextListInfo justClosedList;
        private boolean pendingBlockBreak;
        private char lastOutputCharacter;

        private Traversal(
                JRStyledText styledText,
                JsoupCssStyleResolver styleResolver
        ) {
            this.styledText = styledText;
            this.styleResolver = styleResolver;
        }

        private void processChildren(
                Element parent,
                JasperTextStyle inheritedStyle
        ) {
            for (Node child : parent.childNodes()) {
                processNode(child, inheritedStyle);
            }
        }

        private void processNode(Node node, JasperTextStyle inheritedStyle) {
            if (node instanceof TextNode textNode) {
                appendText(textNode, inheritedStyle);
                return;
            }
            if (!(node instanceof Element element)) {
                return;
            }

            String tag = element.normalName();
            if (isNonRendered(tag)) {
                return;
            }
            if ("br".equals(tag)) {
                if (hasFollowingContent(element)) {
                    appendNewline();
                }
                pendingBlockBreak = false;
                return;
            }

            JasperTextStyle style = JasperTextStyle.forElement(
                    inheritedStyle, element, styleResolver.resolve(element)
            );
            if ("ol".equals(tag) || "ul".equals(tag)) {
                processList(element, style, "ol".equals(tag));
                return;
            }
            if ("li".equals(tag)) {
                processListItem(element, style);
                return;
            }
            if ("p".equals(tag) && !hasMeaningfulChild(element)) {
                appendNewline();
                return;
            }

            boolean block = breaksFlow(tag);
            if (block && styledText.length() > 0) {
                pendingBlockBreak = true;
            }
            processChildren(element, style);
            if (block && styledText.length() > 0) {
                pendingBlockBreak = true;
            }
        }

        private void processList(
                Element element,
                JasperTextStyle style,
                boolean ordered
        ) {
            String type = attributeOrNull(element, "type");
            Integer start = ordered ? integerAttribute(element, "start") : null;
            StyledTextListInfo list = new StyledTextListInfo(
                    ordered,
                    ordered ? type : null,
                    start,
                    insideListItem
            );
            list.setAtLiStart(listItemStart);
            listStack.push(list);
            insideListItem = false;

            Map<Attribute, Object> attributes = new HashMap<>(2);
            attributes.put(
                    JRTextAttribute.HTML_LIST,
                    listStack.toArray(new StyledTextListInfo[0])
            );
            attributes.put(
                    JRTextAttribute.HTML_LIST_ITEM,
                    StyledTextListItemInfo.NO_LIST_ITEM_FILLER
            );

            int startIndex = styledText.length();
            processChildren(element, style);
            styledText.addRun(new JRStyledText.Run(
                    attributes,
                    startIndex,
                    styledText.length()
            ));
            justClosedList = listStack.pop();
        }

        private void processListItem(Element element, JasperTextStyle style) {
            Map<Attribute, Object> attributes = new HashMap<>(2);
            StyledTextListInfo list;
            boolean syntheticList = false;
            if (listStack.isEmpty()) {
                list = new StyledTextListInfo(false, null, null, false);
                listStack.push(list);
                attributes.put(
                        JRTextAttribute.HTML_LIST,
                        listStack.toArray(new StyledTextListInfo[0])
                );
                attributes.put(
                        JRTextAttribute.HTML_LIST_ITEM,
                        StyledTextListItemInfo.NO_LIST_ITEM_FILLER
                );
                syntheticList = true;
            } else {
                list = listStack.peek();
            }

            list.setItemCount(list.getItemCount() + 1);
            insideListItem = true;
            listItemStart = true;
            justClosedList = null;
            attributes.put(
                    JRTextAttribute.HTML_LIST_ITEM,
                    new StyledTextListItemInfo(list.getItemCount() - 1)
            );

            int startIndex = styledText.length();
            processChildren(element, style);
            styledText.addRun(new JRStyledText.Run(
                    attributes,
                    startIndex,
                    styledText.length()
            ));

            insideListItem = false;
            listItemStart = false;
            if (justClosedList != null) {
                justClosedList.setAtLiEnd(true);
            }
            if (syntheticList) {
                listStack.pop();
            }
        }

        private void appendText(TextNode textNode, JasperTextStyle style) {
            String text = normalizeWhitespace(
                    textNode.getWholeText(),
                    style.preserveWhitespace(),
                    hasFollowingContent(textNode)
            );
            if (text.isEmpty()) {
                return;
            }
            if (pendingBlockBreak && styledText.length() > 0) {
                appendNewline();
            }
            pendingBlockBreak = false;
            listItemStart = false;
            justClosedList = null;

            int startIndex = styledText.length();
            styledText.append(text);
            lastOutputCharacter = text.charAt(text.length() - 1);
            styledText.addRun(new JRStyledText.Run(
                    style.toAttributes(),
                    startIndex,
                    styledText.length()
            ));
        }

        private String normalizeWhitespace(
                String source,
                boolean preserve,
                boolean contentFollows
        ) {
            if (source.isEmpty()) {
                return "";
            }
            if (preserve) {
                return source.replace("\r\n", "\n")
                        .replace('\r', '\n')
                        .replace("\n", "\n\n");
            }

            StringBuilder normalized = new StringBuilder(source.length());
            boolean whitespace = false;
            for (int i = 0; i < source.length(); i++) {
                char character = source.charAt(i);
                if (isCollapsibleWhitespace(character)) {
                    whitespace = true;
                } else {
                    if (whitespace && normalized.length() > 0) {
                        normalized.append(' ');
                    } else if (whitespace && styledText.length() > 0
                            && !pendingBlockBreak
                            && !isOutputWhitespace()) {
                        normalized.append(' ');
                    }
                    normalized.append(character);
                    whitespace = false;
                }
            }
            if (whitespace && contentFollows) {
                if (normalized.length() > 0
                        || (styledText.length() > 0
                        && !pendingBlockBreak
                        && !isOutputWhitespace())) {
                    normalized.append(' ');
                }
            }
            return normalized.toString();
        }

        private void appendNewline() {
            styledText.append("\n");
            lastOutputCharacter = '\n';
            resizeRuns(styledText.getRuns(), styledText.length(), 1);
        }

        private boolean isOutputWhitespace() {
            return lastOutputCharacter == ' '
                    || lastOutputCharacter == '\n'
                    || lastOutputCharacter == '\r'
                    || lastOutputCharacter == '\t';
        }
    }

    private static boolean breaksFlow(String tag) {
        return switch (tag) {
            case "address", "blockquote", "div", "dl", "dt", "dd", "form",
                    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "p", "pre",
                    "table", "caption", "tr" -> true;
            default -> false;
        };
    }

    private static boolean isNonRendered(String tag) {
        return "head".equals(tag)
                || "style".equals(tag)
                || "script".equals(tag)
                || "title".equals(tag)
                || "template".equals(tag);
    }

    private static boolean hasFollowingContent(Node node) {
        for (Node current = node; current != null; current = current.parent()) {
            for (Node sibling = current.nextSibling(); sibling != null;
                 sibling = sibling.nextSibling()) {
                if (isMeaningful(sibling)) {
                    return true;
                }
            }
            if (current.parent() instanceof Element parent
                    && "body".equals(parent.normalName())) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasMeaningfulChild(Element element) {
        for (Node child : element.childNodes()) {
            if (isMeaningful(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMeaningful(Node node) {
        if (node instanceof TextNode textNode) {
            return !textNode.getWholeText().isBlank();
        }
        return node instanceof Element element && !isNonRendered(element.normalName());
    }

    private static boolean isCollapsibleWhitespace(char character) {
        return character == ' '
                || character == '\t'
                || character == '\n'
                || character == '\r'
                || character == '\f';
    }

    private static String attributeOrNull(Element element, String name) {
        String value = element.attr(name);
        return value.isEmpty() ? null : value;
    }

    private static Integer integerAttribute(Element element, String name) {
        String value = attributeOrNull(element, name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void resizeRuns(
            List<JRStyledText.Run> runs,
            int startIndex,
            int count
    ) {
        for (JRStyledText.Run run : runs) {
            if (run.startIndex <= startIndex && run.endIndex > startIndex - count) {
                run.endIndex += count;
            }
        }
    }

}
