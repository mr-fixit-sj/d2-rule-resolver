package com.eora.d2resolver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Evaluates the subset of DQL used in d2_documentset where-clauses against an in-memory attribute map.
 *
 * Supported: AND/OR/NOT, parentheses, = <> != < > <= >=, [NOT] IN (list), [NOT] LIKE, IS [NOT] NULL/NULLDATE/NULLSTRING,
 * ANY, date(now)/date(today), USER. Sub-selects are answered from the export when they only read d2_dictionary_value
 * aliases or group membership; everything else that needs the repository (Folder(), dmc_rps_*, ...) yields {@link Tri#U}.
 */
final class DqlEvaluator {

    private static final Pattern TOKEN = Pattern.compile(
            "\\s*('(?:[^']|'')*'|<>|!=|<=|>=|[=<>(),]|[A-Za-z_$][A-Za-z0-9_$.]*|-?\\d+(?:\\.\\d+)?)");
    private static final Pattern DICT_ALIAS_SELECT = Pattern.compile(
            "(?i)select object_name from d2_dictionary_value where dictionary_name = '([^']+)' and any alias_value (=|<>) '([^']*)'");
    private static final Pattern GROUP_MEMBERS_SELECT = Pattern.compile(
            "(?i)select i_all_users_names from dm_group where group_name = '([^']+)'");

    private final String source;
    private final Map<String, String> attrs;
    private final D2Config config;
    private final RuntimeContext ctx;
    private final Consumer<String> warn;
    private final List<String> tokens = new ArrayList<>();
    private int pos;

    DqlEvaluator(String source, Map<String, String> attrs, D2Config config, RuntimeContext ctx, Consumer<String> warn) {
        this.source = source;
        this.attrs = attrs;
        this.config = config;
        this.ctx = ctx;
        this.warn = warn;
    }

    Tri evaluate() {
        try {
            tokenize();
            Tri r = or();
            if (pos < tokens.size()) throw new IllegalStateException("trailing tokens " + tokens.subList(pos, tokens.size()));
            return r;
        } catch (RuntimeException e) {
            warn.accept("unsupported DQL treated as undecidable: " + source.replaceAll("\\s+", " ") + " (" + e.getMessage() + ")");
            return Tri.U;
        }
    }

    private void tokenize() {
        Matcher m = TOKEN.matcher(source);
        int at = 0;
        while (at < source.length() && m.find(at) && m.start() == at) {
            tokens.add(m.group(1));
            at = m.end();
        }
        if (!source.substring(at).isBlank()) throw new IllegalArgumentException("cannot tokenize near: " + source.substring(at));
    }

    // ------------------------------------------------------------------ grammar

    private String peek() { return pos < tokens.size() ? tokens.get(pos) : ""; }

    private String next() {
        if (pos >= tokens.size()) throw new IllegalStateException("unexpected end of clause");
        return tokens.get(pos++);
    }

    private boolean accept(String keyword) {
        if (peek().equalsIgnoreCase(keyword)) { pos++; return true; }
        return false;
    }

    private void expect(String keyword) {
        if (!accept(keyword)) throw new IllegalStateException("expected " + keyword + " but got '" + peek() + "'");
    }

    private Tri or() {
        Tri r = and();
        while (accept("or")) r = r.or(and());
        return r;
    }

    private Tri and() {
        Tri r = not();
        while (accept("and")) r = r.and(not());
        return r;
    }

    private Tri not() {
        return accept("not") ? not().not() : primary();
    }

    private Tri primary() {
        if (peek().equals("(")) {
            int save = pos++;
            Tri r = or();
            if (accept(")")) return r;
            pos = save; // the parenthesis belonged to an operand
        }
        return predicate();
    }

    /** Raw text inside the next balanced parentheses (used for function arguments and sub-selects). */
    private String group() {
        expect("(");
        int depth = 1;
        StringBuilder sb = new StringBuilder();
        while (pos < tokens.size()) {
            String t = tokens.get(pos++);
            if (t.equals("(")) depth++;
            if (t.equals(")") && --depth == 0) return sb.toString().trim();
            sb.append(t).append(' ');
        }
        throw new IllegalStateException("unbalanced parentheses");
    }

    /** Operand value, or null when it cannot be known offline. */
    private String operand() {
        accept("any"); // repeating attributes are modelled single-valued
        String t = next();
        if (t.startsWith("'")) return unquote(t);
        if (t.matches("-?\\d.*")) return t;
        if (t.equalsIgnoreCase("user")) return ctx.user();
        if (peek().equals("(")) {
            String arg = group();
            if (t.equalsIgnoreCase("date") && arg.matches("(?i)now|today")) return ctx.today().toString();
            return null;
        }
        if (t.equalsIgnoreCase("nulldate") || t.equalsIgnoreCase("nullstring")) return "";
        return attrs.getOrDefault(t.toLowerCase(), "");
    }

    private Tri predicate() {
        // Folder('/path') depends on where the object currently lives in the repository
        if (peek().equalsIgnoreCase("folder") && pos + 1 < tokens.size() && tokens.get(pos + 1).equals("(")) {
            pos++;
            group();
            return Tri.U;
        }
        String left = operand();
        if (accept("is")) {
            boolean negate = accept("not");
            String what = next();
            if (!what.matches("(?i)null|nulldate|nullstring")) throw new IllegalStateException("IS " + what);
            Tri r = left == null ? Tri.U : Tri.of(left.isEmpty());
            return negate ? r.not() : r;
        }
        boolean negate = accept("not");
        if (accept("in")) {
            Set<String> values = inList();
            Tri r = values == null || left == null ? Tri.U : Tri.of(values.contains(left));
            return negate ? r.not() : r;
        }
        if (accept("like")) {
            String pattern = operand();
            if (left == null || pattern == null) return Tri.U;
            String regex = Pattern.quote(pattern).replace("%", "\\E.*\\Q").replace("_", "\\E.\\Q");
            Tri r = Tri.of(left.matches(regex));
            return negate ? r.not() : r;
        }
        String op = next();
        String right = operand();
        if (left == null || right == null) return Tri.U;
        int c = compare(left, right);
        return switch (op) {
            case "=" -> Tri.of(c == 0);
            case "<>", "!=" -> Tri.of(c != 0);
            case "<" -> Tri.of(c < 0);
            case ">" -> Tri.of(c > 0);
            case "<=" -> Tri.of(c <= 0);
            case ">=" -> Tri.of(c >= 0);
            default -> throw new IllegalStateException("operator " + op);
        };
    }

    /** Literal IN list or sub-select; null when the sub-select needs the repository. */
    private Set<String> inList() {
        String inner = group();
        if (inner.toLowerCase().startsWith("select")) return subSelect(inner);
        Set<String> values = new LinkedHashSet<>();
        for (String part : inner.split(" , ")) values.add(unquote(part.trim()));
        return values;
    }

    private Set<String> subSelect(String query) {
        Matcher dict = DICT_ALIAS_SELECT.matcher(query);
        if (dict.matches()) {
            boolean equal = dict.group(2).equals("=");
            String alias = dict.group(3);
            return config.dictionary(dict.group(1)).values().stream()
                    .filter(v -> v.aliases().values().stream().anyMatch(a -> a.equals(alias) == equal))
                    .map(D2Config.DictValue::name)
                    .collect(Collectors.toSet());
        }
        Matcher members = GROUP_MEMBERS_SELECT.matcher(query);
        if (members.matches() && ctx.user() != null && ctx.groups() != null)
            return ctx.groups().contains(members.group(1)) ? Set.of(ctx.user()) : Set.of();
        return null;
    }

    private static String unquote(String t) {
        return t.startsWith("'") && t.endsWith("'") ? t.substring(1, t.length() - 1).replace("''", "'") : t;
    }

    private static int compare(String a, String b) {
        LocalDate da = Dates.parse(a), db = Dates.parse(b);
        return da != null && db != null ? da.compareTo(db) : a.compareTo(b);
    }
}
