package com.eora.d2resolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Replays the D2 rule chain for one document, using only a D2 configuration export:
 *
 * <ol>
 *   <li>optional default values (d2_default_value_config) and lifecycle entry-state properties (--as-new)</li>
 *   <li>document set membership (d2_documentset where-clauses)</li>
 *   <li>context matrix (d2_documentset_switch): per module, the first matching row wins</li>
 *   <li>autonaming, then autolink (folders, folder types, folder ACLs) and security (document ACL),
 *       which is the order of the D2 lifecycle entry actions</li>
 *   <li>property page validation in import mode (required fields, dictionary/taxonomy values)</li>
 * </ol>
 */
public final class D2RuleResolver {

    public static final String LINK = "d2_link_config";
    public static final String ACL = "d2_acl_config";
    public static final String NAMING = "d2_naming_config";
    public static final String PROPERTY = "d2_property_config";
    public static final String LIFECYCLE = "d2_lifecycle_config";
    public static final String RETENTION = "d2rps_retention_config";

    private static final Pattern VALUE = Pattern.compile("\\$value\\(([^)]*)\\)");
    private static final Pattern TAXONOMY_ROLE_LOOKUP = Pattern.compile(
            "description like '(tax_[A-Za-z0-9_]+)%'.*object_name = '\\$value\\((\\w+)\\)'");
    private static final Pattern JS_GET_VALUE = Pattern.compile(
            "getValue\\((\\w+)\\)\\.(equals|notEquals|equalsIgnoreCase)\\('([^']*)'\\)");

    // ------------------------------------------------------------------ result model

    /**
     * @param asNew              simulate a new D2 import: apply the lifecycle entry-state properties before resolving
     * @param defaultValueConfig d2_default_value_config to apply to empty attributes, or null
     * @param propertyMode       property page mode whose required flags are checked: import, create or edit
     */
    public record Options(boolean asNew, String defaultValueConfig, String propertyMode) {
        public static Options asIs() { return new Options(false, null, "import"); }
    }

    /** Context matrix outcome for one module; undecidedBefore lists earlier rows that may win at runtime. */
    public record Resolution(String config, D2Config.SwitchEntry row, List<D2Config.SwitchEntry> undecidedBefore) {
        public boolean found() { return config != null; }
    }

    public record AclEntry(String accessor, String permit, String extendedPermits, String note) {}

    public record Folder(String path, String type, String aclConfig, String defaultValueConfig, List<AclEntry> acl) {}

    public record LifecycleEntry(String lifecycle, String state, Map<String, String> sets, List<String> actions) {}

    public record FieldCheck(String attribute, String status, String detail) {}

    public record Naming(String attribute, String value) {}

    public record Result(Map<String, String> attributes,
                         List<String> matchedSets,
                         List<String> undecidedSets,
                         Map<String, Resolution> modules,
                         Naming naming,
                         List<List<Folder>> links,
                         List<AclEntry> documentAcl,
                         LifecycleEntry lifecycle,
                         List<FieldCheck> checks,
                         List<String> warnings) {

        public String config(String module) {
            Resolution r = modules.get(module);
            return r == null || r.config() == null ? "" : r.config();
        }

        public String primaryFolder() {
            return links.isEmpty() ? "" : links.get(0).get(links.get(0).size() - 1).path();
        }
    }

    // ------------------------------------------------------------------ resolver

    private final D2Config config;
    private final RuntimeContext ctx;
    private final DarModel model;

    public D2RuleResolver(D2Config config, RuntimeContext ctx) {
        this(config, ctx, null);
    }

    /** @param model object model from Composer artifacts, or null when not available */
    public D2RuleResolver(D2Config config, RuntimeContext ctx, DarModel model) {
        this.config = config;
        this.ctx = ctx;
        this.model = model;
        if (model != null) model.applyTo(config);
    }

    public Result resolve(Map<String, String> input, Options options) {
        return new Run(input).execute(options);
    }

