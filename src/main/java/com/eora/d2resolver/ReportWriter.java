package com.eora.d2resolver;

import com.eora.d2resolver.D2RuleResolver.AclEntry;
import com.eora.d2resolver.D2RuleResolver.Folder;
import com.eora.d2resolver.D2RuleResolver.Result;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Human readable report for one document, and CSV columns for batch runs. */
final class ReportWriter {

    static final List<String> CSV_COLUMNS = List.of(
            "d2_object_name", "d2_folder", "d2_acl_config", "d2_acl", "d2_lifecycle", "d2_lc_state", "d2_a_status",
            "d2_retention", "d2_property_page", "d2_issues", "d2_undecided_modules", "d2_warnings");

    private ReportWriter() {}

    static void print(Result r, PrintStream o) {
        o.println("== Effective attributes");
        new TreeMap<>(r.attributes()).forEach((k, v) -> o.println("   " + k + " = " + v));

        o.println("\n== Document sets matched");
        r.matchedSets().forEach(s -> o.println("   + " + s));
        r.undecidedSets().forEach(s -> o.println("   ? " + s + "   (depends on repository/user state)"));

        o.println("\n== Context matrix (first matching row wins)");
        r.modules().forEach((module, res) -> {
            o.printf("   %-30s %s%n", module, res.found()
                    ? res.config() + "   [row: " + String.join(" > ", res.row().path()) + "]" : "(none)");
            res.undecidedBefore().forEach(u -> o.println("      ? an earlier row may win at runtime: " + u));
        });

        if (r.naming() != null)
            o.println("\n== Autonaming\n   " + r.naming().attribute() + " = " + r.naming().value());

        if (!r.links().isEmpty()) {
            o.println("\n== Target folder(s)  (autolink creates missing folders with the type/ACL below; existing folders are not changed)");
            for (List<Folder> chain : r.links()) {
                o.println("   " + chain.get(chain.size() - 1).path() + "   <- document is linked here");
                for (Folder f : chain) {
                    o.printf("     %-60s type=%-14s acl=%s%s%n", f.path(), f.type(),
                            f.aclConfig().isEmpty() ? "(repository default)" : f.aclConfig(),
                            f.defaultValueConfig().isEmpty() ? "" : "  defaults=" + f.defaultValueConfig());
                    if (f.acl() != null) f.acl().forEach(a -> printAce(o, "         ", a));
                }
            }
        }

        if (!r.documentAcl().isEmpty()) {
            o.println("\n== Document ACL  (" + r.config(D2RuleResolver.ACL) + ")");
            r.documentAcl().forEach(a -> printAce(o, "   ", a));
        }

        if (r.lifecycle() != null) {
            o.println("\n== Lifecycle " + r.lifecycle().lifecycle() + ", entry state '" + r.lifecycle().state() + "'");
            r.lifecycle().sets().forEach((k, v) -> o.println("   sets " + k + " = " + v));
            o.println("   actions " + r.lifecycle().actions());
        }

        if (!r.checks().isEmpty()) {
            String page = r.config(D2RuleResolver.PROPERTY);
            o.println("\n== Attribute checks" + (page.isEmpty() ? "" : " (property page " + page + ")"));
            r.checks().forEach(c -> o.printf("   %-15s %-24s %s%n", c.status(), c.attribute(), c.detail()));
        }

        if (!r.warnings().isEmpty()) {
            o.println("\n== Warnings");
            r.warnings().forEach(w -> o.println("   ! " + w));
        }
    }

    private static void printAce(PrintStream o, String indent, AclEntry a) {
        o.printf("%s%-45s %-8s %-40s %s%n", indent, a.accessor(), a.permit(), a.extendedPermits(), a.note());
    }

    static List<String> csvValues(Result r) {
        String folders = r.links().stream().map(c -> c.get(c.size() - 1).path()).collect(Collectors.joining(" | "));
        String acl = r.documentAcl().stream()
                .map(a -> a.accessor() + ":" + a.permit() + (a.extendedPermits().isEmpty() ? "" : "[" + a.extendedPermits() + "]"))
                .collect(Collectors.joining("; "));
        String issues = r.checks().stream().map(c -> c.status() + " " + c.attribute()).collect(Collectors.joining("; "));
        String undecided = r.modules().entrySet().stream().filter(e -> !e.getValue().undecidedBefore().isEmpty())
                .map(Map.Entry::getKey).collect(Collectors.joining("; "));
        String name = r.naming() != null ? r.naming().value() : r.attributes().getOrDefault("object_name", "");
        return new ArrayList<>(Stream.of(name, folders, r.config(D2RuleResolver.ACL), acl, r.config(D2RuleResolver.LIFECYCLE),
                r.lifecycle() == null ? "" : r.lifecycle().state(), r.attributes().getOrDefault("a_status", ""),
                r.config(D2RuleResolver.RETENTION), r.config(D2RuleResolver.PROPERTY), issues, undecided,
                String.join(" / ", r.warnings())).toList());
    }
}
