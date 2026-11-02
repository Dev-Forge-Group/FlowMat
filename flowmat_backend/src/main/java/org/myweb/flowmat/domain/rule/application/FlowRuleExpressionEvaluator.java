package org.myweb.flowmat.domain.rule.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Evaluates rule conditions.
 *
 * <p>Grammar (lowest precedence first):
 * <pre>
 *   or         := and ( ("||" | "or") and )*
 *   and        := unary ( ("&&" | "and") unary )*
 *   unary      := ("!" | "not") unary | "(" or ")" | comparison
 *   comparison := operand ("==" | "!=" | ">=" | "<=" | ">" | "<") operand
 * </pre>
 * Keywords are case-insensitive and only recognised as whole words outside quotes, so {@code 'or'} is still a string.
 * A single comparison, which is all older rules contain, parses exactly as before.
 */
@Component
public class FlowRuleExpressionEvaluator {

    private static final Pattern COMPARISON_PATTERN =
        Pattern.compile("^\\s*(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+?)\\s*$");

    private static final Object UNRESOLVED = new Object();

    private final ObjectMapper objectMapper;

    public FlowRuleExpressionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean evaluate(String conditionType, String conditionExpression, Map<String, Object> facts) {
        String normalizedType = normalize(conditionType);
        if ("always".equals(normalizedType)) {
            return true;
        }
        if ("never".equals(normalizedType)) {
            return false;
        }

        Node root;
        try {
            root = parse(conditionExpression);
        } catch (IllegalArgumentException e) {
            // Rules saved before validation existed may be malformed; they never match rather than failing the run.
            return false;
        }
        return root.test(facts);
    }

    /** Rejects an expression the evaluator could not run, so a typo fails at save time instead of silently never matching. */
    public void validate(String conditionExpression) {
        try {
            parse(conditionExpression);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid condition: " + e.getMessage());
        }
    }

