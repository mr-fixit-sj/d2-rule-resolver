package com.eora.d2resolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Minimal RFC 4180 CSV (quoted fields may contain separators, quotes and line breaks). */
final class Csv {

    private Csv() {}

    static List<List<String>> read(BufferedReader in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int c;
        while ((c = in.read()) != -1) {
            char ch = (char) c;
            if (quoted) {
                if (ch == '"') {
                    in.mark(1);
                    if (in.read() == '"') cell.append('"');
                    else { quoted = false; in.reset(); }
                } else cell.append(ch);
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (ch != '\r' && ch != '﻿') {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    static String line(List<String> values) {
        return values.stream().map(Csv::escape).collect(Collectors.joining(",")) + "\n";
    }

    static String escape(String s) {
        return s.matches("(?s).*[\",\r\n].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
