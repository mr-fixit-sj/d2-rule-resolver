# d2-rule-resolver

Offline replay of OpenText Documentum **D2** context rules, driven only by a D2 configuration export.

When you migrate documents into a D2 repository, D2 decides at import time where a document goes and what it looks like:
the autolink folder, the folder types and ACLs of folders it creates, the document ACL, the autonamed `object_name`,
the lifecycle entry state, the retention policy, and which attributes the property page requires.
Migration tools usually reproduce that by calling D2 at runtime. This tool computes the same outcome **without a D2
server, DFC or repository connection**: it reads the export and evaluates the rules itself.

Typical uses:

- pre-compute target folders, ACLs and names for a migration load (batch CSV in, enriched CSV out)
- validate source metadata before loading (missing required attributes, values outside dictionaries or taxonomies)
- understand an unfamiliar client configuration: which context matrix row wins for a given document, and why

## Build

Requires JDK 17 or newer. Maven is provided by the wrapper (pinned to Maven 3.10.0).

```bash
./mvnw verify
```

The runnable jar is `target/d2-rule-resolver-<version>.jar`. It has no runtime dependencies.

## Usage

```bash
java -jar target/d2-rule-resolver-0.1.0-SNAPSHOT.jar --config path/to/export \
  --types types.properties \
  r_object_type=acme_document acme_process=Contracts "acme_doc_type=Lease Contract" \
  acme_doc_date=2024-03-15 acme_customer=C001 a_status=Active
```

`--config` takes the export folder (the one containing `D2.xml`) or the zip D2-Config produces.

Batch mode reads a CSV whose header holds attribute names and appends the resolved columns
(`d2_object_name`, `d2_folder`, `d2_acl_config`, `d2_acl`, `d2_lifecycle`, `d2_lc_state`, `d2_a_status`,
`d2_retention`, `d2_property_page`, `d2_issues`, `d2_undecided_modules`, `d2_warnings`):

```bash
java -jar target/d2-rule-resolver-0.1.0-SNAPSHOT.jar --config export.zip --types types.properties \
  --csv documents.csv --out resolved.csv
```

Attributes given on the command line apply to every CSV row (handy for `r_object_type=...`).

| Option | Meaning |
|---|---|
| `--as-new` | simulate a fresh import: apply the lifecycle entry-state properties (e.g. `a_status`) before resolving |
| `--defaults <name>` | apply a `d2_default_value_config` to attributes that are empty |
| `--mode import\|create\|edit` | property page mode whose required flags are checked (default `import`) |
| `--user`, `--groups a,b` | importing user and groups, for `USER` in where-clauses and role-based document sets |
| `--today yyyy-MM-dd` | value of `date(now)`, `date(today)`, `$TODAY` |
| `--locale en` | dictionary locale when a template names none |
| `--dar path` | Composer workspace, Composer project or built `.dar` (repeatable); see below |
| `--types file` | object type hierarchy, `child_type=super_type` per line (when no DAR is available) |
| `--type-parent c=p` | a single type hierarchy edge (repeatable) |

### Object model: DAR or type hierarchy

A D2 export does not contain the object model, but document sets filter on type (`dql_from`) and migrated values must
fit the attributes. The best source is the client's **Composer project or built DAR**:

```bash
java -jar target/d2-rule-resolver-0.1.0-SNAPSHOT.jar --config export.zip \
  --dar path/to/composer-workspace ...
```

From the type artifacts (`*.type`, or `*.type.artifact` inside a `.dar`) and TBO modules it adds:

- the type hierarchy, so no `--types` file is needed
- attribute checks: unknown attributes, string length in characters *and* UTF-8 bytes (`TOO-LONG`,
  `TOO-LONG-BYTES`), datatype (`BAD-VALUE` for integer, double, boolean, time, id), required attributes
- type default values, applied with `--as-new` before the D2 rules run
- a warning for types with a TBO, whose save-time code may change attributes after the D2 rules ran
- a warning when an autolink folder type is not defined

Jars, content and other artifact kinds are ignored. Sources win over Composer's `bin/` copies.

**Composer version.** The artifact parser was built and verified against **Composer 22.1.0000.0060** (artifact schema
namespace `.../artifact/12012007/...`). The loader reads `composer.version` from `version.properties` and the schema
version from the XML namespaces, and warns when either differs. Other releases most likely work, since the type
artifact format has been stable for years, but compare the reported type counts with the repository the first time.

Without a DAR, export the hierarchy from the repository and pass it with `--types`:

```sql
select name, super_name from dm_type where name not like 'dm%'
```

Format it as `name=super_name` lines. Without either, sets restricted to an unknown type are reported as undecidable.

## How it works

1. **Document sets** (`d2_documentset`): each where-clause is evaluated against the document's attributes with
   three-valued logic. Anything that needs the repository (sub-selects on repository tables, `Folder(...)`,
   group membership when no groups are given) evaluates to *unknown* rather than being guessed.
2. **Context matrix** (`d2_documentset_switch`): per module the rows are walked top-down and the first row whose
   document sets all match wins (`Parent¬Child` rows require both). Rows before the winner that evaluated to
   *unknown* are reported, because at runtime they could win instead.
3. **Autonaming** runs first, then **autolink** and **security**, mirroring the order of D2 lifecycle entry actions.
   The autonamed value is fed back before the remaining modules are resolved.
4. **Autolink** expands `$value(attr)`, `$value(attr.dictionary.locale|alias)` and `$value(attr.datePattern)` per
   folder level, and resolves the per-level folder type, folder ACL and folder default values.
5. **Security** decodes permits and extended permits, applies conditional rows (first applicable row per accessor
   wins), and resolves dynamic accessors that look up groups through a taxonomy.
6. **Property page** contents are checked for required fields (`condition_required_<mode>` plus an optional
   `condition_required` expression) and for values outside their dictionary or taxonomy.

Unresolvable parts never fail silently: they appear as `?` lines, `MAYBE-REQUIRED` checks or warnings.

## Limitations

- `$dqlvalue(...)` expressions are not executed (reported as warnings), except the taxonomy group lookup pattern in ACLs.
- Lifecycle transitions beyond the entry state, workflows and D2 methods (`applymethod`) are not simulated.
- Retention configs are reported by name only; the policy itself lives in the repository.
- Repeating attributes are treated as single-valued.
- From a DAR only types and TBO modules are read; permission sets, folders, groups and lifecycles are not used yet.

## Testing against a real export

The unit tests use a synthetic export in `src/test/resources/acme-export` and a synthetic Composer project in
`src/test/resources/acme-dar`. To smoke-test real client material (read in place, never copied into the project):

```bash
./mvnw verify -Dd2.export.dir=/path/to/export.zip -Dd2.dar.dir=/path/to/composer-workspace
```

It reports matrix rows that point to document sets or configs missing from the export, types the export uses that the
DAR does not define, and Composer version differences.

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [NOTICE](NOTICE).

OpenText, Documentum and D2 are trademarks of Open Text Corporation. This project is not affiliated with, sponsored by
or endorsed by Open Text Corporation. It reads configuration export files only and contains no OpenText code.
