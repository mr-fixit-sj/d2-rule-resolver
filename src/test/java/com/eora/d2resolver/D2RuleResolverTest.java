package com.eora.d2resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eora.d2resolver.D2RuleResolver.AclEntry;
import com.eora.d2resolver.D2RuleResolver.FieldCheck;
import com.eora.d2resolver.D2RuleResolver.Folder;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the resolver against the synthetic export in src/test/resources/acme-export. */
class D2RuleResolverTest {

    static final Path EXPORT = Path.of("src/test/resources/acme-export");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    static D2Config config;

    @BeforeAll
    static void load() throws IOException {
        config = D2Config.open(EXPORT);
        config.loadTypeHierarchy(Path.of("src/test/resources/acme-types.properties"));
    }

    static Result resolve(Map<String, String> attrs) {
        return resolve(attrs, Options.asIs(), new RuntimeContext(null, null, TODAY, "en"));
    }

    static Result resolve(Map<String, String> attrs, Options options, RuntimeContext ctx) {
        return new D2RuleResolver(config, ctx).resolve(attrs, options);
    }

    static Map<String, String> contract(String docType, String status) {
        Map<String, String> m = new HashMap<>(Map.of(
                "r_object_type", "acme_document",
                "acme_process", "Contracts",
                "acme_doc_type", docType,
                "acme_doc_date", "2024-03-15",
                "acme_customer", "C001"));
        if (status != null) m.put("a_status", status);
        return m;
    }

    static AclEntry ace(List<AclEntry> acl, String accessor) {
        return acl.stream().filter(a -> a.accessor().equals(accessor)).findFirst().orElseThrow(() -> new AssertionError("no ACE for " + accessor + " in " + acl));
    }

    @Test
    void readsManifest() {
        assertEquals("ACME", config.application());
        assertEquals("22.1.0000", config.d2Version());
    }

    @Test
    void activeLeaseContract() {
        Result r = resolve(contract("Lease Contract", "Active"));

        assertEquals("C001_LC_20240315", r.naming().value());
        assertEquals("/ACME/Contrats/Lease Contract/2024/03", r.primaryFolder());
        assertEquals("acl_contract_active", r.config(D2RuleResolver.ACL));
        assertEquals("lc_document", r.config(D2RuleResolver.LIFECYCLE));
        assertEquals("", r.config(D2RuleResolver.RETENTION));

        assertTrue(r.matchedSets().containsAll(List.of("Doc - Contracts - All", "Doc - Contracts - Active", "Doc")));
        assertFalse(r.matchedSets().contains("Doc - Other Application"), "sets of other applications are ignored");
        assertTrue(r.undecidedSets().contains("Doc - Customer Hold"), "sub-select on a repository table is undecidable");
        assertEquals("Doc - Customer Hold", r.modules().get(D2RuleResolver.LIFECYCLE).undecidedBefore().get(0).path().get(0));
    }

    @Test
    void documentAclResolvesDynamicAndConditionalAccessors() {
        List<AclEntry> acl = resolve(contract("Lease Contract", "Active")).documentAcl();

        assertEquals("NONE", ace(acl, "dm_world").permit());
        assertEquals("WRITE", ace(acl, "dm_owner").permit());
        assertEquals("READ", ace(acl, "grp_lease_read").permit(), "group resolved through taxonomy tax_doctype_read_roles");
        assertEquals("READ", ace(acl, "grp_lease_audit").permit());
        assertEquals("READ", ace(acl, "grp_contract_write").permit(), "conditional row only applies to amendments");
        assertEquals("CHANGE_STATE+CHANGE_PERMIT+CHANGE_OWNER+DELETE_OBJECT+CHANGE_FOLDER_LINKS", ace(acl, "admingroup").extendedPermits());
    }

    @Test
    void amendmentGetsConditionalRightsAndCollapsedName() {
        Result r = resolve(contract("Amendment", "Active"));

        assertEquals("C001_20240315", r.naming().value(), "empty abbreviation collapsed by the naming regexps");
        AclEntry write = ace(r.documentAcl(), "grp_contract_write");
        assertEquals("WRITE", write.permit());
        assertEquals("CHANGE_FOLDER_LINKS", write.extendedPermits());
        assertTrue(write.note().contains("acme_doc_type=Amendment"));
        assertTrue(r.documentAcl().stream().anyMatch(a -> a.accessor().equals("(none)")), "no group mapped for amendments");
    }

    @Test
    void folderAclsUseFolderDefaultsAndApplyPerLevel() {
        List<Folder> chain = resolve(contract("Amendment", "Active")).links().get(0);

        assertEquals(5, chain.size());
        assertEquals("dm_cabinet", chain.get(0).type());
        assertNull(chain.get(1).acl(), "no ACL configured on the process level");
        Folder typeFolder = chain.get(2);
        assertEquals("/ACME/Contrats/Amendment", typeFolder.path());
        assertEquals("acl_contract_folder", typeFolder.aclConfig());
        assertEquals("def_type_folder", typeFolder.defaultValueConfig());
        assertEquals("NONE", ace(typeFolder.acl(), "grp_restricted").permit(), "condition matches the folder subject default");

        Folder leaseFolder = resolve(contract("Lease Contract", "Active")).links().get(0).get(2);
        assertEquals("READ", ace(leaseFolder.acl(), "grp_restricted").permit());
    }

