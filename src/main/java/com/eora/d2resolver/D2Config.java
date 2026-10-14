package com.eora.d2resolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Read-only model of a D2 configuration export: the folder (or zip) produced by D2-Config "Export configuration",
 * containing D2.xml and one folder per config module. Nothing in here is specific to one client.
 */
public final class D2Config {

    /** Separator D2 uses in d2_documentset_switch for nested document sets ("Parent¬Child"). */
    static final String SET_PATH_SEPARATOR = "¬";

    public record DocSet(String name, List<String> from, String where, List<String> parents, List<String> groups,
                         List<String> applications, int order) {}

    public record SwitchEntry(List<String> path, String config) {
        @Override
        public String toString() {
            return String.join(" > ", path) + " -> " + config;
        }
    }

    public record DictValue(String name, boolean enabled, Map<String, String> locales, Map<String, String> aliases) {
        /** D2 resolves $value(attr.dic.key) against locale names first, then alias names. */
        Optional<String> lookup(String key) {
            if (locales.containsKey(key)) return Optional.of(locales.get(key));
            return Optional.ofNullable(aliases.get(key));
        }
    }

    public record Taxonomy(List<String> levels, List<String[]> paths) {}

    /**
     * Generic config: child elements holding value="..." or repeating values ("new_format" exports), plus root
     * attributes (attribute-style exports, e.g. dictionaries and older D2 versions).
     */
    public record FlatConfig(Map<String, List<String>> fields) {
        public String first(String key) {
            List<String> l = fields.get(key);
            return l == null || l.isEmpty() ? "" : l.get(0);
        }

        public List<String> list(String key) {
            return fields.getOrDefault(key, List.of());
        }
    }

    private final Path root;
    private final String application;
    private final String d2Version;
    private final Map<String, DocSet> docSets = new LinkedHashMap<>();
    private final Map<String, List<SwitchEntry>> switches = new LinkedHashMap<>();
    private final Map<String, Map<String, DictValue>> dictionaries = new LinkedHashMap<>();
    private final Map<String, Taxonomy> taxonomies = new LinkedHashMap<>();
    private final Map<String, Optional<FlatConfig>> flatCache = new HashMap<>();

    /**
     * The object type hierarchy is not part of a D2 export. Only Documentum core types are known up front;
     * client types are added with {@link #addTypeParent} or {@link #loadTypeHierarchy}.
     */
    private final Map<String, String> typeParent = new HashMap<>(Map.of(
            "dm_document", "dm_sysobject",
            "dm_folder", "dm_sysobject",
            "dm_cabinet", "dm_folder"));

    /** Opens an export folder, or a zip of one (extracted to a temporary folder). */
    public static D2Config open(Path exportFolderOrZip) throws IOException {
        return new D2Config(Files.isDirectory(exportFolderOrZip) ? exportFolderOrZip : unzip(exportFolderOrZip));
    }

