package com.eora.d2resolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Object model read from Documentum Composer artifacts: type definitions (*.type) and TBO modules (*.module).
 *
 * Accepts a Composer workspace, a single Composer project, or a built .dar file (a zip of *.artifact entries
 * with the same XML). Content, jars and other artifact kinds are ignored.
 */
public final class DarModel {

    public enum DataType { STRING, INTEGER, DOUBLE, TIME, BOOLEAN, ID, UNKNOWN }

    /**
     * @param length       declared length for strings, 0 when not declared
     * @param defaultValue literal default expression, null when none
     * @param required     is_required="true" in the data dictionary annotations
     */
    public record Attribute(String name, DataType type, int length, boolean repeating, String defaultValue, boolean required) {}

    public record TypeDef(String name, String superType, Map<String, Attribute> attributes, String source) {}

    /** Type name taken from a super_name href: urn:com.emc.ide.artifact.dclass/<name>?name=... or ?artifactURI=... */
    private static final Pattern URN_TYPE = Pattern.compile("dclass/([A-Za-z0-9_]+)\\?");

    /** Documentum core attributes that are never declared in a client DAR. Prefixes r_, i_ and a_ are core as well. */
    private static final Set<String> CORE_ATTRIBUTES = Set.of(
            "object_name", "title", "subject", "authors", "keywords", "owner_name", "owner_permit", "group_name",
            "group_permit", "world_permit", "acl_name", "acl_domain", "language_code", "log_entry", "resolution_label");

    /** Lengths of core string attributes in current Documentum releases. */
    private static final Map<String, Integer> CORE_LENGTHS = Map.of("object_name", 255, "title", 400, "subject", 192);

    /** Composer release the artifact parser was built and tested against. */
    public static final String VERIFIED_COMPOSER = "22.1.0000.0060";

    /** Version segment of the Composer artifact XML namespaces (e.g. .../artifact/12012007/dclass). */
    public static final String VERIFIED_SCHEMA = "12012007";

    private static final Pattern SCHEMA_NAMESPACE = Pattern.compile("http://documentum\\.emc\\.com/[a-z]+/(\\d+)/");

    private final Map<String, TypeDef> types = new LinkedHashMap<>();
    private final Map<String, String> tbos = new LinkedHashMap<>();
    private final List<String> sources = new ArrayList<>();
    private final Set<String> composerVersions = new java.util.TreeSet<>();
    private final Set<String> schemaVersions = new java.util.TreeSet<>();

    /** Loads every type and module artifact found under the given paths (folders or .dar files). */
    public static DarModel load(Collection<Path> paths) throws IOException {
        DarModel model = new DarModel();
        for (Path p : paths) {
            if (Files.isDirectory(p)) model.loadFolder(p);
            else model.loadDar(p);
            model.sources.add(p.toString());
        }
        return model;
    }

    // ------------------------------------------------------------------ queries

    public Map<String, TypeDef> types() { return types; }

    public Optional<TypeDef> type(String name) { return Optional.ofNullable(types.get(name.toLowerCase())); }

    /** Type name to TBO module name. */
    public Map<String, String> tbos() { return tbos; }

    public List<String> sources() { return sources; }

    /** composer.version values found in version.properties files. */
    public Set<String> composerVersions() { return composerVersions; }

    /** Artifact schema versions found in the XML namespaces. */
    public Set<String> schemaVersions() { return schemaVersions; }

    /** Differences from the Composer release this parser was verified against. */
    public List<String> compatibilityWarnings() {
        List<String> out = new ArrayList<>();
        if (composerVersions.isEmpty())
            out.add("no composer.version found (version.properties); parser was verified with Composer " + VERIFIED_COMPOSER);
        composerVersions.stream().filter(v -> !majorMinor(v).equals(majorMinor(VERIFIED_COMPOSER)))
                .forEach(v -> out.add("built with Composer " + v + "; parser was verified with " + VERIFIED_COMPOSER
                        + ": check type/attribute counts against the repository"));
        schemaVersions.stream().filter(v -> !v.equals(VERIFIED_SCHEMA))
                .forEach(v -> out.add("artifact schema " + v + " differs from verified schema " + VERIFIED_SCHEMA));
        return out;
    }

    private static String majorMinor(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : version;
    }

    /** Type and its super types, most specific first; stops at the first type not in the model (e.g. dm_document). */
    public List<TypeDef> hierarchy(String typeName) {
        List<TypeDef> chain = new ArrayList<>();
        for (Optional<TypeDef> t = type(typeName); t.isPresent(); t = type(t.get().superType())) {
            chain.add(t.get());
            if (chain.size() > 50) break; // defensive: cyclic definitions
        }
        return chain;
    }

    /** Attribute declared on the type or one of its modelled super types. */
    public Optional<Attribute> attribute(String typeName, String attr) {
        String key = attr.toLowerCase();
        return hierarchy(typeName).stream().map(t -> t.attributes().get(key)).filter(a -> a != null).findFirst();
    }

    public static boolean isCoreAttribute(String attr) {
        String a = attr.toLowerCase();
        return CORE_ATTRIBUTES.contains(a) || a.startsWith("r_") || a.startsWith("i_") || a.startsWith("a_");
    }

    public static int coreLength(String attr) {
        return CORE_LENGTHS.getOrDefault(attr.toLowerCase(), 0);
    }

    /** Pushes the super type edges into the D2 config so dql_from checks work without a separate --types file. */
    public void applyTo(D2Config config) {
        types.values().forEach(t -> { if (t.superType() != null) config.addTypeParent(t.name(), t.superType()); });
    }

