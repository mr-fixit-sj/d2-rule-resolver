package com.eora.d2resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eora.d2resolver.D2RuleResolver.FieldCheck;
import com.eora.d2resolver.D2RuleResolver.Options;
import com.eora.d2resolver.D2RuleResolver.Result;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Composer artifacts from the synthetic project in src/test/resources/acme-dar. */
class DarModelTest {

    static final Path PROJECT = Path.of("src/test/resources/acme-dar");
    static final RuntimeContext CTX = new RuntimeContext(null, null, LocalDate.of(2026, 10, 14), "en");

    static DarModel load(Path p) throws IOException {
        return DarModel.load(List.of(p));
    }

    @Test
    void readsTypesFromComposerProject() throws IOException {
        DarModel model = load(PROJECT);

        assertEquals(Set.of("acme_document", "acme_contract", "acme_folder"), model.types().keySet());
        assertEquals("dm_document", model.type("acme_document").orElseThrow().superType());
        assertEquals("acme_document", model.type("ACME_CONTRACT").orElseThrow().superType(), "super type given by artifactURI");
        assertTrue(model.type("acme_folder").orElseThrow().attributes().containsKey("acme_area"));
        assertFalse(model.type("acme_folder").orElseThrow().attributes().containsKey("stale_attr"), "source artifact wins over bin/ copy");
        assertEquals(Set.of("acme_contract"), model.tbos().keySet(), "only TBO modules count, SBOs are ignored");
    }

    @Test
    void attributesAreInheritedAlongTheHierarchy() throws IOException {
        DarModel model = load(PROJECT);

        assertEquals(List.of("acme_contract", "acme_document"), model.hierarchy("acme_contract").stream().map(DarModel.TypeDef::name).toList());
        DarModel.Attribute priority = model.attribute("acme_contract", "acme_priority").orElseThrow();
        assertEquals(DarModel.DataType.INTEGER, priority.type());
        assertEquals("5", priority.defaultValue());
        assertEquals("migration", model.attribute("acme_document", "acme_source").orElseThrow().defaultValue(), "quotes stripped");
        assertTrue(model.attribute("acme_document", "acme_tags").orElseThrow().repeating());
        assertEquals(32, model.attribute("acme_document", "acme_tags").orElseThrow().length());
        assertTrue(model.attribute("acme_contract", "acme_audit_code").orElseThrow().required());
        assertTrue(model.attribute("acme_document", "acme_audit_code").isEmpty(), "sub type attributes are not visible on the super type");
    }

    @Test
    void recordsComposerVersionAndSchema() throws IOException {
        DarModel model = load(PROJECT);
        assertEquals(Set.of(DarModel.VERIFIED_COMPOSER), model.composerVersions());
        assertEquals(Set.of(DarModel.VERIFIED_SCHEMA), model.schemaVersions());
        assertTrue(model.compatibilityWarnings().isEmpty());
    }

    @Test
    void warnsAboutOtherComposerReleases(@TempDir Path tmp) throws IOException {
        copyProject(tmp);
        Files.writeString(tmp.resolve("version.properties"), "composer.version=23.4.0000.0100\n");
        assertTrue(load(tmp).compatibilityWarnings().stream().anyMatch(w -> w.contains("23.4.0000.0100")));

        Files.delete(tmp.resolve("version.properties"));
        assertTrue(load(tmp).compatibilityWarnings().stream().anyMatch(w -> w.contains("no composer.version")));
    }

