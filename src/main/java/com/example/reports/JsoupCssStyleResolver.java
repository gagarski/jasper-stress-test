package com.example.reports;

import com.helger.css.ICSSWriterSettings;
import com.helger.css.decl.CSSDeclaration;
import com.helger.css.decl.CSSDeclarationList;
import com.helger.css.decl.CSSExpression;
import com.helger.css.decl.CSSExpressionMemberTermSimple;
import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSSelectorAttribute;
import com.helger.css.decl.CSSSelectorMemberFunctionLike;
import com.helger.css.decl.CSSSelectorMemberHost;
import com.helger.css.decl.CSSSelectorMemberHostContext;
import com.helger.css.decl.CSSSelectorMemberNot;
import com.helger.css.decl.CSSSelectorMemberPseudoHas;
import com.helger.css.decl.CSSSelectorMemberPseudoIs;
import com.helger.css.decl.CSSSelectorMemberPseudoWhere;
import com.helger.css.decl.CSSSelectorMemberSlotted;
import com.helger.css.decl.CSSSelectorSimpleMember;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.decl.ICSSSelectorMember;
import com.helger.css.decl.shorthand.CSSShortHandDescriptor;
import com.helger.css.decl.shorthand.CSSShortHandRegistry;
import com.helger.css.handler.DoNothingCSSParseExceptionCallback;
import com.helger.css.property.ECSSProperty;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderDeclarationList;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.DoNothingCSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.DoNothingCSSParseErrorHandler;
import com.helger.css.writer.CSSWriterSettings;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Selector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies the author cascade to ph-css declarations matched against a Jsoup
 * document. Property values remain parsed {@link CSSExpression} objects until
 * they reach the Jasper text-style adapter.
 */
final class JsoupCssStyleResolver {

    private static final ICSSWriterSettings CSS_WRITER_SETTINGS =
            new CSSWriterSettings(true);
    private static final CSSReaderSettings CSS_READER_SETTINGS =
            createReaderSettings();
    private static final Specificity INLINE_SPECIFICITY =
            new Specificity(1, 0, 0, 0);
    private static final long INLINE_ORDER_BASE = 1L << 50;

    private static final Set<ECSSProperty> SUPPORTED_PROPERTIES = Set.of(
            ECSSProperty.FONT_FAMILY,
            ECSSProperty.FONT_SIZE,
            ECSSProperty.FONT_WEIGHT,
            ECSSProperty.FONT_STYLE,
            ECSSProperty.COLOR,
            ECSSProperty.BACKGROUND_COLOR,
            ECSSProperty.TEXT_DECORATION,
            ECSSProperty.VERTICAL_ALIGN,
            ECSSProperty.WHITE_SPACE
    );
    private static final Set<ECSSProperty> SUPPORTED_SHORTHANDS = Set.of(
            ECSSProperty.FONT,
            ECSSProperty.BACKGROUND
    );

    private final List<Rule> rules;
    private final Map<String, List<ParsedDeclaration>> inlineCache = new HashMap<>();

    private JsoupCssStyleResolver(List<Rule> rules) {
        this.rules = rules;
    }

    static JsoupCssStyleResolver from(Document document) {
        List<Rule> rules = new ArrayList<>();
        long sourceOrder = 0;
        for (Element styleElement : document.getElementsByTag("style")) {
            if (!isCssStyleElement(styleElement)) {
                continue;
            }
            CascadingStyleSheet styleSheet = CSSReader.readFromStringReader(
                    styleElement.data(), CSS_READER_SETTINGS
            );
            if (styleSheet == null) {
                continue;
            }
            for (CSSStyleRule styleRule : styleSheet.getAllStyleRules()) {
                List<ParsedDeclaration> declarations = parseDeclarations(
                        styleRule.getAllDeclarations(), sourceOrder
                );
                sourceOrder += Math.max(1, declarations.size());
                if (declarations.isEmpty()) {
                    continue;
                }
                for (CSSSelector selector : styleRule.getAllSelectors()) {
                    String selectorText = selector.getAsCSSString(
                            CSS_WRITER_SETTINGS, 0
                    );
                    if (!selectorText.isBlank()) {
                        rules.add(new Rule(
                                selectorText,
                                specificity(selector),
                                declarations
                        ));
                    }
                }
            }
        }
        return new JsoupCssStyleResolver(List.copyOf(rules));
    }