    private Node parse(String expression) {
        List<Token> tokens = tokenize(expression == null ? "" : expression);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("the expression is empty.");
        }
        Parser parser = new Parser(tokens);
        Node root = parser.parseOr();
        if (parser.position < tokens.size()) {
            throw new IllegalArgumentException("unexpected '" + tokens.get(parser.position).text() + "'.");
        }
        return root;
    }

    // ---- tokenizer ----

    private enum TokenType { AND, OR, NOT, LPAREN, RPAREN, COMPARISON }

    private record Token(TokenType type, String text) {
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder comparison = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\'' || c == '"') {
                int end = input.indexOf(c, i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unclosed quote.");
                }
                comparison.append(input, i, end + 1);
                i = end + 1;
            } else if (c == '(' || c == ')') {
                flush(comparison, tokens);
                tokens.add(new Token(c == '(' ? TokenType.LPAREN : TokenType.RPAREN, String.valueOf(c)));
                i++;
            } else if (input.startsWith("&&", i) || input.startsWith("||", i)) {
                flush(comparison, tokens);
                tokens.add(new Token(c == '&' ? TokenType.AND : TokenType.OR, input.substring(i, i + 2)));
                i += 2;
            } else if (c == '!' && !input.startsWith("!=", i)) {
                flush(comparison, tokens);
                tokens.add(new Token(TokenType.NOT, "!"));
                i++;
            } else if (isWordChar(c) && (i == 0 || !isWordChar(input.charAt(i - 1)))) {
                int end = i;
                while (end < input.length() && isWordChar(input.charAt(end))) {
                    end++;
                }
                String word = input.substring(i, end);
                TokenType keyword = switch (word.toLowerCase()) {
                    case "and" -> TokenType.AND;
                    case "or" -> TokenType.OR;
                    case "not" -> TokenType.NOT;
                    default -> null;
                };
                if (keyword != null) {
                    flush(comparison, tokens);
                    tokens.add(new Token(keyword, word));
                } else {
                    comparison.append(word);
                }
                i = end;
            } else {
                comparison.append(c);
                i++;
            }
        }
        flush(comparison, tokens);
        return tokens;
    }

    private static void flush(StringBuilder comparison, List<Token> tokens) {
        String text = comparison.toString().trim();
        if (!text.isEmpty()) {
            tokens.add(new Token(TokenType.COMPARISON, text));
        }
        comparison.setLength(0);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    // ---- parser ----

    @FunctionalInterface
    private interface Node {
        boolean test(Map<String, Object> facts);
    }

    private final class Parser {
        private final List<Token> tokens;
        private int position;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (accept(TokenType.OR)) {
                Node l = left;
                Node r = parseAnd();
                left = facts -> l.test(facts) || r.test(facts);
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseUnary();
            while (accept(TokenType.AND)) {
                Node l = left;
                Node r = parseUnary();
                left = facts -> l.test(facts) && r.test(facts);
            }
            return left;
        }

        private Node parseUnary() {
            if (accept(TokenType.NOT)) {
                Node operand = parseUnary();
                return facts -> !operand.test(facts);
            }
            if (accept(TokenType.LPAREN)) {
                Node inner = parseOr();
                if (!accept(TokenType.RPAREN)) {
                    throw new IllegalArgumentException("missing ')'.");
                }
                return inner;
            }
            if (position >= tokens.size()) {
                throw new IllegalArgumentException("the expression ends where a comparison was expected.");
            }
            Token token = tokens.get(position);
            if (token.type() != TokenType.COMPARISON) {
                throw new IllegalArgumentException("expected a comparison before '" + token.text() + "'.");
            }
            position++;
            return comparison(token.text());
        }

        private boolean accept(TokenType type) {
            if (position < tokens.size() && tokens.get(position).type() == type) {
                position++;
                return true;
            }
            return false;
        }
    }

    private Node comparison(String text) {
        Matcher matcher = COMPARISON_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("'" + text + "' is not a comparison like fact > 10.");
        }
        String leftToken = matcher.group(1);
        String operator = matcher.group(2);
        String rightToken = matcher.group(3);
        return facts -> compare(resolveToken(leftToken, facts), operator, resolveToken(rightToken, facts));
    }

    // ---- comparison ----

    private Object resolveToken(String token, Map<String, Object> facts) {
        String trimmed = token.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        if (isNumeric(trimmed)) {
            return new BigDecimal(trimmed);
        }

        Object resolved = resolvePath(facts, trimmed);
        return resolved != UNRESOLVED ? resolved : trimmed;
    }

    private Object resolvePath(Map<String, Object> facts, String path) {
        String[] segments = path.split("\\.");
        if (!facts.containsKey(segments[0])) {
            return UNRESOLVED;
        }
        Object current = facts.get(segments[0]);

        for (int i = 1; i < segments.length; i++) {
            if (current == null) {
                return UNRESOLVED;
            }
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(segments[i])) {
                    return UNRESOLVED;
                }
                current = map.get(segments[i]);
                continue;
            }
            Map<String, Object> valueMap = objectMapper.convertValue(current, new TypeReference<Map<String, Object>>() {
            });
            if (!valueMap.containsKey(segments[i])) {
                return UNRESOLVED;
            }
            current = valueMap.get(segments[i]);
        }
        return current;
    }

    private boolean compare(Object left, String operator, Object right) {
        if ("==".equals(operator)) {
            return compareEquality(left, right);
        }
        if ("!=".equals(operator)) {
            return !compareEquality(left, right);
        }

        BigDecimal leftNumber = toBigDecimal(left);
        BigDecimal rightNumber = toBigDecimal(right);
        if (leftNumber == null || rightNumber == null) {
            return false;
        }

        int compareResult = leftNumber.compareTo(rightNumber);
        return switch (operator) {
            case ">" -> compareResult > 0;
            case ">=" -> compareResult >= 0;
            case "<" -> compareResult < 0;
            case "<=" -> compareResult <= 0;
            default -> false;
        };
    }

    private boolean compareEquality(Object left, Object right) {
        BigDecimal leftNumber = toBigDecimal(left);
        BigDecimal rightNumber = toBigDecimal(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber) == 0;
        }
        if (left == null || right == null) {
            return left == right;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String stringValue && isNumeric(stringValue.trim())) {
            return new BigDecimal(stringValue.trim());
        }
        return null;
    }

    private static boolean isNumeric(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String normalize(String value) {
        return value == null ? "expression" : value.trim().toLowerCase();
    }
}