    @Test
    void readsBuiltDarFile(@TempDir Path tmp) throws IOException {
        // a built .dar holds the artifacts as "\bin\Artifacts\Types\x.type.artifact" entries plus version.properties
        Path dar = tmp.resolve("acme.dar");
        try (OutputStream os = Files.newOutputStream(dar); ZipOutputStream zip = new ZipOutputStream(os);
             Stream<Path> files = Files.walk(PROJECT)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String rel = PROJECT.relativize(p).toString().replace('/', '\\');
                if (rel.startsWith("bin") || rel.startsWith("locales")) continue;
                zip.putNextEntry(new ZipEntry(rel.equals("version.properties") ? rel : "\\bin\\" + rel + ".artifact"));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        DarModel model = load(dar);
        assertEquals(load(PROJECT).types().keySet(), model.types().keySet());
        assertEquals(Set.of(DarModel.VERIFIED_COMPOSER), model.composerVersions());
        assertEquals(Set.of("acme_contract"), model.tbos().keySet());
    }

    @Test
    void typeHierarchyFromDarDrivesDocumentSets() throws IOException {
        D2Config config = D2Config.open(D2RuleResolverTest.EXPORT); // no --types file: the DAR provides the hierarchy
        D2RuleResolver resolver = new D2RuleResolver(config, CTX, load(PROJECT));

        Map<String, String> doc = D2RuleResolverTest.contract("Lease Contract", "Active");
        doc.put("r_object_type", "acme_contract");
        doc.put("acme_audit_code", "A1");
        Result r = resolver.resolve(doc, Options.asIs());

        assertTrue(r.matchedSets().contains("Doc - Contracts - All"), "acme_contract is a sub type of acme_document");
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("acme_contract has a TBO")));
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("folder type")), "acme_folder is defined in the DAR");
        assertTrue(r.checks().stream().noneMatch(c -> c.status().startsWith("UNKNOWN") || c.status().startsWith("TOO")), r.checks().toString());
    }

    @Test
    void validatesValuesAgainstTheModel() throws IOException {
        D2RuleResolver resolver = new D2RuleResolver(D2Config.open(D2RuleResolverTest.EXPORT), CTX, load(PROJECT));
        Map<String, String> doc = new HashMap<>(D2RuleResolverTest.contract("Lease Contract", "Active"));
        doc.put("r_object_type", "acme_contract");
        doc.put("acme_customer", "CUSTOMER-123");
        doc.put("acme_priority", "high");
        doc.put("acme_amount", "12.50");
        doc.put("acme_archived", "maybe");
        doc.put("acme_lease_end", "31/12/2030");
        doc.put("subject", "é".repeat(100));
        doc.put("bogus_attr", "x");
        List<FieldCheck> checks = resolver.resolve(doc, Options.asIs()).checks();

        assertEquals("TOO-LONG", check(checks, "acme_customer"));
        assertEquals("BAD-VALUE", check(checks, "acme_priority"));
        assertEquals("BAD-VALUE", check(checks, "acme_archived"));
        assertEquals("BAD-VALUE", check(checks, "acme_lease_end"));
        assertEquals("TOO-LONG-BYTES", check(checks, "subject"), "100 characters but 200 UTF-8 bytes > 192");
        assertEquals("UNKNOWN-ATTR", check(checks, "bogus_attr"));
        assertEquals("MISSING", check(checks, "acme_audit_code"));
        assertTrue(checks.stream().noneMatch(c -> c.attribute().equals("acme_amount") || c.attribute().equals("object_name")
                || c.attribute().equals("a_status")), checks.toString());
    }

    @Test
    void asNewAppliesTypeDefaults() throws IOException {
        D2RuleResolver resolver = new D2RuleResolver(D2Config.open(D2RuleResolverTest.EXPORT), CTX, load(PROJECT));
        Map<String, String> doc = D2RuleResolverTest.contract("Lease Contract", null);
        Result r = resolver.resolve(doc, new Options(true, null, "import"));
        assertEquals("5", r.attributes().get("acme_priority"));
        assertEquals("migration", r.attributes().get("acme_source"));
        assertEquals("Active", r.attributes().get("a_status"), "lifecycle entry state still applies after type defaults");
    }

    static String check(List<FieldCheck> checks, String attribute) {
        return checks.stream().filter(c -> c.attribute().equals(attribute)).map(FieldCheck::status).findFirst()
                .orElseThrow(() -> new AssertionError("no check for " + attribute + " in " + checks));
    }

    static void copyProject(Path target) throws IOException {
        try (Stream<Path> files = Files.walk(PROJECT)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                Path out = target.resolve(PROJECT.relativize(p).toString());
                Files.createDirectories(out.getParent());
                Files.copy(p, out);
            }
        }
    }
}