    Map<ECSSProperty, CSSExpression> resolve(Element element) {
        if (rules.isEmpty() && !element.hasAttr("style")) {
            return Map.of();
        }

        Map<ECSSProperty, Winner> winners = new EnumMap<>(ECSSProperty.class);
        for (Rule rule : rules) {
            if (matches(element, rule.selector())) {
                for (ParsedDeclaration declaration : rule.declarations()) {
                    offer(winners, declaration, rule.specificity());
                }
            }
        }

        String inlineStyle = element.attr("style");
        if (!inlineStyle.isBlank()) {
            List<ParsedDeclaration> declarations = inlineCache.computeIfAbsent(
                    inlineStyle,
                    JsoupCssStyleResolver::parseInlineDeclarations
            );
            for (ParsedDeclaration declaration : declarations) {
                offer(winners, declaration, INLINE_SPECIFICITY);
            }
        }

        if (winners.isEmpty()) {
            return Map.of();
        }
        Map<ECSSProperty, CSSExpression> resolved = new LinkedHashMap<>();
        winners.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(entry -> resolved.put(
                        entry.getKey(), entry.getValue().expression()
                ));
        return resolved;
    }

    private static void offer(
            Map<ECSSProperty, Winner> winners,
            ParsedDeclaration declaration,
            Specificity specificity
    ) {
        Winner candidate = new Winner(
                declaration.expression(), declaration.important(), specificity,
                declaration.sourceOrder()
        );
        winners.merge(
                declaration.property(), candidate,
                (current, replacement) -> current.compareTo(replacement) < 0
                        ? replacement : current
        );
    }

    private static boolean matches(Element element, String selector) {
        try {
            return element.is(selector);
        } catch (Selector.SelectorParseException ignored) {
            return false;
        }
    }

    private static List<ParsedDeclaration> parseInlineDeclarations(String source) {
        CSSDeclarationList list = CSSReaderDeclarationList.readFromString(
                source, CSS_READER_SETTINGS
        );
        return list == null
                ? List.of()
                : parseDeclarations(list.getAllDeclarations(), INLINE_ORDER_BASE);
    }

    private static List<ParsedDeclaration> parseDeclarations(
            Iterable<CSSDeclaration> declarations,
            long initialOrder
    ) {
        List<ParsedDeclaration> parsed = new ArrayList<>();
        long order = initialOrder;
        for (CSSDeclaration declaration : declarations) {
            ECSSProperty property = ECSSProperty.getFromNameOrNull(
                    declaration.getProperty().toLowerCase(Locale.ROOT)
            );
            if (property == ECSSProperty.TEXT_DECORATION_LINE) {
                property = ECSSProperty.TEXT_DECORATION;
            }
            if (property == null) {
                continue;
            }

            if (SUPPORTED_PROPERTIES.contains(property)) {
                parsed.add(new ParsedDeclaration(
                        property, declaration.getExpression(),
                        declaration.isImportant(), order++
                ));
            } else if (SUPPORTED_SHORTHANDS.contains(property)) {
                for (CSSDeclaration expanded : expandShorthand(property, declaration)) {
                    ECSSProperty expandedProperty = ECSSProperty.getFromNameOrNull(
                            expanded.getProperty()
                    );
                    if (SUPPORTED_PROPERTIES.contains(expandedProperty)) {
                        parsed.add(new ParsedDeclaration(
                                expandedProperty, expanded.getExpression(),
                                declaration.isImportant(), order++
                        ));
                    }
                }
            }
        }
        return List.copyOf(parsed);
    }

    private static List<CSSDeclaration> expandShorthand(
            ECSSProperty property,
            CSSDeclaration declaration
    ) {
        CSSShortHandDescriptor descriptor =
                CSSShortHandRegistry.getShortHandDescriptor(property);
        if (descriptor == null) {
            return List.of();
        }
        if (isCssWideExpression(declaration.getExpression())) {
            return descriptor.getAllSubProperties().stream()
                    .map(subProperty -> new CSSDeclaration(
                            subProperty.getProperty().getPropertyName(),
                            declaration.getExpression()
                    ))
                    .toList();
        }
        return descriptor.getSplitIntoPieces(declaration);
    }

    private static boolean isCssWideExpression(CSSExpression expression) {
        if (expression.getMemberCount() != 1
                || !(expression.getMemberAtIndex(0)
                instanceof CSSExpressionMemberTermSimple term)) {
            return false;
        }
        return switch (term.getValue().toLowerCase(Locale.ROOT)) {
            case "inherit", "initial", "unset", "revert", "revert-layer" -> true;
            default -> false;
        };
    }

    private static boolean isCssStyleElement(Element element) {
        String type = element.attr("type");
        if (!type.isBlank() && !"text/css".equalsIgnoreCase(type.trim())) {
            return false;
        }
        String media = element.attr("media").trim();
        return media.isEmpty() || "all".equalsIgnoreCase(media);
    }