    /** State for a single document. */
    private final class Run {
        final Map<String, String> attrs = new LinkedHashMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final Map<String, Tri> setCache = new HashMap<>();

        Run(Map<String, String> input) {
            input.forEach((k, v) -> attrs.put(k.toLowerCase(), v));
        }

        Result execute(Options options) {
            if (options.defaultValueConfig() != null)
                applyDefaults(options.defaultValueConfig(), Map.copyOf(attrs), attrs, false);

            LifecycleEntry lifecycle = null;
            if (options.asNew()) {
                applyTypeDefaults();
                Resolution lc = matrix(LIFECYCLE);
                if (lc.found()) {
                    lifecycle = lifecycleEntry(lc.config()).orElse(null);
                    if (lifecycle != null) changed(lifecycle.sets());
                }
            }

            Resolution namingRow = matrix(NAMING);
            Naming naming = namingRow.found() ? autoname(namingRow.config()).orElse(null) : null;
            if (naming != null) changed(Map.of(naming.attribute().toLowerCase(), naming.value()));

            Map<String, Resolution> modules = new LinkedHashMap<>();
            for (String module : config.switches().keySet()) {
                Resolution r = module.equals(NAMING) ? namingRow : matrix(module);
                if (r.found() || !r.undecidedBefore().isEmpty()) modules.put(module, r);
            }
            if (!options.asNew() && modules.containsKey(LIFECYCLE) && modules.get(LIFECYCLE).found())
                lifecycle = lifecycleEntry(modules.get(LIFECYCLE).config()).orElse(null);

            List<String> matched = new ArrayList<>(), undecided = new ArrayList<>();
            for (D2Config.DocSet d : config.docSetsByOrder()) {
                switch (inSet(d.name())) {
                    case T -> matched.add(d.name());
                    case U -> undecided.add(d.name());
                    case F -> { }
                }
            }

            List<List<Folder>> links = found(modules, LINK) ? autolink(modules.get(LINK).config()) : List.of();
            List<AclEntry> acl = found(modules, ACL) ? acl(modules.get(ACL).config(), attrs) : List.of();
            List<FieldCheck> checks = new ArrayList<>();
            if (found(modules, PROPERTY)) checks.addAll(propertyChecks(modules.get(PROPERTY).config(), options.propertyMode()));
            checks.addAll(modelChecks());
            if (model != null) links.forEach(chain -> chain.forEach(f -> checkFolderType(f.type())));

            return new Result(Map.copyOf(attrs), matched, undecided, modules, naming, links, acl, lifecycle, checks,
                    List.copyOf(warnings));
        }

        boolean found(Map<String, Resolution> modules, String module) {
            return modules.containsKey(module) && modules.get(module).found();
        }

        void changed(Map<String, String> updates) {
            attrs.putAll(updates);
            setCache.clear();
        }

        // -------------------------------------------------------------- document sets and matrix

        Tri inSet(String name) {
            String key = D2Config.key(name);
            Tri cached = setCache.get(key);
            if (cached != null) return cached;
            Tri result = config.docSet(name).map(this::evaluateSet).orElseGet(() -> {
                warnings.add("document set used in the matrix is not in the export: " + name);
                return Tri.F;
            });
            setCache.put(key, result);
            return result;
        }

        Tri evaluateSet(D2Config.DocSet d) {
            if (!d.applications().isEmpty() && !config.application().isEmpty()
                    && d.applications().stream().noneMatch(config.application()::equalsIgnoreCase))
                return Tri.F;
            Tri r = d.from().isEmpty() ? Tri.T : typeMatch(d.from());
            if (!d.groups().isEmpty())
                r = r.and(ctx.groups() == null ? Tri.U : Tri.of(d.groups().stream().anyMatch(ctx.groups()::contains)));
            if (r != Tri.F && !d.where().isBlank())
                r = r.and(new DqlEvaluator(d.where(), attrs, config, ctx, warnings::add).evaluate());
            return r;
        }

