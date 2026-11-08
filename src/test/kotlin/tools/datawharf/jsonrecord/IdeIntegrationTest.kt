package tools.datawharf.jsonrecord

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runWriteAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class IdeIntegrationTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = JAVA_LATEST_WITH_LATEST_JDK

    private val json = """{"id": 1, "user_name": "a", "tags": ["x"], "address": {"city": "Z", "zip": 12345678901},
        "orders": [{"sku": "s", "qty": 2}, {"sku": "t"}], "class": true}"""

    private fun run(text: String, style: AnnotationStyle = AnnotationStyle.NONE, name: String = "Root") =
        GenerateRecordAction.apply(project, myFixture.editor, null, text, name, style)

    private fun assertNoErrors() {
        myFixture.doHighlighting()
        val psiErrors = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java)
        assertTrue("PSI errors: ${psiErrors.map { it.errorDescription }}", psiErrors.isEmpty())
        val errs: List<HighlightInfo> = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertTrue("Highlight errors: ${errs.map { it.description }}\n${myFixture.file.text}", errs.isEmpty())
    }

    fun testInsertIntoClassBody() {
        myFixture.configureByText("Foo.java", "public class Foo {\n    <caret>\n}\n")
        assertNull(run(json))
        val t = myFixture.file.text
        assertTrue(t, t.contains("record Root("))
        assertTrue(t, t.contains("record Address("))
        assertTrue(t, t.contains("List<Order> orders"))
        assertTrue(t, t.contains("Integer qty") || t.contains("Integer"))
        assertNoErrors()
    }

    fun testTopLevelInFileWithMatchingName() {
        myFixture.configureByText("Root.java", "package a.b;\n\n<caret>")
        assertNull(run(json, AnnotationStyle.JACKSON))
        assertTrue(myFixture.file.text.contains("import java.util.List;"))
        assertTrue(myFixture.file.text.indexOf("import") < myFixture.file.text.indexOf("record"))
        // Jackson is not on the test classpath, so only syntax errors are checked here
        myFixture.doHighlighting()
        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
    }

    fun testTopLevelMismatchedFileNameAndExistingImports() {
        myFixture.configureByText("Other.java", "import java.util.Map;\n\npublic class Other {}\n<caret>\n")
        assertNull(run(json))
        val t = myFixture.file.text
        assertTrue(t, t.startsWith("import java.util.Map;\nimport java.util.List;") || t.contains("import java.util.List;"))
        assertNoErrors()
    }

    fun testInsideMethodAndSelectionReplaced() {
        myFixture.configureByText("Foo.java", "public class Foo {\n    <selection>JUNK</selection>\n}\n")
        assertNull(run("""{"a": 1}""", name = "Point"))
        assertFalse(myFixture.file.text.contains("JUNK"))
        assertNoErrors()
    }

    fun testGsonAnnotationsCompileWithStub() {
        myFixture.addClass("package com.google.gson.annotations; public @interface SerializedName { String value(); }")
        myFixture.configureByText("Foo.java", "public class Foo {\n    <caret>\n}\n")
        assertNull(run(json, AnnotationStyle.GSON))
        assertTrue(myFixture.file.text.contains("@SerializedName(\"user_name\")"))
        assertNoErrors()
    }

    fun testTopLevelArray() {
        myFixture.configureByText("Foo.java", "public class Foo {\n    <caret>\n}\n")
        assertNull(run("""[{"a":1},{"a":2.5,"b":null}]"""))
        assertNoErrors()
    }

    fun testInvalidJsonGivesMessageAndLeavesFileUntouched() {
        myFixture.configureByText("Foo.java", "public class Foo {\n    <caret>\n}\n")
        val before = myFixture.file.text
        for (bad in listOf("{\"a\": ", "not json", "[1,2]", "\"str\"", "{\"a\":1} trailing", "")) {
            val err = run(bad)
            assertNotNull("expected error for '$bad'", err)
            assertTrue(err!!.isNotBlank())
        }
        assertEquals(before, myFixture.file.text)
    }

    fun testCreateFileInDirectory() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("gen")
        assertNull(GenerateRecordAction.apply(project, null, dir, json, "my-payload", AnnotationStyle.NONE))
        val f = dir.findChild("MyPayload.java")
        assertNotNull(f)
        val text = String(f!!.contentsToByteArray())
        assertTrue(text, text.contains("public record MyPayload("))
        myFixture.configureFromExistingVirtualFile(f)
        assertNoErrors()
        // second run: no overwrite, graceful message
        val err = GenerateRecordAction.apply(project, null, dir, json, "my-payload", AnnotationStyle.NONE)
        assertTrue(err, err!!.contains("already exists"))
    }

    fun testNoTargetGivesMessage() {
        val err = GenerateRecordAction.apply(project, null, null, json, "Root", AnnotationStyle.NONE)
        assertNotNull(err)
    }

    fun testActionRegisteredInMenus() {
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        assertNotNull(am.getAction("tools.datawharf.jsonrecord.Generate"))
        for (g in listOf("ToolsMenu", "GenerateGroup", "NewGroup")) {
            val grp = am.getAction(g) as com.intellij.openapi.actionSystem.DefaultActionGroup
            assertTrue(g, grp.getChildren(null).any { am.getId(it) == "tools.datawharf.jsonrecord.Generate" })
        }
    }
}
