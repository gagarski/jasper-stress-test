package com.example.reports;

import com.helger.css.ECSSUnit;
import com.helger.css.ICSSWriterSettings;
import com.helger.css.decl.CSSHSL;
import com.helger.css.decl.CSSHSLA;
import com.helger.css.decl.CSSExpression;
import com.helger.css.decl.CSSExpressionMemberTermSimple;
import com.helger.css.decl.CSSRGB;
import com.helger.css.decl.CSSRGBA;
import com.helger.css.decl.ECSSExpressionOperator;
import com.helger.css.decl.ICSSExpressionMember;
import com.helger.css.property.ECSSProperty;
import com.helger.css.utils.CSSColorHelper;
import com.helger.css.utils.ECSSColor;
import com.helger.css.writer.CSSWriterSettings;
import net.sf.jasperreports.engine.base.JRBasePrintHyperlink;
import net.sf.jasperreports.engine.type.HyperlinkTypeEnum;
import net.sf.jasperreports.engine.util.JRTextAttribute;
import org.jsoup.nodes.Element;

import java.awt.Color;
import java.awt.font.TextAttribute;
import java.text.AttributedCharacterIterator.Attribute;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mutable per-element adapter from parsed ph-css values to the small attribute
 * set supported by Jasper styled text. It deliberately contains no CSS parser,
 * selector matcher, cascade, or shorthand implementation.
 */
final class JasperTextStyle {

    private static final float[] LEGACY_FONT_SIZE_POINTS =
            {8f, 10f, 12f, 14f, 18f, 24f, 36f};
    private static final ICSSWriterSettings CSS_WRITER_SETTINGS =
            new CSSWriterSettings(true);
    private static final Pattern DIMENSION = Pattern.compile(
            "^([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))([a-zA-Z%]*)$"
    );

    static final JasperTextStyle EMPTY = new JasperTextStyle();

    private String family;
    private Boolean bold;
    private Boolean italic;
    private Boolean underline;
    private Boolean strikeThrough;
    private Float size;
    private Integer legacyFontSize;
    private Color foreground;
    private Color background;
    private Integer superscript;
    private Hyperlink hyperlink;
    private boolean preserveWhitespace;

    private JasperTextStyle() {
    }

    private JasperTextStyle(JasperTextStyle source) {
        family = source.family;
        bold = source.bold;
        italic = source.italic;
        underline = source.underline;
        strikeThrough = source.strikeThrough;
        size = source.size;
        legacyFontSize = source.legacyFontSize;
        foreground = source.foreground;
        background = source.background;
        superscript = source.superscript;
        hyperlink = source.hyperlink;
        preserveWhitespace = source.preserveWhitespace;
    }

    static JasperTextStyle forElement(
            JasperTextStyle inherited,
            Element element,
            Map<ECSSProperty, CSSExpression> css
    ) {
        JasperTextStyle style = new JasperTextStyle(inherited);
        applyHtmlSemantics(style, element);
        for (Map.Entry<ECSSProperty, CSSExpression> declaration : css.entrySet()) {
            applyCss(style, inherited, declaration.getKey(), declaration.getValue());
        }
        return style;
    }

    boolean preserveWhitespace() {
        return preserveWhitespace;
    }

    Map<Attribute, Object> toAttributes() {
        if (family == null
                && bold == null
                && italic == null
                && !Boolean.TRUE.equals(underline)
                && !Boolean.TRUE.equals(strikeThrough)
                && size == null
                && foreground == null
                && background == null
                && superscript == null
                && hyperlink == null) {
            return Map.of();
        }

        Map<Attribute, Object> attributes = new HashMap<>(10);
        if (family != null) {
            attributes.put(TextAttribute.FAMILY, family);
        }
        if (bold != null) {
            attributes.put(
                    TextAttribute.WEIGHT,
                    bold ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR
            );
        }
        if (italic != null) {
            attributes.put(
                    TextAttribute.POSTURE,
                    italic ? TextAttribute.POSTURE_OBLIQUE : TextAttribute.POSTURE_REGULAR
            );
        }
        if (Boolean.TRUE.equals(underline)) {
            attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        }
        if (Boolean.TRUE.equals(strikeThrough)) {
            attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        }
        if (size != null) {
            attributes.put(TextAttribute.SIZE, size);
        }
        if (foreground != null) {
            attributes.put(TextAttribute.FOREGROUND, foreground);
        }
        if (background != null) {
            attributes.put(TextAttribute.BACKGROUND, background);
        }
        if (superscript != null) {
            attributes.put(TextAttribute.SUPERSCRIPT, superscript);
        }
        if (hyperlink != null) {
            JRBasePrintHyperlink printHyperlink = new JRBasePrintHyperlink();
            printHyperlink.setHyperlinkType(HyperlinkTypeEnum.REFERENCE);
            printHyperlink.setHyperlinkReference(hyperlink.href());
            printHyperlink.setLinkTarget(hyperlink.target());
            attributes.put(JRTextAttribute.HYPERLINK, printHyperlink);
        }
        return attributes;
    }