        /** dql_from check; an exact type match needs no hierarchy, sub types need the client's type hierarchy. */
        Tri typeMatch(List<String> from) {
            String type = attrs.getOrDefault("r_object_type", "");
            if (type.isEmpty()) {
                warnings.add("r_object_type not given: dql_from type restrictions are ignored");
                return Tri.T;
            }
            if (from.stream().anyMatch(f -> config.isTypeOf(type, f))) return Tri.T;
            if (!config.knowsType(type)) {
                warnings.add("type hierarchy of '" + type + "' unknown (use --types or --type-parent): sets on "
                        + String.join(",", from) + " are undecidable");
                return Tri.U;
            }
            return Tri.F;
        }

        /** D2 walks the context matrix top-down and takes the first row whose document sets all match. */
        Resolution matrix(String module) {
            List<D2Config.SwitchEntry> undecided = new ArrayList<>();
            for (D2Config.SwitchEntry row : config.switches().getOrDefault(module, List.of())) {
                Tri t = Tri.T;
                for (String set : row.path()) t = t.and(inSet(set));
                if (t == Tri.T) return new Resolution(row.config(), row, undecided);
                if (t == Tri.U) undecided.add(row);
            }
            return new Resolution(null, null, undecided);
        }

        // -------------------------------------------------------------- $value() templates

        String expand(String template, Map<String, String> values) {
            if (template == null) return "";
            Matcher m = VALUE.matcher(template);
            StringBuilder sb = new StringBuilder();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(valueOf(m.group(1), values)));
            m.appendTail(sb);
            String out = sb.toString().replace("$TODAY", ctx.today().toString());
            if (ctx.user() != null) out = out.replace("$USER", ctx.user());
            if (out.contains("$dqlvalue(")) warnings.add("$dqlvalue left unresolved (needs the repository): " + template);
            return out;
        }

        /** attr | attr.dictionary.localeOrAlias | attr.SimpleDateFormatPattern */
        String valueOf(String expression, Map<String, String> values) {
            String[] parts = expression.split("\\.", 3);
            String raw = values.getOrDefault(parts[0].trim().toLowerCase(), "");
            if (parts.length == 1 || raw.isEmpty()) return raw;
            if (config.hasDictionary(parts[1])) {
                D2Config.DictValue dv = config.dictionary(parts[1]).get(raw);
                if (dv == null) {
                    warnings.add("'" + raw + "' (" + parts[0] + ") is not a value of dictionary " + parts[1]);
                    return "";
                }
                String key = parts.length > 2 ? parts[2] : ctx.locale();
                return dv.lookup(key).orElseGet(() -> {
                    warnings.add("dictionary " + parts[1] + " has no locale or alias '" + key + "'");
                    return "";
                });
            }
            String pattern = expression.substring(parts[0].length() + 1);
            LocalDateTime date = Dates.parseDateTime(raw);
            if (date == null) {
                warnings.add("cannot format '" + raw + "' (" + parts[0] + ") with date pattern " + pattern);
                return raw;
            }
            try {
                return Dates.format(date, pattern);
            } catch (IllegalArgumentException e) {
                warnings.add("invalid date pattern " + pattern);
                return raw;
            }
        }

        // -------------------------------------------------------------- autonaming

        Optional<Naming> autoname(String name) {
            Optional<D2Config.FlatConfig> cfg = config.flat(NAMING, name);
            if (cfg.isEmpty()) { warnings.add("naming config not in export: " + name); return Optional.empty(); }
            List<String> templates = cfg.get().list("templates_names");
            if (templates.size() > 1) warnings.add(name + " has " + templates.size() + " templates; only the first is evaluated");
            String value = expand(templates.isEmpty() ? "" : templates.get(0), attrs);
            if (value.toUpperCase().contains("$COUNTER") || value.contains("$NUMBER"))
                warnings.add(name + " uses a counter; its value depends on repository state");
            List<String> patterns = cfg.get().list("regexp_pattern"), replacements = cfg.get().list("regexp_replace");
            for (int i = 0; i < patterns.size(); i++)
                value = value.replaceAll(patterns.get(i), i < replacements.size() ? replacements.get(i) : "");
            return Optional.of(new Naming(cfg.get().first("attr_name"), value));
        }