    private static CSSReaderSettings createReaderSettings() {
        return new CSSReaderSettings()
                .setBrowserCompliantMode(true)
                .setCustomErrorHandler(new DoNothingCSSParseErrorHandler())
                .setCustomExceptionHandler(new DoNothingCSSParseExceptionCallback())
                .setInterpretErrorHandler(new DoNothingCSSInterpretErrorHandler());
    }

    private static Specificity specificity(CSSSelector selector) {
        Specificity result = Specificity.ZERO;
        for (ICSSSelectorMember member : selector.getAllMembers()) {
            result = result.plus(specificity(member));
        }
        return result;
    }

    private static Specificity specificity(ICSSSelectorMember member) {
        if (member instanceof CSSSelectorSimpleMember simple) {
            if (simple.isHash()) {
                return Specificity.ID;
            }
            if (simple.isClass()) {
                return Specificity.CLASS;
            }
            if (simple.isPseudo()) {
                return simple.getValue().startsWith("::")
                        ? Specificity.TYPE : Specificity.CLASS;
            }
            return simple.isElementName() && !"*".equals(simple.getValue())
                    ? Specificity.TYPE : Specificity.ZERO;
        }
        if (member instanceof CSSSelectorAttribute) {
            return Specificity.CLASS;
        }
        if (member instanceof CSSSelectorMemberPseudoWhere) {
            return Specificity.ZERO;
        }
        if (member instanceof CSSSelectorMemberPseudoIs pseudo) {
            return maximumSpecificity(pseudo.getAllSelectors());
        }
        if (member instanceof CSSSelectorMemberPseudoHas pseudo) {
            return maximumSpecificity(pseudo.getAllSelectors());
        }
        if (member instanceof CSSSelectorMemberNot pseudo) {
            return maximumSpecificity(pseudo.getAllSelectors());
        }
        if (member instanceof CSSSelectorMemberHost host) {
            return Specificity.CLASS.plus(specificity(host.getSelector()));
        }
        if (member instanceof CSSSelectorMemberHostContext host) {
            return Specificity.CLASS.plus(specificity(host.getSelector()));
        }
        if (member instanceof CSSSelectorMemberSlotted slotted) {
            return Specificity.TYPE.plus(specificity(slotted.getSelector()));
        }
        return member instanceof CSSSelectorMemberFunctionLike
                ? Specificity.CLASS : Specificity.ZERO;
    }

    private static Specificity maximumSpecificity(Iterable<CSSSelector> selectors) {
        Specificity maximum = Specificity.ZERO;
        for (CSSSelector selector : selectors) {
            Specificity candidate = specificity(selector);
            if (maximum.compareTo(candidate) < 0) {
                maximum = candidate;
            }
        }
        return maximum;
    }

    private record Rule(
            String selector,
            Specificity specificity,
            List<ParsedDeclaration> declarations
    ) {
    }

    private record ParsedDeclaration(
            ECSSProperty property,
            CSSExpression expression,
            boolean important,
            long sourceOrder
    ) {
    }

    private record Winner(
            CSSExpression expression,
            boolean important,
            Specificity specificity,
            long sourceOrder
    ) implements Comparable<Winner> {
        @Override
        public int compareTo(Winner other) {
            int comparison = Boolean.compare(important, other.important);
            if (comparison == 0) {
                comparison = specificity.compareTo(other.specificity);
            }
            return comparison == 0
                    ? Long.compare(sourceOrder, other.sourceOrder())
                    : comparison;
        }
    }

    private record Specificity(int inline, int ids, int classes, int types)
            implements Comparable<Specificity> {
        private static final Specificity ZERO = new Specificity(0, 0, 0, 0);
        private static final Specificity ID = new Specificity(0, 1, 0, 0);
        private static final Specificity CLASS = new Specificity(0, 0, 1, 0);
        private static final Specificity TYPE = new Specificity(0, 0, 0, 1);

        private Specificity plus(Specificity other) {
            return new Specificity(
                    inline + other.inline,
                    ids + other.ids,
                    classes + other.classes,
                    types + other.types
            );
        }

        @Override
        public int compareTo(Specificity other) {
            int comparison = Integer.compare(inline, other.inline);
            if (comparison == 0) {
                comparison = Integer.compare(ids, other.ids);
            }
            if (comparison == 0) {
                comparison = Integer.compare(classes, other.classes);
            }
            return comparison == 0
                    ? Integer.compare(types, other.types)
                    : comparison;
        }
    }
}