    private static void applyHtmlSemantics(
            JasperTextStyle style,
            Element element
    ) {
        switch (element.normalName()) {
            case "b" -> style.bold = true;
            case "i" -> style.italic = true;
            case "u" -> style.underline = true;
            case "s", "strike" -> style.strikeThrough = true;
            case "sub" -> style.superscript = TextAttribute.SUPERSCRIPT_SUB;
            case "sup" -> style.superscript = TextAttribute.SUPERSCRIPT_SUPER;
            case "pre" -> style.preserveWhitespace = true;
            default -> {
            }
        }

        if ("font".equals(element.normalName())
                || "basefont".equals(element.normalName())) {
            applyLegacyFont(style, element);
        }
        if ("a".equals(element.normalName())) {
            style.hyperlink = new Hyperlink(
                    attributeOrNull(element, "href"),
                    attributeOrNull(element, "target")
            );
        }
    }

    private static void applyLegacyFont(
            JasperTextStyle style,
            Element element
    ) {
        String face = attributeOrNull(element, "face");
        if (face != null) {
            style.family = face.split(",", 2)[0].trim();
        }
        Color color = color(attributeOrNull(element, "color"));
        if (color != null) {
            style.foreground = color;
        }
        Integer legacySize = legacySize(
                attributeOrNull(element, "size"), style.legacyFontSize
        );
        if (legacySize != null) {
            style.legacyFontSize = legacySize;
            style.size = LEGACY_FONT_SIZE_POINTS[legacySize - 1];
        }
    }

    private static void applyCss(
            JasperTextStyle style,
            JasperTextStyle inherited,
            ECSSProperty property,
            CSSExpression expression
    ) {
        String value = cssValue(expression);
        if (applyCssWideKeyword(style, inherited, property, value)) {
            return;
        }

        switch (property) {
            case FONT_FAMILY -> style.family = firstFontFamily(expression);
            case FONT_WEIGHT -> style.bold = fontWeight(value);
            case FONT_STYLE -> {
                if ("normal".equals(value)) {
                    style.italic = false;
                } else if ("italic".equals(value) || value.startsWith("oblique")) {
                    style.italic = true;
                }
            }
            case TEXT_DECORATION -> applyTextDecoration(style, expression);
            case FONT_SIZE -> {
                Float computedSize = fontSize(value, style);
                if (computedSize != null) {
                    style.size = computedSize;
                    style.legacyFontSize = null;
                }
            }
            case COLOR -> {
                Color computedColor = color(value);
                if (computedColor != null) {
                    style.foreground = computedColor;
                }
            }
            case BACKGROUND_COLOR -> style.background = "transparent".equals(value)
                    ? null : colorOrCurrent(value, style.background);
            case VERTICAL_ALIGN -> {
                if ("super".equals(value)) {
                    style.superscript = TextAttribute.SUPERSCRIPT_SUPER;
                } else if ("sub".equals(value)) {
                    style.superscript = TextAttribute.SUPERSCRIPT_SUB;
                } else if ("baseline".equals(value)) {
                    style.superscript = null;
                }
            }
            case WHITE_SPACE -> style.preserveWhitespace = switch (value) {
                case "pre", "pre-wrap", "break-spaces" -> true;
                default -> false;
            };
            default -> {
            }
        }
    }