        // -------------------------------------------------------------- autolink

        List<List<Folder>> autolink(String name) {
            Optional<D2Config.FlatConfig> found = config.flat(LINK, name);
            if (found.isEmpty()) { warnings.add("autolink config not in export: " + name); return List.of(); }
            D2Config.FlatConfig cfg = found.get();
            String undefined = cfg.first("undefined_label");
            List<String> paths = cfg.list("path"), types = cfg.list("type"), acls = cfg.list("acl"), defaults = cfg.list("default_value");
            List<List<Folder>> links = new ArrayList<>();
            for (int i = 0; i < paths.size(); i++) {
                // path, type, acl and default_value are parallel "/"-separated lists, one entry per folder level
                String[] segments = paths.get(i).split("/", -1);
                String[] levelTypes = at(types, i).split("/", -1);
                String[] levelAcls = at(acls, i).split("/", -1);
                String[] levelDefaults = at(defaults, i).split("/", -1);
                List<Folder> chain = new ArrayList<>();
                StringBuilder path = new StringBuilder();
                for (int s = 1; s < segments.length; s++) {
                    String folderName = expand(segments[s], attrs);
                    if (folderName.isEmpty()) folderName = undefined;
                    if (folderName.contains("/")) warnings.add("autolink value contains '/' and creates extra levels: " + folderName);
                    path.append('/').append(folderName);
                    String type = at(levelTypes, s), aclName = at(levelAcls, s), defaultName = at(levelDefaults, s);
                    List<AclEntry> folderAcl = null;
                    if (!aclName.isEmpty()) {
                        // folder ACL conditions see the folder: its defaults are computed from the document being linked
                        Map<String, String> folderAttrs = new HashMap<>();
                        if (!defaultName.isEmpty()) applyDefaults(defaultName, attrs, folderAttrs, true);
                        folderAttrs.put("object_name", folderName);
                        folderAttrs.put("r_object_type", type);
                        folderAcl = acl(aclName, folderAttrs);
                    }
                    chain.add(new Folder(path.toString(), type, aclName, defaultName, folderAcl));
                }
                links.add(chain);
            }
            return links;
        }

        // -------------------------------------------------------------- security

