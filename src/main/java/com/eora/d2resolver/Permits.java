package com.eora.d2resolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Documentum r_accessor_permit / r_accessor_xpermit decoding. */
final class Permits {

    private static final String[] BASIC = {"", "NONE", "BROWSE", "READ", "RELATE", "VERSION", "WRITE", "DELETE"};

    /** EXECUTE_PROC and CHANGE_LOCATION are granted by default: their bit set means "revoked". The others are grants. */
    private static final Map<Integer, String> EXTENDED = new LinkedHashMap<>();

    static {
        EXTENDED.put(1, "no EXECUTE_PROC");
        EXTENDED.put(2, "no CHANGE_LOCATION");
        EXTENDED.put(65536, "CHANGE_STATE");
        EXTENDED.put(131072, "CHANGE_PERMIT");
        EXTENDED.put(262144, "CHANGE_OWNER");
        EXTENDED.put(524288, "DELETE_OBJECT");
        EXTENDED.put(1048576, "CHANGE_FOLDER_LINKS");
    }

    private Permits() {}

    static String basic(String value) {
        if (value.isEmpty()) return "";
        int p = Integer.parseInt(value);
        return p > 0 && p < BASIC.length ? BASIC[p] : value;
    }

    static String extended(String value) {
        if (value.isEmpty()) return "";
        int x = Integer.parseInt(value);
        List<String> names = new ArrayList<>();
        EXTENDED.forEach((bit, name) -> { if ((x & bit) != 0) names.add(name); });
        return String.join("+", names);
    }
}
