package com.eora.d2resolver;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Opt-in check against real client material, which is read in place and never copied into the project:
 * <pre>
 * mvn verify -Dd2.export.dir=/path/to/export(.zip) [-Dd2.dar.dir=/path/to/composer-workspace-or.dar[,more]]
 * </pre>
 * Reports matrix rows pointing to missing document sets or configs and, with a DAR, types the export uses
 * that the DAR does not define.
 */
class RealExportSmokeTest {

    private static final Set<String> FLAT_MODULES = Set.of(D2RuleResolver.LINK, D2RuleResolver.ACL, D2RuleResolver.NAMING);

    @Test
    void loadsAndResolves() throws IOException {
        String dir = System.getProperty("d2.export.dir", "");
        assumeTrue(!dir.isBlank(), "set -Dd2.export.dir to run against a real export");
        String darDirs = System.getProperty("d2.dar.dir", "");

        D2Config config = D2Config.open(Path.of(dir));
        DarModel model = darDirs.isBlank() ? null
                : DarModel.load(Arrays.stream(darDirs.split(",")).map(String::trim).map(Path::of).toList());

        List<String> problems = new ArrayList<>();
        Set<String> usedTypes = new TreeSet<>();
        for (Map.Entry<String, List<D2Config.SwitchEntry>> module : config.switches().entrySet()) {
            for (D2Config.SwitchEntry row : module.getValue()) {
                row.path().stream().filter(s -> config.docSet(s).isEmpty())
                        .forEach(s -> problems.add(module.getKey() + ": unknown document set '" + s + "'"));
                if (FLAT_MODULES.contains(module.getKey()) && config.flat(module.getKey(), row.config()).isEmpty())
                    problems.add(module.getKey() + ": config '" + row.config() + "' not in export");
                if (module.getKey().equals(D2RuleResolver.LINK))
                    config.flat(D2RuleResolver.LINK, row.config()).ifPresent(link -> link.list("type")
                            .forEach(t -> Arrays.stream(t.split("/")).filter(x -> !x.isBlank()).forEach(usedTypes::add)));
            }
        }
        config.docSetsByOrder().forEach(d -> d.from().forEach(t -> usedTypes.add(t.toLowerCase())));

        if (model != null) {
            usedTypes.stream().filter(t -> !t.startsWith("dm_") && model.type(t).isEmpty())
                    .forEach(t -> problems.add("type '" + t + "' is used by the export but not defined in the DAR"));
            problems.addAll(model.compatibilityWarnings());
        }
        new D2RuleResolver(config, RuntimeContext.defaults(), model).resolve(Map.of(), D2RuleResolver.Options.asIs());

        System.out.printf("%s: application %s, D2 %s, %d document sets, %d matrix modules%n",
                dir, config.application(), config.d2Version(), config.docSetsByOrder().size(), config.switches().size());
        if (model != null)
            System.out.printf("DAR: %d types, %d TBOs, Composer %s, schema %s%n",
                    model.types().size(), model.tbos().size(), model.composerVersions(), model.schemaVersions());
        problems.forEach(p -> System.out.println("  ! " + p));
    }
}