        /** First applicable row per accessor wins; conditional rows are listed before their fallback in the configs. */
        List<AclEntry> acl(String name, Map<String, String> target) {
            Optional<D2Config.FlatConfig> found = config.flat(ACL, name);
            if (found.isEmpty()) { warnings.add("ACL config not in export: " + name); return List.of(); }
            D2Config.FlatConfig cfg = found.get();
            List<String> names = cfg.list("accessor_name"), permits = cfg.list("accessor_permit"), xpermits = cfg.list("accessor_xpermit");
            List<String> conditionAttrs = cfg.list("condition_attr_name"), conditionValues = cfg.list("condition_attr_value");
            List<AclEntry> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < names.size(); i++) {
                String permit = Permits.basic(at(permits, i)), extended = Permits.extended(at(xpermits, i));
                String condition = at(conditionAttrs, i), note = "";
                if (!condition.isEmpty()) {
                    String expected = expand(at(conditionValues, i), target);
                    if (!target.getOrDefault(condition.toLowerCase(), "").equals(expected)) continue;
                    note = "because " + condition + "=" + expected;
                }
                List<String> accessors = new ArrayList<>();
                String accessor = names.get(i);
                if (accessor.startsWith("%")) {
                    // dynamic accessor: "%$dqlvalue(select ... dm_relation ... tax_xxx% ... '$value(attr)')"
                    Matcher tm = TAXONOMY_ROLE_LOOKUP.matcher(accessor);
                    Optional<D2Config.Taxonomy> taxonomy = tm.find() ? config.taxonomy(tm.group(1)) : Optional.empty();
                    if (taxonomy.isEmpty()) {
                        out.add(new AclEntry(accessor, permit, extended, "UNRESOLVED dynamic accessor"));
                        continue;
                    }
                    String lookup = target.getOrDefault(tm.group(2).toLowerCase(), "");
                    for (String[] p : taxonomy.get().paths())
                        if (p.length > 1 && p[p.length - 1].equals(lookup)) accessors.add(p[0]);
                    note = (note + " via taxonomy " + tm.group(1) + "[" + lookup + "]").trim();
                    if (accessors.isEmpty()) {
                        out.add(new AclEntry("(none)", permit, extended, note + ": no group mapped"));
                        continue;
                    }
                } else {
                    accessors.add(accessor);
                }
                for (String a : accessors)
                    if (seen.add(a)) out.add(new AclEntry(a, permit, extended, note));
            }
            return out;
        }

        // -------------------------------------------------------------- defaults and lifecycle

        void applyDefaults(String name, Map<String, String> source, Map<String, String> target, boolean overwrite) {
            Optional<D2Config.FlatConfig> cfg = config.flat("d2_default_value_config", name);
            if (cfg.isEmpty()) { warnings.add("default value config not in export: " + name); return; }
            List<String> names = cfg.get().list("attr_name"), values = cfg.get().list("attr_value");
            for (int i = 0; i < names.size(); i++) {
                String key = names.get(i).toLowerCase();
                if (overwrite || target.getOrDefault(key, "").isEmpty()) target.put(key, expand(at(values, i), source));
            }
        }

        Optional<LifecycleEntry> lifecycleEntry(String name) {
            try {
                Optional<org.w3c.dom.Document> doc = config.contents(LIFECYCLE, name);
                if (doc.isEmpty()) { warnings.add("lifecycle contents not in export: " + name); return Optional.empty(); }
                for (Element state : D2Config.children(doc.get().getDocumentElement(), "state")) {
                    if (!"true".equals(state.getAttribute("entry"))) continue;
                    Map<String, String> sets = new LinkedHashMap<>();
                    List<String> actions = new ArrayList<>();
                    for (Element block : D2Config.children(state, "actions")) {
                        for (Element action : D2Config.children(block, null)) {
                            List<Element> setters = switch (action.getTagName()) {
                                case "setproperties" -> D2Config.children(action, "set");
                                case "set" -> List.of(action);
                                default -> List.of();
                            };
                            setters.forEach(s -> sets.put(s.getAttribute("name").toLowerCase(), expand(s.getAttribute("value"), attrs)));
                            if (setters.isEmpty())
                                actions.add(action.getTagName() + (action.hasAttribute("name") ? ":" + action.getAttribute("name") : ""));
                        }
                    }
                    return Optional.of(new LifecycleEntry(name, state.getAttribute("name"), sets, actions));
                }
                return Optional.empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // -------------------------------------------------------------- property page

        List<FieldCheck> propertyChecks(String name, String mode) {
            org.w3c.dom.Document doc;
            try {
                Optional<org.w3c.dom.Document> found = config.contents(PROPERTY, name);
                if (found.isEmpty()) { warnings.add("property page contents not in export: " + name); return List.of(); }
                doc = found.get();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<Element> fields = new ArrayList<>();
            Map<String, String> dictionaryOfField = new HashMap<>();
            NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                if (!e.hasAttribute("id") || e.getTagName().equals("dialog")) continue;
                fields.add(e);
                if (!e.getAttribute("assistance_dictionary").isEmpty())
                    dictionaryOfField.put(e.getAttribute("id"), e.getAttribute("assistance_dictionary"));
            }
            List<FieldCheck> out = new ArrayList<>();
            for (Element field : fields) {
                String id = field.getAttribute("id"), value = attrs.getOrDefault(id.toLowerCase(), "");
                // condition_required_<mode> switches the check on for that mode; condition_required (optional) narrows it
                if ("true".equals(field.getAttribute("condition_required_" + mode)) && value.isEmpty()) {
                    String condition = field.getAttribute("condition_required");
                    Tri required = condition.isEmpty() ? Tri.T : jsCondition(condition);
                    if (required != Tri.F)
                        out.add(new FieldCheck(id, required == Tri.T ? "MISSING" : "MAYBE-REQUIRED",
                                condition.isEmpty() ? "always required on " + mode : condition));
                }
                if (value.isEmpty()) continue;
                String assistance = field.getAttribute("assistance_type"), dictionary = field.getAttribute("assistance_dictionary");
                if (config.hasDictionary(dictionary) && (assistance.equals("dictionary") || assistance.equals("taxonomy"))) {
                    D2Config.DictValue dv = config.dictionary(dictionary).get(value);
                    if (dv == null || !dv.enabled())
                        out.add(new FieldCheck(id, "INVALID", "'" + value + "' is not an enabled value of " + dictionary));
                }
                String taxonomyName = field.getAttribute("assistance_taxonomy");
                Optional<D2Config.Taxonomy> taxonomy = config.taxonomy(taxonomyName);
                if (assistance.equals("taxonomy") && taxonomy.isPresent()) {
                    int level = taxonomy.get().levels().indexOf(dictionary);
                    if (level <= 0) continue;
                    String parentDictionary = taxonomy.get().levels().get(level - 1);
                    Optional<String> parentField = dictionaryOfField.entrySet().stream()
                            .filter(x -> x.getValue().equals(parentDictionary)).map(Map.Entry::getKey).findFirst();
                    if (parentField.isEmpty()) continue;
                    String parentValue = attrs.getOrDefault(parentField.get().toLowerCase(), "");
                    boolean allowed = taxonomy.get().paths().stream()
                            .anyMatch(p -> p.length > level && p[level].equals(value) && p[level - 1].equals(parentValue));
                    if (!allowed)
                        out.add(new FieldCheck(id, "INVALID", "'" + parentValue + "/" + value + "' is not allowed by taxonomy " + taxonomyName));
                }
            }
            return out;
        }

        // -------------------------------------------------------------- object model (Composer types)

        /** The server fills type defaults on creation, before D2 runs its rules. */
        void applyTypeDefaults() {
            String type = attrs.getOrDefault("r_object_type", "");
            if (model == null || type.isEmpty()) return;
            Map<String, String> defaults = new LinkedHashMap<>();
            for (DarModel.TypeDef t : model.hierarchy(type))
                t.attributes().values().forEach(a -> {
                    if (a.defaultValue() != null && attrs.getOrDefault(a.name().toLowerCase(), "").isEmpty())
                        defaults.putIfAbsent(a.name().toLowerCase(), a.defaultValue());
                });
            if (!defaults.isEmpty()) changed(defaults);
        }

        /** Checks the effective attributes against the type definition: existence, datatype, length. */
        List<FieldCheck> modelChecks() {
            String type = attrs.getOrDefault("r_object_type", "");
            if (model == null || type.isEmpty()) return List.of();
            List<DarModel.TypeDef> chain = model.hierarchy(type);
            if (chain.isEmpty()) {
                if (!type.toLowerCase().startsWith("dm")) warnings.add("type '" + type + "' is not defined in the DAR model");
                return List.of();
            }
            chain.stream().filter(t -> model.tbos().containsKey(t.name())).forEach(t ->
                    warnings.add("type " + t.name() + " has a TBO: save-time code may change attributes after D2 rules ran"));
            // only when the chain ends in a core type is the model complete enough to call an attribute unknown
            String root = chain.get(chain.size() - 1).superType();
            boolean complete = root != null && root.startsWith("dm");

            List<FieldCheck> out = new ArrayList<>();
            attrs.forEach((name, value) -> {
                Optional<DarModel.Attribute> declared = model.attribute(type, name);
                if (declared.isEmpty()) {
                    if (DarModel.isCoreAttribute(name)) {
                        lengthCheck(name, value, DarModel.coreLength(name)).ifPresent(out::add);
                    } else if (complete) {
                        out.add(new FieldCheck(name, "UNKNOWN-ATTR", "not an attribute of " + type + " or its super types"));
                    }
                    return;
                }
                DarModel.Attribute a = declared.get();
                if (value.isEmpty()) return;
                switch (a.type()) {
                    case STRING -> lengthCheck(name, value, a.length()).ifPresent(out::add);
                    case INTEGER -> { if (!value.matches("-?\\d+")) out.add(new FieldCheck(name, "BAD-VALUE", "'" + value + "' is not an integer")); }
                    case DOUBLE -> { if (!value.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) out.add(new FieldCheck(name, "BAD-VALUE", "'" + value + "' is not a number")); }
                    case BOOLEAN -> { if (!value.matches("(?i)true|false|t|f|1|0")) out.add(new FieldCheck(name, "BAD-VALUE", "'" + value + "' is not a boolean")); }
                    case TIME -> { if (Dates.parse(value) == null) out.add(new FieldCheck(name, "BAD-VALUE", "'" + value + "' is not an ISO date")); }
                    case ID -> { if (!value.matches("[0-9a-fA-F]{16}")) out.add(new FieldCheck(name, "BAD-VALUE", "'" + value + "' is not an object id")); }
                    case UNKNOWN -> { }
                }
            });
            for (DarModel.TypeDef t : chain)
                for (DarModel.Attribute a : t.attributes().values())
                    if (a.required() && attrs.getOrDefault(a.name().toLowerCase(), "").isEmpty())
                        out.add(new FieldCheck(a.name(), "MISSING", "required by type " + t.name()));
            return out;
        }

        /** Documentum string lengths are in characters or bytes depending on the database, so both are reported. */
        Optional<FieldCheck> lengthCheck(String name, String value, int max) {
            if (max <= 0) return Optional.empty();
            if (value.length() > max)
                return Optional.of(new FieldCheck(name, "TOO-LONG", value.length() + " > " + max + " characters"));
            int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > max)
                return Optional.of(new FieldCheck(name, "TOO-LONG-BYTES", bytes + " UTF-8 bytes > " + max
                        + " (fails when the database counts bytes)"));
            return Optional.empty();
        }

        void checkFolderType(String type) {
            if (!type.isEmpty() && !type.startsWith("dm_") && model.type(type).isEmpty())
                warnings.add("autolink folder type '" + type + "' is not defined in the DAR model");
        }

        /** Evaluates the D2 property page condition language for getValue(x).equals('y') style expressions. */
        Tri jsCondition(String expression) {
            Matcher m = JS_GET_VALUE.matcher(expression);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = attrs.getOrDefault(m.group(1).toLowerCase(), "");
                boolean r = switch (m.group(2)) {
                    case "equalsIgnoreCase" -> v.equalsIgnoreCase(m.group(3));
                    case "notEquals" -> !v.equals(m.group(3));
                    default -> v.equals(m.group(3));
                };
                m.appendReplacement(sb, r ? "T" : "F");
            }
            m.appendTail(sb);
            String s = sb.toString().replaceAll("\\s", "");
            if (!s.matches("[TF()&|!]*")) return Tri.U;
            for (String previous = ""; !s.equals(previous); ) {
                previous = s;
                s = s.replace("!T", "F").replace("!F", "T").replace("(T)", "T").replace("(F)", "F")
                        .replace("T&&T", "T").replaceAll("[TF]&&[TF]", "F")
                        .replace("F||F", "F").replaceAll("[TF]\\|\\|[TF]", "T");
            }
            return switch (s) {
                case "T" -> Tri.T;
                case "F" -> Tri.F;
                default -> Tri.U;
            };
        }
    }

    private static String at(List<String> list, int i) {
        return i < list.size() ? list.get(i) : "";
    }

    private static String at(String[] array, int i) {
        return i < array.length ? array[i] : "";
    }
}
