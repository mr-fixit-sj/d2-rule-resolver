package com.eora.d2resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @Test
    void batchCsvAppendsResolvedColumns(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("in.csv"), out = tmp.resolve("out.csv");
        Files.writeString(in, """
                id,acme_process,acme_doc_type,acme_doc_date,acme_customer,a_status
                1,Contracts,Lease Contract,2024-03-15,"C,001",Active
                2,Contracts,Amendment,2024-04-01,C002,Final
                """, StandardCharsets.UTF_8);

        Main.main(new String[]{"--config", "src/test/resources/acme-export", "--types", "src/test/resources/acme-types.properties",
                "--today", "2026-10-14", "--csv", in.toString(), "--out", out.toString(), "r_object_type=acme_document"});

        List<List<String>> rows = Csv.read(new BufferedReader(new StringReader(Files.readString(out, StandardCharsets.UTF_8))));
        assertEquals(3, rows.size());
        List<String> header = rows.get(0);
        assertEquals(6 + ReportWriter.CSV_COLUMNS.size(), header.size());

        List<String> lease = rows.get(1);
        assertEquals("C,001_LC_20240315", lease.get(header.indexOf("d2_object_name")), "quoted CSV value survives the round trip");
        assertEquals("/ACME/Contrats/Lease Contract/2024/03", lease.get(header.indexOf("d2_folder")));
        assertEquals("acl_contract_active", lease.get(header.indexOf("d2_acl_config")));

        List<String> finalAmendment = rows.get(2);
        assertEquals("acl_contract_final", finalAmendment.get(header.indexOf("d2_acl_config")));
        assertEquals("rps_10_years", finalAmendment.get(header.indexOf("d2_retention")));
        assertTrue(finalAmendment.get(header.indexOf("d2_acl")).contains("grp_contract_read:READ"));
    }
}
