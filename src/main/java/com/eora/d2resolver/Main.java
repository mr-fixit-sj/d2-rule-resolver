package com.eora.d2resolver;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command line entry point. */
public final class Main {

    private static final String USAGE = """
            Usage:
              java -jar d2-rule-resolver.jar --config <export dir|zip> [options] attr=value ...
              java -jar d2-rule-resolver.jar --config <export dir|zip> [options] --csv in.csv [--out out.csv]

            Options:
              --as-new              simulate a new import: apply lifecycle entry-state properties first
              --defaults <name>     apply a d2_default_value_config to empty attributes
              --mode <mode>         property page mode to validate: import (default), create, edit
              --user <name>         importing user (USER in where-clauses)
              --groups a,b,c        groups of the importing user (role document sets)
              --today yyyy-MM-dd    date for date(now), date(today), $TODAY (default: today)
              --locale <code>       dictionary locale when a template names none (default: en)
              --dar <path>          Composer workspace/project folder or built .dar (repeatable): type hierarchy,
                                    attribute datatype/length checks, type defaults, TBO warnings
              --types <file>        type hierarchy as child_type=super_type lines (when no DAR is available)
              --type-parent c=p     single type hierarchy edge (repeatable)

            Attributes use Documentum names; dates are ISO (yyyy-MM-dd[ HH:mm:ss]).
            Pass r_object_type so dql_from restrictions can be checked.
            """;

    private Main() {}

    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path config = null, csv = null, csvOut = null, types = null;
        List<Path> dars = new ArrayList<>();
        boolean asNew = false;
        String defaults = null, mode = "import";
        RuntimeContext ctx = RuntimeContext.defaults();
        String user = null, locale = ctx.locale();
        Set<String> groups = null;
        LocalDate today = ctx.today();
        Map<String, String> attrs = new LinkedHashMap<>(), typeEdges = new LinkedHashMap<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> { out.print(USAGE); return; }
                case "--config" -> config = Path.of(value(args, ++i, a));
                case "--csv" -> csv = Path.of(value(args, ++i, a));
                case "--out" -> csvOut = Path.of(value(args, ++i, a));
                case "--as-new" -> asNew = true;
                case "--defaults" -> defaults = value(args, ++i, a);
                case "--mode" -> mode = value(args, ++i, a);
                case "--user" -> user = value(args, ++i, a);
                case "--groups" -> groups = new HashSet<>(Arrays.asList(value(args, ++i, a).split(",")));
                case "--today" -> today = LocalDate.parse(value(args, ++i, a));
                case "--locale" -> locale = value(args, ++i, a);
                case "--types" -> types = Path.of(value(args, ++i, a));
                case "--dar" -> dars.add(Path.of(value(args, ++i, a)));
                case "--type-parent" -> {
                    String[] kv = value(args, ++i, a).split("=", 2);
                    typeEdges.put(kv[0], kv.length > 1 ? kv[1] : "");
                }
                default -> {
                    int eq = a.indexOf('=');
                    if (a.startsWith("--") || eq < 1) fail("unexpected argument: " + a);
                    attrs.put(a.substring(0, eq), a.substring(eq + 1));
                }
            }
        }
        if (config == null) fail("missing --config <D2 export folder or zip>");

        D2Config d2 = D2Config.open(config);
        if (types != null) d2.loadTypeHierarchy(types);
        typeEdges.forEach(d2::addTypeParent);
        DarModel model = dars.isEmpty() ? null : DarModel.load(dars);
        D2RuleResolver resolver = new D2RuleResolver(d2, new RuntimeContext(user, groups, today, locale), model);
        D2RuleResolver.Options options = new D2RuleResolver.Options(asNew, defaults, mode);

        if (csv == null) {
            out.println("D2 export: " + d2.root() + "  (application " + d2.application() + ", D2 " + d2.d2Version() + ")");
            if (model != null) {
                out.println("DAR model: " + model.types().size() + " types, " + model.tbos().size() + " TBOs, Composer "
                        + model.composerVersions() + ", schema " + model.schemaVersions() + " from " + model.sources());
                model.compatibilityWarnings().forEach(w -> out.println("   ! " + w));
            }
            out.println();
            ReportWriter.print(resolver.resolve(attrs, options), out);
            return;
        }

        List<List<String>> rows;
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            rows = Csv.read(in);
        }
        if (rows.isEmpty()) fail("empty CSV: " + csv);
        List<String> header = rows.get(0);
        try (Writer w = csvOut == null ? new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)
                : Files.newBufferedWriter(csvOut, StandardCharsets.UTF_8)) {
            List<String> outHeader = new ArrayList<>(header);
            outHeader.addAll(ReportWriter.CSV_COLUMNS);
            w.write(Csv.line(outHeader));
            for (List<String> row : rows.subList(1, rows.size())) {
                if (row.stream().allMatch(String::isBlank)) continue;
                Map<String, String> doc = new LinkedHashMap<>(attrs);
                for (int c = 0; c < header.size() && c < row.size(); c++)
                    if (!row.get(c).isEmpty()) doc.put(header.get(c), row.get(c));
                List<String> outRow = new ArrayList<>(row);
                while (outRow.size() < header.size()) outRow.add("");
                outRow.addAll(ReportWriter.csvValues(resolver.resolve(doc, options)));
                w.write(Csv.line(outRow));
            }
            w.flush();
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) fail(option + " needs a value");
        return args[i];
    }

    private static void fail(String message) {
        System.err.println(message + "\n\n" + USAGE);
        System.exit(2);
    }
}
