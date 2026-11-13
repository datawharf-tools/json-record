# JSON to Java Record

An IntelliJ IDEA plugin: paste JSON, get Java `record` types.

- Nested records with sensible types (`int`, `long`, `double`, `boolean`, `String`, `List`, `Object`)
- Optional Jackson (`@JsonProperty`) or Gson (`@SerializedName`) annotations for non-Java field names
- Array elements are merged, so optional fields become boxed types
- Insert at the caret, or create a new `.java` file from the Project view
- No network access, no telemetry

Use it from **Tools | Paste JSON as Java Record**, the editor **Generate** menu (Alt+Insert), or the Project view **New** menu.

Requires IntelliJ IDEA 2024.1 or newer (Java support).

## Build

```bash
./gradlew buildPlugin   # zip in build/distributions
./gradlew test          # unit + IDE integration tests
```

## License

Apache License 2.0. See [LICENSE](LICENSE).

Made by [Datawharf Tools](https://plugins.jetbrains.com/vendor/datawharf-tools).