    @Test
    void finalStatusSwitchesSecurityAndRetention() {
        Result r = resolve(contract("Lease Contract", "Final"));
        assertEquals("acl_contract_final", r.config(D2RuleResolver.ACL));
        assertEquals("rps_10_years", r.config(D2RuleResolver.RETENTION));
    }

    @Test
    void propertyPageChecks() {
        Map<String, String> doc = contract("Lease Contract", "Active");
        doc.remove("acme_customer");
        List<FieldCheck> checks = resolve(doc).checks();
        assertEquals("MISSING", check(checks, "acme_customer").status());
        assertTrue(checks.stream().noneMatch(c -> c.attribute().equals("acme_contract_nr")), "&& of exclusive values is never required");
        assertEquals("MAYBE-REQUIRED", check(checks, "acme_notes").status(), "unsupported condition is reported, not guessed");

        assertEquals("INVALID", check(resolve(contract("Invoice", "Active")).checks(), "acme_doc_type").status(), "not under Contracts in the taxonomy");
        assertTrue(check(resolve(contract("Old Type", "Active")).checks(), "acme_doc_type").detail().contains("not an enabled value"));
    }

    static FieldCheck check(List<FieldCheck> checks, String attribute) {
        return checks.stream().filter(c -> c.attribute().equals(attribute)).findFirst().orElseThrow(() -> new AssertionError("no check for " + attribute + " in " + checks));
    }

    @Test
    void asNewAppliesLifecycleEntryState() {
        Map<String, String> doc = contract("Lease Contract", null);
        Result r = resolve(doc, new Options(true, null, "import"), new RuntimeContext("jdoe", null, TODAY, "en"));

        assertEquals("Active", r.lifecycle().state());
        assertEquals("Active", r.attributes().get("a_status"));
        assertEquals("jdoe", r.attributes().get("acme_owner"));
        assertEquals("acl_contract_active", r.config(D2RuleResolver.ACL));
    }

    @Test
    void defaultValuesFillEmptyAttributes() {
        Map<String, String> doc = Map.of("r_object_type", "acme_document", "acme_doc_type", "Lease Contract", "a_status", "Active");
        Result r = resolve(doc, new Options(false, "def_contract", "import"), new RuntimeContext(null, null, TODAY, "en"));
        assertEquals("/ACME/Contrats/Lease Contract/2026/10", r.primaryFolder());
    }

    @Test
    void roleSetsDependOnGroups() {
        Map<String, String> doc = contract("Lease Contract", "Active");
        assertEquals("prp_admin", resolve(doc, Options.asIs(), new RuntimeContext("a", Set.of("grp_contract_admin"), TODAY, "en")).config(D2RuleResolver.PROPERTY));
        Result notAdmin = resolve(doc, Options.asIs(), new RuntimeContext("b", Set.of(), TODAY, "en"));
        assertEquals("prp_contract", notAdmin.config(D2RuleResolver.PROPERTY));
        assertTrue(notAdmin.modules().get(D2RuleResolver.PROPERTY).undecidedBefore().isEmpty());
    }

    @Test
    void unknownTypeMakesTypedSetsUndecidable() {
        Map<String, String> doc = contract("Lease Contract", "Active");
        doc.put("r_object_type", "custom_type");
        Result r = resolve(doc);
        assertTrue(r.undecidedSets().contains("Doc - Contracts - All"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("custom_type")));
    }

    @Test
    void whereClauseFeatures() {
        Map<String, String> expired = contract("Lease Contract", "Active");
        expired.put("acme_end_date", "2020-01-01");
        assertTrue(resolve(expired).matchedSets().contains("Doc - Expired"), "date(now) and dictionary alias sub-select");

        Map<String, String> permanent = contract("Amendment", "Active");
        permanent.put("acme_end_date", "2020-01-01");
        assertFalse(resolve(permanent).matchedSets().contains("Doc - Expired"));

        Map<String, String> future = contract("Lease Contract", "Active");
        future.put("acme_end_date", "2030-01-01");
        assertFalse(resolve(future).matchedSets().contains("Doc - Expired"));

        // not a contract, so no autonaming rewrites object_name before the sets are evaluated
        Map<String, String> draft = new HashMap<>(Map.of("r_object_type", "acme_document", "acme_process", "Invoices",
                "acme_doc_type", "Invoice", "object_name", "DRAFT-42"));
        assertTrue(resolve(draft).matchedSets().contains("Doc - Drafts"), "LIKE and NOT IN");
        draft.put("acme_doc_type", "Amendment");
        assertFalse(resolve(draft).matchedSets().contains("Doc - Drafts"));

        Map<String, String> renamed = contract("Lease Contract", "Active");
        renamed.put("object_name", "DRAFT-42");
        assertFalse(resolve(renamed).matchedSets().contains("Doc - Drafts"), "autonaming runs before the sets are re-evaluated");
    }

    @Test
    void opensZippedExport(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("export.zip");
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(os);
             Stream<Path> files = Files.walk(EXPORT)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry("export/" + EXPORT.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, out);
                out.closeEntry();
            }
        }
        D2Config zipped = D2Config.open(zip);
        assertEquals("ACME", zipped.application());
        assertEquals(config.switches().keySet(), zipped.switches().keySet());
    }
}
