package tools.datawharf.jsonrecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordGeneratorTest {
    @Test fun primitivesAndStrings() {
        val out = RecordGenerator.generate("""{"id":1,"big":12345678901,"price":9.5,"ok":true,"name":"x"}""", Options("Item"))
        assertTrue(out, out.contains("int id"))
        assertTrue(out.contains("long big"))
        assertTrue(out.contains("double price"))
        assertTrue(out.contains("boolean ok"))
        assertTrue(out.contains("String name"))
        assertTrue(out.contains("public record Item("))
    }

    @Test fun nestedRecordsAndLists() {
        val out = RecordGenerator.generate("""{"user":{"id":1},"tags":["a","b"],"orders":[{"sku":"x"}]}""")
        assertTrue(out, out.contains("public record User("))
        assertTrue(out.contains("List<String> tags"))
        assertTrue(out.contains("List<Order> orders"))
        assertTrue(out.contains("import java.util.List;"))
    }

    @Test fun mergesArrayElementsMakesOptionalBoxed() {
        val out = RecordGenerator.generate("""{"rows":[{"a":1,"b":2},{"a":3}]}""")
        assertTrue(out, out.contains("int a"))
        assertTrue(out.contains("Integer b"))
    }

    @Test fun mixedNumbersWidenAndConflictsBecomeObject() {
        val out = RecordGenerator.generate("""{"n":[1,2.5],"m":[1,"x"],"z":null}""")
        assertTrue(out, out.contains("List<Double> n"))
        assertTrue(out.contains("List<Object> m"))
        assertTrue(out.contains("Object z"))
    }

    @Test fun snakeCaseWithJackson() {
        val out = RecordGenerator.generate("""{"first_name":"a","class":"b"}""", Options(annotations = AnnotationStyle.JACKSON))
        assertTrue(out, out.contains("@JsonProperty(\"first_name\") String firstName"))
        assertTrue(out.contains("@JsonProperty(\"class\") String class_"))
        assertTrue(out.contains("import com.fasterxml.jackson.annotation.JsonProperty;"))
    }

    @Test fun gsonImportOnlyWhenNeeded() {
        val plain = RecordGenerator.generate("""{"a":1}""", Options(annotations = AnnotationStyle.GSON))
        assertFalse(plain.contains("SerializedName"))
        val ann = RecordGenerator.generate("""{"a_b":1}""", Options(annotations = AnnotationStyle.GSON))
        assertTrue(ann.contains("@SerializedName(\"a_b\") int aB"))
    }

    @Test fun topLevelArrayOfObjects() {
        val out = RecordGenerator.generate("""[{"a":1},{"a":2}]""", Options("Row"))
        assertTrue(out, out.contains("public record Row("))
    }

    @Test fun flatModeEmitsSiblingRecords() {
        val out = RecordGenerator.generate("""{"user":{"id":1}}""", Options(nested = false))
        assertTrue(out, out.indexOf("record Root") < out.indexOf("record User"))
    }

    @Test fun duplicateFieldNamesAfterNormalizationAreDisambiguated() {
        val out = RecordGenerator.generate("""{"a-b":1,"a_b":2}""")
        assertTrue(out, out.contains("int aB,") && out.contains("int aB_"))
    }

    @Test fun invalidJsonThrows() {
        assertThrows(JsonParseException::class.java) { RecordGenerator.generate("""{"a":""") }
        assertThrows(JsonParseException::class.java) { RecordGenerator.generate("""{"a":1} x""") }
        assertThrows(IllegalArgumentException::class.java) { RecordGenerator.generate("""42""") }
    }

    @Test fun parserHandlesEscapesAndUnicode() {
        val j = JsonParser.parse("""{"s":"a\n\"b\" A"}""") as Json.Obj
        assertEquals("a\n\"b\" A", (j.fields["s"] as Json.Str).value)
    }
}