    public D2Config(Path root) throws IOException {
        this.root = root;
        Path manifest = root.resolve("D2.xml");
        if (!Files.isRegularFile(manifest))
            throw new IOException("not a D2 configuration export (no D2.xml): " + root);
        Element d2 = parse(manifest).getDocumentElement();
        this.application = childText(d2, "application");
        this.d2Version = childText(d2, "project.version");

        for (Path p : xmlFiles("d2_documentset")) {
            FlatConfig m = readFlat(p);
            String order = m.first("order_no");
            DocSet d = new DocSet(m.first("object_name"), m.list("dql_from"), m.first("dql_where"), m.list("parent_names"),
                    m.list("group_names"), m.list("application_names"), order.isEmpty() ? 0 : Integer.parseInt(order));
            docSets.put(key(d.name()), d);
        }
        for (Path p : xmlFiles("d2_documentset_switch")) {
            Element r = parse(p).getDocumentElement();
            List<SwitchEntry> entries = children(r, "d2_documentset").stream()
                    .map(e -> new SwitchEntry(Arrays.asList(e.getAttribute("name").split(SET_PATH_SEPARATOR)), e.getAttribute("config")))
                    .toList();
            switches.put(r.getAttribute("object_name"), entries);
        }
        for (Path p : xmlFiles("d2_dictionary")) {
            Element r = parse(p).getDocumentElement();
            Map<String, DictValue> values = new LinkedHashMap<>();
            for (Element v : children(r, "d2_dictionary_value")) {
                Map<String, String> locales = new LinkedHashMap<>(), aliases = new LinkedHashMap<>();
                children(v, "locale").forEach(l -> locales.put(l.getAttribute("name"), l.getAttribute("value")));
                children(v, "alias").forEach(a -> aliases.put(a.getAttribute("name"), a.getAttribute("value")));
                String name = v.getAttribute("object_name");
                values.put(name, new DictValue(name, !"false".equals(v.getAttribute("is_enabled")), locales, aliases));
            }
            dictionaries.put(r.getAttribute("object_name"), values);
        }
        for (Path p : xmlFiles("d2_taxonomy")) {
            Element r = parse(p).getDocumentElement();
            List<String> levels = children(r, "level").stream()
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.getAttribute("position"))))
                    .map(e -> e.getAttribute("name")).toList();
            List<String[]> paths = children(r, "path").stream().map(e -> e.getAttribute("value").split("/")).toList();
            taxonomies.put(r.getAttribute("object_name"), new Taxonomy(levels, paths));
        }
    }

    // ------------------------------------------------------------------ accessors

    public Path root() { return root; }

    /** Application the export was taken from (D2.xml), used to ignore document sets of other applications. */
    public String application() { return application; }

    public String d2Version() { return d2Version; }

    public Optional<DocSet> docSet(String name) { return Optional.ofNullable(docSets.get(key(name))); }

    public List<DocSet> docSetsByOrder() {
        return docSets.values().stream().sorted(Comparator.comparingInt(DocSet::order)).toList();
    }

    public Map<String, List<SwitchEntry>> switches() { return switches; }

    public boolean hasDictionary(String name) { return dictionaries.containsKey(name); }

    public Map<String, DictValue> dictionary(String name) { return dictionaries.getOrDefault(name, Map.of()); }

    public Optional<Taxonomy> taxonomy(String name) { return Optional.ofNullable(taxonomies.get(name)); }

    public void addTypeParent(String type, String parent) { typeParent.put(type.toLowerCase(), parent.toLowerCase()); }

    /** Loads "child_type=super_type" lines, e.g. the output of: select name, super_name from dm_type. */
    public void loadTypeHierarchy(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        p.forEach((k, v) -> addTypeParent(k.toString().trim(), v.toString().trim()));
    }

    public boolean knowsType(String type) {
        return typeParent.containsKey(type.toLowerCase()) || typeParent.containsValue(type.toLowerCase());
    }

    public boolean isTypeOf(String type, String wanted) {
        for (String t = type.toLowerCase(); t != null; t = typeParent.get(t)) if (t.equalsIgnoreCase(wanted)) return true;
        return false;
    }

    /** A module config such as d2_link_config/my_autolink, empty when it is not part of the export. */
    public Optional<FlatConfig> flat(String module, String name) {
        return flatCache.computeIfAbsent(module + "/" + name, k -> {
            Path p = root.resolve(module).resolve(name + ".xml");
            if (!Files.exists(p)) return Optional.empty();
            try {
                return Optional.of(readFlat(p));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** XML payload stored as content of a config object (property pages, lifecycles). */
    public Optional<Document> contents(String module, String name) throws IOException {
        Path p = root.resolve(module).resolve("contents").resolve(name + ".xml");
        return Files.exists(p) ? Optional.of(parse(p)) : Optional.empty();
    }

    // ------------------------------------------------------------------ XML helpers

    static String key(String setName) {
        return setName.toLowerCase();
    }

    private List<Path> xmlFiles(String module) throws IOException {
        Path dir = root.resolve(module);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
    }

    static Document parse(Path p) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(p.toFile());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot parse " + p + ": " + e.getMessage(), e);
        }
    }

    static List<Element> children(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element c && (tag == null || c.getTagName().equals(tag))) out.add(c);
        return out;
    }

    private static String childText(Element e, String tag) {
        List<Element> c = children(e, tag);
        return c.isEmpty() ? "" : c.get(0).getTextContent().trim();
    }

    private static FlatConfig readFlat(Path p) throws IOException {
        Element root = parse(p).getDocumentElement();
        Map<String, List<String>> m = new LinkedHashMap<>();
        NamedNodeMap attributes = root.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr a = (Attr) attributes.item(i);
            m.put(a.getName(), List.of(a.getValue()));
        }
        for (Element c : children(root, null)) {
            List<String> values = new ArrayList<>();
            if (c.hasAttribute("value")) values.add(c.getAttribute("value"));
            children(c, "repeating").forEach(r -> values.add(r.getAttribute("value")));
            m.put(c.getTagName(), values);
        }
        return new FlatConfig(m);
    }

    private static Path unzip(Path zip) throws IOException {
        Path target = Files.createTempDirectory("d2-export-");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) throw new IOException("zip entry outside target: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // exports are zipped either flat or with one top-level folder
        if (Files.exists(target.resolve("D2.xml"))) return target;
        try (Stream<Path> s = Files.list(target)) {
            return s.filter(d -> Files.exists(d.resolve("D2.xml"))).findFirst()
                    .orElseThrow(() -> new IOException("no D2.xml found in " + zip));
        }
    }
}
