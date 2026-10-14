package com.eora.d2resolver;

import java.time.LocalDate;
import java.util.Set;

/**
 * Facts about the import run that are not stored on the document.
 *
 * @param user   importing user, null when unknown
 * @param groups groups of the importing user, null when unknown (role based document sets become undecidable)
 * @param today  value for date(now), date(today) and $TODAY
 * @param locale dictionary locale used when a template has no explicit key, e.g. $value(attr.dic_x)
 */
public record RuntimeContext(String user, Set<String> groups, LocalDate today, String locale) {

    public static RuntimeContext defaults() {
        return new RuntimeContext(null, null, LocalDate.now(), "en");
    }

    public RuntimeContext withToday(LocalDate date) {
        return new RuntimeContext(user, groups, date, locale);
    }
}
