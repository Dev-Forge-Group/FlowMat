package org.myweb.flowmat.domain.workflow.domain.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** A small, data-only condition language. No reflection, method calls, or arbitrary code execution. */
public final class ConditionExpression {
    private static final int MAX_LENGTH = 500;
    private static final Object MISSING = new Object();

    private final BooleanNode root;
    private final List<AttributeReference> attributes;

    private ConditionExpression(BooleanNode root, List<AttributeReference> attributes) {
        this.root = root;
        this.attributes = List.copyOf(attributes);
    }

    public static ConditionExpression compile(String source) {
        if (source == null || source.isBlank()) {
            throw invalid(1, "expression is empty");
        }
        if (source.length() > MAX_LENGTH) {
            throw invalid(MAX_LENGTH + 1, "expression exceeds 500 characters");
        }
        Parser parser = new Parser(tokenize(source));
        BooleanNode root = parser.expression();
        parser.expect("EOF");
        return new ConditionExpression(root, parser.attributes);
    }

    public boolean evaluate(Map<String, Object> values) {
        return root.evaluate(values == null ? Map.of() : values);
    }

    public Set<String> attributeReferences() {
        Set<String> names = new HashSet<>();
        for (AttributeReference attribute : attributes) names.add(attribute.name());
        return Set.copyOf(names);
    }

    public void requireDeclaredAttributes(Set<String> propertyNames) {
        if (propertyNames == null) return;
        for (AttributeReference attribute : attributes) {
            if (!propertyNames.contains(attribute.name())) {
                throw invalid(attribute.position(), "attrs." + attribute.name() + " is not declared in schemaJson");
            }
        }
    }

    private interface BooleanNode {
        boolean evaluate(Map<String, Object> values);
    }

    private interface ValueNode {
        Object resolve(Map<String, Object> values);
    }