    private static boolean applyCssWideKeyword(
            JasperTextStyle style,
            JasperTextStyle inherited,
            ECSSProperty property,
            String value
    ) {
        switch (value) {
            case "inherit" -> copyProperty(style, inherited, property);
            case "initial" -> initialProperty(style, property);
            case "unset" -> {
                if (isInheritedProperty(property)) {
                    copyProperty(style, inherited, property);
                } else {
                    initialProperty(style, property);
                }
            }
            case "revert", "revert-layer" -> {
                // HTML semantics were applied before author CSS.
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean isInheritedProperty(ECSSProperty property) {
        return switch (property) {
            case FONT_FAMILY, FONT_SIZE, FONT_WEIGHT, FONT_STYLE, COLOR,
                    WHITE_SPACE -> true;
            default -> false;
        };
    }

    private static void copyProperty(
            JasperTextStyle target,
            JasperTextStyle source,
            ECSSProperty property
    ) {
        switch (property) {
            case FONT_FAMILY -> target.family = source.family;
            case FONT_SIZE -> {
                target.size = source.size;
                target.legacyFontSize = source.legacyFontSize;
            }
            case FONT_WEIGHT -> target.bold = source.bold;
            case FONT_STYLE -> target.italic = source.italic;
            case COLOR -> target.foreground = source.foreground;
            case BACKGROUND_COLOR -> target.background = source.background;
            case TEXT_DECORATION -> {
                target.underline = source.underline;
                target.strikeThrough = source.strikeThrough;
            }
            case VERTICAL_ALIGN -> target.superscript = source.superscript;
            case WHITE_SPACE -> target.preserveWhitespace = source.preserveWhitespace;
            default -> {
            }
        }
    }

    private static void initialProperty(
            JasperTextStyle style,
            ECSSProperty property
    ) {
        switch (property) {
            case FONT_FAMILY -> style.family = null;
            case FONT_SIZE -> {
                style.size = null;
                style.legacyFontSize = null;
            }
            case FONT_WEIGHT -> style.bold = false;
            case FONT_STYLE -> style.italic = false;
            case COLOR -> style.foreground = null;
            case BACKGROUND_COLOR -> style.background = null;
            case TEXT_DECORATION -> {
                style.underline = false;
                style.strikeThrough = false;
            }
            case VERTICAL_ALIGN -> style.superscript = null;
            case WHITE_SPACE -> style.preserveWhitespace = false;
            default -> {
            }
        }
    }

    private static void applyTextDecoration(
            JasperTextStyle style,
            CSSExpression expression
    ) {
        style.underline = false;
        style.strikeThrough = false;
        for (CSSExpressionMemberTermSimple term : expression.getAllSimpleMembers()) {
            switch (term.getValue().toLowerCase(Locale.ROOT)) {
                case "underline" -> style.underline = true;
                case "line-through" -> style.strikeThrough = true;
                default -> {
                }
            }
        }
    }

    private static Boolean fontWeight(String value) {
        return switch (value) {
            case "bold", "bolder" -> true;
            case "normal", "lighter" -> false;
            default -> {
                try {
                    yield Float.parseFloat(value) >= 600f;
                } catch (NumberFormatException ignored) {
                    yield null;
                }
            }
        };
    }

    private static Float fontSize(String value, JasperTextStyle style) {
        return switch (value) {
            case "xx-small" -> LEGACY_FONT_SIZE_POINTS[0];
            case "x-small" -> LEGACY_FONT_SIZE_POINTS[1];
            case "small" -> LEGACY_FONT_SIZE_POINTS[2];
            case "medium" -> LEGACY_FONT_SIZE_POINTS[3];
            case "large" -> LEGACY_FONT_SIZE_POINTS[4];
            case "x-large" -> LEGACY_FONT_SIZE_POINTS[5];
            case "xx-large" -> LEGACY_FONT_SIZE_POINTS[6];
            case "larger" -> LEGACY_FONT_SIZE_POINTS[
                    relativeLegacySize(style.legacyFontSize, 1) - 1
                    ];
            case "smaller" -> LEGACY_FONT_SIZE_POINTS[
                    relativeLegacySize(style.legacyFontSize, -1) - 1
                    ];
            default -> dimensionInPoints(value, style.size == null ? 12f : style.size);
        };
    }

    private static Float dimensionInPoints(String value, float inheritedSize) {
        Matcher matcher = DIMENSION.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        float number;
        try {
            number = Float.parseFloat(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        String unitName = matcher.group(2).toLowerCase(Locale.ROOT);
        if (unitName.isEmpty()) {
            return number;
        }
        ECSSUnit unit = ECSSUnit.getFromNameOrNull(unitName);
        if (unit == null) {
            return null;
        }
        return switch (unit) {
            // Keep Swing/Jasper compatibility: its CSS point size mapping treats
            // px numerically rather than converting CSS px to physical points.
            case LENGTH_PT, PX -> number;
            case EM -> number * inheritedSize;
            case REM -> number * 12f;
            case PERCENTAGE -> number * inheritedSize / 100f;
            case LENGTH_PC -> number * 12f;
            case LENGTH_IN -> number * 72f;
            case LENGTH_CM -> number * 72f / 2.54f;
            case LENGTH_MM -> number * 72f / 25.4f;
            case LENGTH_Q -> number * 72f / 101.6f;
            default -> null;
        };
    }

    private static String firstFontFamily(CSSExpression expression) {
        StringBuilder family = new StringBuilder();
        for (ICSSExpressionMember member : expression.getAllMembers()) {
            if (member == ECSSExpressionOperator.COMMA) {
                break;
            }
            if (family.length() > 0) {
                family.append(' ');
            }
            family.append(member.getAsCSSString(CSS_WRITER_SETTINGS, 0));
        }
        String value = family.toString().trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String cssValue(CSSExpression expression) {
        return expression.getAsCSSString(CSS_WRITER_SETTINGS, 0)
                .trim().toLowerCase(Locale.ROOT);
    }

    private static Color colorOrCurrent(String value, Color current) {
        Color parsed = color(value);
        return parsed == null ? current : parsed;
    }

    private static Color color(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        ECSSColor named = ECSSColor.getFromNameCaseInsensitiveOrNull(normalized);
        if (named != null) {
            return new Color(named.getRed(), named.getGreen(), named.getBlue());
        }
        try {
            if (CSSColorHelper.isHexColorValue(normalized)) {
                String hex = normalized.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0)
                            + hex.charAt(1) + hex.charAt(1)
                            + hex.charAt(2) + hex.charAt(2);
                }
                return new Color(Integer.parseInt(hex, 16));
            }
            if (CSSColorHelper.isRGBColorValue(normalized)) {
                return rgbColor(CSSColorHelper.getParsedRGBColorValue(normalized), 255);
            }
            if (CSSColorHelper.isRGBAColorValue(normalized)) {
                CSSRGBA rgba = CSSColorHelper.getParsedRGBAColorValue(normalized);
                return rgbColor(rgba.getAsRGB(), alpha(rgba.getOpacity()));
            }
            if (CSSColorHelper.isHSLColorValue(normalized)) {
                return hslColor(CSSColorHelper.getParsedHSLColorValue(normalized), 255);
            }
            if (CSSColorHelper.isHSLAColorValue(normalized)) {
                CSSHSLA hsla = CSSColorHelper.getParsedHSLAColorValue(normalized);
                return hslColor(hsla.getAsHSL(), alpha(hsla.getOpacity()));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    private static Color rgbColor(CSSRGB rgb, int alpha) {
        return new Color(
                colorChannel(rgb.getRed()),
                colorChannel(rgb.getGreen()),
                colorChannel(rgb.getBlue()),
                alpha
        );
    }

    private static Color hslColor(CSSHSL hsl, int alpha) {
        float hue = Float.parseFloat(hsl.getHue()) / 60f;
        float saturation = percentage(hsl.getSaturation()) / 100f;
        float lightness = percentage(hsl.getLightness()) / 100f;
        float chroma = (1f - Math.abs(2f * lightness - 1f)) * saturation;
        float secondary = chroma * (1f - Math.abs(hue % 2f - 1f));
        float red;
        float green;
        float blue;
        if (hue < 1f) {
            red = chroma;
            green = secondary;
            blue = 0f;
        } else if (hue < 2f) {
            red = secondary;
            green = chroma;
            blue = 0f;
        } else if (hue < 3f) {
            red = 0f;
            green = chroma;
            blue = secondary;
        } else if (hue < 4f) {
            red = 0f;
            green = secondary;
            blue = chroma;
        } else if (hue < 5f) {
            red = secondary;
            green = 0f;
            blue = chroma;
        } else {
            red = chroma;
            green = 0f;
            blue = secondary;
        }
        float match = lightness - chroma / 2f;
        return new Color(
                Math.round((red + match) * 255f),
                Math.round((green + match) * 255f),
                Math.round((blue + match) * 255f),
                alpha
        );
    }

    private static int colorChannel(String value) {
        String normalized = value.trim();
        float channel = normalized.endsWith("%")
                ? Float.parseFloat(normalized.substring(0, normalized.length() - 1))
                        * 2.55f
                : Float.parseFloat(normalized);
        return Math.max(0, Math.min(255, Math.round(channel)));
    }

    private static int alpha(String value) {
        String normalized = value.trim();
        float alpha = normalized.endsWith("%")
                ? Float.parseFloat(normalized.substring(0, normalized.length() - 1))
                        / 100f
                : Float.parseFloat(normalized);
        return Math.max(0, Math.min(255, Math.round(alpha * 255f)));
    }

    private static float percentage(String value) {
        String normalized = value.trim();
        if (normalized.endsWith("%")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return Float.parseFloat(normalized);
    }

    private static Integer legacySize(String value, Integer inheritedSize) {
        if (value == null) {
            return null;
        }
        try {
            int base = inheritedSize == null ? 3 : inheritedSize;
            int parsed = value.startsWith("+")
                    ? base + Integer.parseInt(value.substring(1))
                    : value.startsWith("-")
                    ? base - Integer.parseInt(value.substring(1))
                    : Integer.parseInt(value);
            return clampLegacySize(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int relativeLegacySize(Integer inheritedSize, int delta) {
        return clampLegacySize((inheritedSize == null ? 3 : inheritedSize) + delta);
    }

    private static int clampLegacySize(int size) {
        return Math.max(1, Math.min(LEGACY_FONT_SIZE_POINTS.length, size));
    }

    private static String attributeOrNull(Element element, String name) {
        String value = element.attr(name);
        return value.isEmpty() ? null : value;
    }

    private record Hyperlink(String href, String target) {
    }
}