    // ------------------------------------------------------------------ loading

    private void loadFolder(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> all = s.filter(Files::isRegularFile).toList();
            for (Path v : all)
                if (v.getFileName().toString().equals("version.properties") && !isDerived(root.relativize(v)))
                    try (InputStream in = Files.newInputStream(v)) {
                        readVersion(in);
                    }
            List<Path> files = all.stream()
                    .filter(p -> isArtifact(p.getFileName().toString()))
                    // Composer keeps compiled copies in bin/ and per-locale copies in locales/: sources win
                    .sorted((a, b) -> Boolean.compare(isDerived(root.relativize(a)), isDerived(root.relativize(b))))
                    .toList();
            for (Path f : files) {
                try (InputStream in = Files.newInputStream(f)) {
                    read(in, root.relativize(f).toString());
                }
            }
        }
    }

    private void loadDar(Path dar) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(dar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName().replace('\\', '/');
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (e.isDirectory()) continue;
                if (fileName.equals("version.properties")) readVersion(zip);
                else if (isArtifact(fileName)) read(zip, dar.getFileName() + "!" + name);
            }
        }
    }

    private static boolean isArtifact(String fileName) {
        String n = fileName.endsWith(".artifact") ? fileName.substring(0, fileName.length() - ".artifact".length()) : fileName;
        return n.endsWith(".type") || n.endsWith(".module");
    }

    private static boolean isDerived(Path relative) {
        for (Path part : relative) {
            String p = part.toString();
            if (p.equals("bin") || p.equals("bin-dar") || p.equals("locales") || p.startsWith(".")) return true;
        }
        return false;
    }

    private void readVersion(InputStream in) throws IOException {
        java.util.Properties p = new java.util.Properties();
        p.load(new java.io.FilterInputStream(in) {
            @Override
            public void close() {
            }
        });
        String v = p.getProperty("composer.version");
        if (v != null && !v.isBlank()) composerVersions.add(v.trim());
    }

    private void read(InputStream in, String source) throws IOException {
        Document doc = parse(in, source);
        org.w3c.dom.NamedNodeMap rootAttrs = doc.getDocumentElement().getAttributes();
        for (int i = 0; i < rootAttrs.getLength(); i++) {
            Matcher ns = SCHEMA_NAMESPACE.matcher(rootAttrs.item(i).getNodeValue());
            if (rootAttrs.item(i).getNodeName().startsWith("xmlns:") && ns.find()) schemaVersions.add(ns.group(1));
        }
        NodeList models = doc.getElementsByTagName("dataModel");
        for (int i = 0; i < models.getLength(); i++) {
            Element model = (Element) models.item(i);
            if (model.hasAttribute("a_module_type")) {
                if ("TBO".equalsIgnoreCase(model.getAttribute("a_module_type")))
                    tbos.putIfAbsent(model.getAttribute("objectName").toLowerCase(), model.getAttribute("objectName"));
                continue;
            }
            for (Element type : D2Config.children(model, "type")) {
                TypeDef def = readType(type, source);
                types.putIfAbsent(def.name().toLowerCase(), def);
            }
        }
    }

    private static TypeDef readType(Element type, String source) {
        String superType = null;
        for (Element s : D2Config.children(type, "super_name")) {
            Matcher m = URN_TYPE.matcher(s.getAttribute("href"));
            if (m.find()) superType = m.group(1).toLowerCase();
        }
        Map<String, Attribute> attributes = new LinkedHashMap<>();
        for (Element primary : D2Config.children(type, "primaryElement")) {
            for (Element a : D2Config.children(primary, "attributes")) {
                Attribute attr = readAttribute(a);
                attributes.put(attr.name().toLowerCase(), attr);
            }
        }
        return new TypeDef(type.getAttribute("name").toLowerCase(), superType, attributes, source);
    }

    private static Attribute readAttribute(Element a) {
        DataType dataType = DataType.UNKNOWN;
        for (Element t : D2Config.children(a, "attr_type")) {
            try {
                dataType = DataType.valueOf(t.getAttribute("values").toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // new or unusual datatype: keep UNKNOWN, no value checks
            }
        }
        String defaultValue = null;
        boolean required = false;
        for (Element annotation : D2Config.children(a, "attrAnnotations")) {
            if (!annotation.getAttribute("xsi:type").endsWith("AttrDDInfo")) continue;
            required = "true".equalsIgnoreCase(annotation.getAttribute("is_required"))
                    || "true".equalsIgnoreCase(annotation.getAttribute("not_null"));
            for (Element d : D2Config.children(annotation, "default_value")) defaultValue = literal(d.getAttribute("expressionText"));
        }
        String length = a.getAttribute("attr_length");
        return new Attribute(a.getAttribute("attr_name"), dataType, length.isEmpty() ? 0 : Integer.parseInt(length),
                "true".equalsIgnoreCase(a.getAttribute("attr_repeating")), defaultValue, required);
    }

    /** Default expressions are DocBasic literals: 5, 'text' or "text". */
    private static String literal(String expression) {
        String e = expression.trim();
        if (e.length() >= 2 && (e.startsWith("'") && e.endsWith("'") || e.startsWith("\"") && e.endsWith("\"")))
            return e.substring(1, e.length() - 1);
        return e;
    }

    private static Document parse(InputStream in, String source) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            // the zip stream must stay open for the next entry
            return f.newDocumentBuilder().parse(new java.io.FilterInputStream(in) {
                @Override
                public void close() {
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot parse " + source + ": " + e.getMessage(), e);
        }
    }
}