    private record AttributeReference(String name, int position) {}
    private record Token(String type, String value, int position) {}

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (Character.isWhitespace(ch)) { index++; continue; }
            int start = index;
            if (ch == '\'') {
                StringBuilder value = new StringBuilder();
                index++;
                boolean closed = false;
                while (index < source.length()) {
                    char current = source.charAt(index++);
                    if (current == '\'') { closed = true; break; }
                    if (current == '\\' && index < source.length()) {
                        char escaped = source.charAt(index++);
                        if (escaped != '\'' && escaped != '\\') throw invalid(index, "invalid string escape");
                        value.append(escaped);
                    } else {
                        value.append(current);
                    }
                }
                if (!closed) throw invalid(start + 1, "unterminated string");
                tokens.add(new Token("STRING", value.toString(), start + 1));
                continue;
            }
            if (Character.isDigit(ch) || (ch == '-' && index + 1 < source.length()
                && Character.isDigit(source.charAt(index + 1)))) {
                index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                if (index < source.length() && source.charAt(index) == '.') {
                    index++;
                    if (index >= source.length() || !Character.isDigit(source.charAt(index))) {
                        throw invalid(index + 1, "number needs digits after decimal point");
                    }
                    while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                }
                tokens.add(new Token("NUMBER", source.substring(start, index), start + 1));
                continue;
            }
            if (isIdentifierStart(ch)) {
                index++;
                while (index < source.length() && isIdentifierPart(source.charAt(index))) index++;
                if (source.substring(start, index).equals("attrs") && index < source.length()
                    && source.charAt(index) == '.') {
                    index++;
                    if (index >= source.length() || !isIdentifierStart(source.charAt(index))) {
                        throw invalid(index + 1, "attrs needs a property name");
                    }
                    index++;
                    while (index < source.length() && isIdentifierPart(source.charAt(index))) index++;
                }
                String word = source.substring(start, index);
                String type = switch (word) {
                    case "or", "and", "not", "true", "false" -> word;
                    default -> "IDENT";
                };
                tokens.add(new Token(type, word, start + 1));
                continue;
            }
            if (ch == '(' || ch == ')') {
                tokens.add(new Token(String.valueOf(ch), String.valueOf(ch), start + 1));
                index++;
                continue;
            }
            if (ch == '=' || ch == '!' || ch == '<' || ch == '>') {
                index++;
                if (index < source.length() && source.charAt(index) == '=') index++;
                String operator = source.substring(start, index);
                if (!Set.of("=", "!=", "<", "<=", ">", ">=").contains(operator)) {
                    throw invalid(start + 1, "unsupported comparison operator '" + operator + "'");
                }
                tokens.add(new Token("COMPARE", operator, start + 1));
                continue;
            }
            throw invalid(start + 1, "unexpected character '" + ch + "'");
        }
        tokens.add(new Token("EOF", "", source.length() + 1));
        return tokens;
    }

    private static boolean isIdentifierStart(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || (value >= '0' && value <= '9');
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final List<AttributeReference> attributes = new ArrayList<>();
        private int cursor;

        private Parser(List<Token> tokens) { this.tokens = tokens; }

        private Token current() { return tokens.get(cursor); }

        private boolean take(String type) {
            if (!current().type().equals(type)) return false;
            cursor++;
            return true;
        }

        private Token expect(String type) {
            Token token = current();
            if (!take(type)) throw invalid(token.position(), "expected " + type);
            return token;
        }

        private BooleanNode expression() {
            BooleanNode left = conjunction();
            while (take("or")) {
                BooleanNode previous = left;
                BooleanNode right = conjunction();
                left = values -> previous.evaluate(values) || right.evaluate(values);
            }
            return left;
        }

        private BooleanNode conjunction() {
            BooleanNode left = negation();
            while (take("and")) {
                BooleanNode previous = left;
                BooleanNode right = negation();
                left = values -> previous.evaluate(values) && right.evaluate(values);
            }
            return left;
        }

        private BooleanNode negation() {
            if (take("not")) {
                BooleanNode child = negation();
                return values -> !child.evaluate(values);
            }
            if (take("(")) {
                BooleanNode grouped = expression();
                expect(")");
                return grouped;
            }
            ValueNode left = operand();
            String operator = expect("COMPARE").value();
            ValueNode right = operand();
            return values -> compare(left.resolve(values), right.resolve(values), operator);
        }

        private ValueNode operand() {
            Token token = current();
            switch (token.type()) {
                case "NUMBER" -> { cursor++; BigDecimal value = new BigDecimal(token.value()); return ignored -> value; }
                case "STRING" -> { cursor++; return ignored -> token.value(); }
                case "true", "false" -> { cursor++; boolean value = Boolean.parseBoolean(token.value()); return ignored -> value; }
                case "IDENT" -> {
                    cursor++;
                    String name = token.value();
                    if (name.startsWith("attrs.")) {
                        String property = name.substring(6);
                        attributes.add(new AttributeReference(property, token.position()));
                        return values -> {
                            Object attrs = values.get("attrs");
                            return attrs instanceof Map<?, ?> map && map.containsKey(property)
                                ? map.get(property) : MISSING;
                        };
                    }
                    if (!Set.of("quantity", "unit", "item").contains(name)) {
                        throw invalid(token.position(), "unknown operand '" + name + "'");
                    }
                    return values -> values.containsKey(name) ? values.get(name) : MISSING;
                }
                default -> throw invalid(token.position(), "expected operand");
            }
        }
    }

    private static boolean compare(Object left, Object right, String operator) {
        if (left == MISSING || right == MISSING || left == null || right == null) return false;
        int order;
        if (left instanceof Number first && right instanceof Number second) {
            try { order = new BigDecimal(first.toString()).compareTo(new BigDecimal(second.toString())); }
            catch (NumberFormatException exception) { return false; }
        } else if (left instanceof String first && right instanceof String second) {
            order = first.compareTo(second);
        } else if (left instanceof Boolean first && right instanceof Boolean second) {
            if (!operator.equals("=") && !operator.equals("!=")) return false;
            order = first.compareTo(second);
        } else {
            return false;
        }
        return switch (operator) {
            case "=" -> order == 0;
            case "!=" -> order != 0;
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            case ">=" -> order >= 0;
            default -> false;
        };
    }

    private static BusinessException invalid(int position, String reason) {
        return new BusinessException(ErrorCode.BAD_REQUEST,
            "Invalid condition at position " + position + ": " + reason);
    }
}
