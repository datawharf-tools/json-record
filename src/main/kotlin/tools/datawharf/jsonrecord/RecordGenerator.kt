package tools.datawharf.jsonrecord

enum class AnnotationStyle { NONE, JACKSON, GSON }

data class Options(
    val rootName: String = "Root",
    val annotations: AnnotationStyle = AnnotationStyle.NONE,
    val nested: Boolean = true,
    val indent: String = "    ",
)

/** Inferred type tree. */
private sealed class T {
    object Unknown : T()
    object Bool : T()
    object Int : T()
    object Long : T()
    object Double : T()
    object Str : T()
    data class ListOf(val el: T) : T()
    class Rec(val fields: LinkedHashMap<String, T>, val optional: MutableSet<String> = linkedSetOf()) : T()
}

object RecordGenerator {
    private val RESERVED = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "var", "yield", "null", "true", "false",
    )

    fun generate(json: String, options: Options = Options()): String = generate(JsonParser.parse(json), options)

    fun generate(root: Json, options: Options): String {
        val type = infer(root)
        val rootRec = when (type) {
            is T.Rec -> type
            is T.ListOf -> (type.el as? T.Rec) ?: throw IllegalArgumentException("Top-level JSON must be an object or an array of objects")
            else -> throw IllegalArgumentException("Top-level JSON must be an object or an array of objects")
        }
        val imports = sortedSetOf<String>()
        val defs = ArrayList<Pair<String, String>>() // used only when nested = false
        val body = emitRecord(options.rootName.toClassName(), rootRec, options, 0, imports, defs)
        val flat = if (options.nested) "" else defs.joinToString("") { "\n" + it.second }
        val all = body + flat
        when (options.annotations) {
            AnnotationStyle.JACKSON -> if (all.contains("@JsonProperty")) imports.add("com.fasterxml.jackson.annotation.JsonProperty")
            AnnotationStyle.GSON -> if (all.contains("@SerializedName")) imports.add("com.google.gson.annotations.SerializedName")
            else -> {}
        }
        if (all.contains("List<")) imports.add("java.util.List")
        val out = StringBuilder()
        for (i in imports) out.append("import ").append(i).append(";\n")
        if (imports.isNotEmpty()) out.append('\n')
        out.append(all)
        return out.toString()
    }

    // ---- inference ----
    private fun infer(j: Json): T = when (j) {
        is Json.Null -> T.Unknown
        is Json.Bool -> T.Bool
        is Json.Str -> T.Str
        is Json.Num ->
            if (!j.isInteger) T.Double
            else if (j.raw.toLongOrNull()?.let { it in Int.MIN_VALUE..Int.MAX_VALUE } == true) T.Int
            else T.Long
        is Json.Arr -> T.ListOf(j.items.map(::infer).fold(T.Unknown as T) { a, b -> merge(a, b) })
        is Json.Obj -> T.Rec(LinkedHashMap(j.fields.mapValues { infer(it.value) }))
    }

    private fun merge(a: T, b: T): T = when {
        a === T.Unknown -> b
        b === T.Unknown -> a
        a === b -> a
        a is T.Rec && b is T.Rec -> {
            val fields = LinkedHashMap<String, T>()
            val optional = (a.optional + b.optional).toMutableSet()
            for ((k, v) in a.fields) {
                val o = b.fields[k]
                if (o == null) optional.add(k)
                fields[k] = if (o == null) v else merge(v, o)
            }
            for ((k, v) in b.fields) if (k !in a.fields) { fields[k] = v; optional.add(k) }
            T.Rec(fields, optional)
        }
        a is T.ListOf && b is T.ListOf -> T.ListOf(merge(a.el, b.el))
        (a === T.Int && b === T.Long) || (a === T.Long && b === T.Int) -> T.Long
        isNum(a) && isNum(b) -> T.Double
        else -> T.Unknown // incompatible -> Object
    }

    private fun isNum(t: T) = t === T.Int || t === T.Long || t === T.Double

    // ---- emission ----
    private fun emitRecord(
        name: String, rec: T.Rec, o: Options, depth: Int, imports: MutableSet<String>, defs: MutableList<Pair<String, String>>,
    ): String {
        val pad = o.indent.repeat(depth)
        val inner = o.indent.repeat(depth + 1)
        val nestedBodies = ArrayList<String>()
        val usedNames = HashSet<String>()
        val comps = ArrayList<String>()
        for ((key, t) in rec.fields) {
            var field = key.toFieldName()
            while (!usedNames.add(field)) field += "_"
            val typeName = typeName(t, key, rec.optional.contains(key), o, depth, nestedBodies, defs, imports)
            val ann = when (o.annotations) {
                AnnotationStyle.JACKSON -> if (field != key) "@JsonProperty(\"${esc(key)}\") " else ""
                AnnotationStyle.GSON -> if (field != key) "@SerializedName(\"${esc(key)}\") " else ""
                else -> ""
            }
            comps.add("$inner$ann$typeName $field")
        }
        val sb = StringBuilder()
        if (comps.isEmpty()) {
            sb.append(pad).append("public record ").append(name).append("() {")
        } else {
            sb.append(pad).append("public record ").append(name).append("(\n")
                .append(comps.joinToString(",\n")).append("\n").append(pad).append(") {")
        }
        if (nestedBodies.isEmpty()) {
            sb.append("}\n")
        } else {
            sb.append('\n').append(nestedBodies.joinToString("\n")).append(pad).append("}\n")
        }
        return sb.toString()
    }

    private fun typeName(
        t: T, key: String, optional: Boolean, o: Options, depth: Int,
        nested: MutableList<String>, defs: MutableList<Pair<String, String>>, imports: MutableSet<String>,
    ): String = when (t) {
        T.Unknown -> "Object"
        T.Bool -> if (optional) "Boolean" else "boolean"
        T.Int -> if (optional) "Integer" else "int"
        T.Long -> if (optional) "Long" else "long"
        T.Double -> if (optional) "Double" else "double"
        T.Str -> "String"
        is T.ListOf -> "List<" + boxed(typeName(t.el, key.singular(), false, o, depth, nested, defs, imports)) + ">"
        is T.Rec -> {
            val n = key.toClassName()
            if (o.nested) nested.add(emitRecord(n, t, o, depth + 1, imports, defs))
            else defs.add(n to emitRecord(n, t, o, 0, imports, defs))
            n
        }
    }

    private fun boxed(n: String) = when (n) {
        "int" -> "Integer"
        "long" -> "Long"
        "double" -> "Double"
        "boolean" -> "Boolean"
        else -> n
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun words(s: String): List<String> =
        s.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.flatMap { w ->
            Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+").findAll(w).map { it.value }.toList()
        }

    internal fun String.toClassName(): String {
        val n = words(this).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val safe = if (n.isEmpty()) "Item" else n
        return if (safe[0].isDigit()) "_$safe" else safe
    }

    internal fun String.toFieldName(): String {
        val w = words(this)
        if (w.isEmpty()) return "field"
        var n = w[0].lowercase() + w.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        if (n[0].isDigit()) n = "_$n"
        return if (n in RESERVED) "${n}_" else n
    }

    internal fun String.singular(): String = when {
        endsWith("ies") && length > 3 -> dropLast(3) + "y"
        endsWith("sses") -> dropLast(2)
        endsWith("s") && !endsWith("ss") && length > 1 -> dropLast(1)
        else -> this
    }
}
