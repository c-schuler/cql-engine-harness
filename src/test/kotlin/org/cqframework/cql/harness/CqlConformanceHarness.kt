package org.cqframework.cql.harness

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.cqframework.cql.cql2elm.CqlCompilerException
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.StringLibrarySourceProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.engine.elm.executing.EquivalentEvaluator
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.runtime.Value
import org.w3c.dom.Element

/**
 * Runs the [cql-tests](https://github.com/cqframework/cql-tests) conformance suite directly against
 * the reference CQL engine (pulled in as live source via the Gradle composite build). For each
 * `<test>` it compiles and evaluates the `<expression>` and `<output>` as separate defines and
 * compares them with the engine's own equivalence — no FHIR server, no clinical-reasoning.
 *
 * This is a report generator, not a pass/fail unit test: it never asserts on the failure count, so
 * it won't break the build. Results are written to `build/reports/cql-conformance.txt`.
 *
 * Test suite location resolves in order: `CQL_TESTS_DIR` env var, the `cqlTestsDir` system
 * property, then the vendored submodule `cql-tests/tests/cql`.
 */
class CqlConformanceHarness {

    private enum class Outcome {
        PASS,
        FAIL,
        ERROR,
    }

    private data class Case(
        val file: String,
        val group: String,
        val name: String,
        val expression: String,
        val outputs: List<String>,
        val invalid: kotlin.Boolean,
    )

    @Test
    fun runConformanceSuite() {
        val dir = resolveTestDir()
        Assumptions.assumeTrue(dir.isDirectory, "cql-tests dir not found: $dir (skipping)")

        val xmlFiles = dir.listFiles { f -> f.extension == "xml" }?.sortedBy { it.name } ?: emptyList()

        var total = 0
        var pass = 0
        var fail = 0
        var errored = 0
        val failures = StringBuilder()
        val perFile = StringBuilder()

        for (xml in xmlFiles) {
            val cases = parse(xml)
            if (cases.isEmpty()) continue

            // Fresh manager/engine per file to bound memory and isolate compile state.
            val cqlById = LinkedHashMap<String, String>()
            cases.forEachIndexed { i, c -> cqlById["T_$i"] = buildCql("T_$i", c) }

            val options = CqlCompilerOptions()
            options.setOptions(
                CqlCompilerOptions.Options.EnableLocators,
                CqlCompilerOptions.Options.EnableResultTypes,
            )
            val lm = LibraryManager(ModelManager(), options)
            lm.librarySourceLoader.registerProvider(StringLibrarySourceProvider(cqlById.values.toList()))
            val engine = CqlEngine(Environment(lm), mutableSetOf())

            var fPass = 0
            cases.forEachIndexed { i, c ->
                total++
                val id = VersionedIdentifier().withId("T_$i").withVersion("1.0.0")
                when (evaluateCase(lm, engine, id, c)) {
                    Outcome.PASS -> {
                        pass++
                        fPass++
                    }
                    Outcome.FAIL -> {
                        fail++
                        failures.append(
                            "FAIL  [${c.file}] ${c.group}/${c.name}: ${oneLine(c.expression)}" +
                                "  expected=${c.outputs.joinToString(" | ").ifEmpty { "<none>" }}\n"
                        )
                    }
                    Outcome.ERROR -> {
                        errored++
                        failures.append(
                            "ERROR [${c.file}] ${c.group}/${c.name}: ${oneLine(c.expression)}\n"
                        )
                    }
                }
            }
            perFile.append("%-46s %4d/%-4d\n".format(xml.name, fPass, cases.size))
        }

        val summary = buildString {
            append("=== cql-engine-harness conformance run ===\n")
            append("dir: ${dir.absolutePath}\n")
            append("total=$total  pass=$pass  fail=$fail  error=$errored")
            if (total > 0) append("  (${(pass * 1000 / total) / 10.0}% pass)")
            append("\n\n--- per file (pass/total) ---\n")
            append(perFile)
            append("\n--- failures & errors ---\n")
            append(failures)
        }

        val out = File(layoutBuildDir(), "reports/cql-conformance.txt")
        out.parentFile.mkdirs()
        out.writeText(summary)
        println(
            "cql-engine-harness: total=$total pass=$pass fail=$fail error=$errored -> ${out.absolutePath}"
        )
    }

    private fun evaluateCase(
        lm: LibraryManager,
        engine: CqlEngine,
        id: VersionedIdentifier,
        c: Case,
    ): Outcome {
        val errors = mutableListOf<CqlCompilerException>()
        val compiled =
            try {
                lm.resolveLibrary(id, errors).library
            } catch (t: Throwable) {
                null
            }
        val hasCompileError =
            compiled == null || errors.any { it.severity == CqlCompilerException.ErrorSeverity.Error }

        if (c.invalid) {
            if (hasCompileError) return Outcome.PASS
            return try {
                eval(engine, id, listOf("actual"))
                Outcome.FAIL // expected an error, got a value
            } catch (t: Throwable) {
                Outcome.PASS
            }
        }

        if (hasCompileError) return Outcome.ERROR
        val results =
            try {
                eval(engine, id, if (c.outputs.isEmpty()) listOf("actual") else listOf("actual", "expected"))
            } catch (t: Throwable) {
                return Outcome.ERROR
            }

        if (c.outputs.isEmpty()) return Outcome.PASS // no expected output: must merely not error
        return try {
            if (EquivalentEvaluator.equivalent(results["actual"], results["expected"], engine.state).value)
                Outcome.PASS
            else Outcome.FAIL
        } catch (t: Throwable) {
            Outcome.ERROR
        }
    }

    private fun eval(
        engine: CqlEngine,
        id: VersionedIdentifier,
        names: List<String>,
    ): Map<String, Value?> {
        val res = engine.evaluate { library(id) { expressions(names) } }.onlyResultOrThrow
        return names.associateWith { res[it]?.value }
    }

    private fun buildCql(libId: String, c: Case): String {
        val defs = StringBuilder("define \"actual\": (${c.expression})\n")
        if (!c.invalid && c.outputs.isNotEmpty()) {
            val expected =
                if (c.outputs.size == 1) c.outputs[0] else "{ ${c.outputs.joinToString(", ")} }"
            defs.append("define \"expected\": ($expected)\n")
        }
        return "library \"$libId\" version '1.0.0'\n\n$defs"
    }

    private fun parse(xml: File): List<Case> {
        val doc =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(xml)
        val cases = mutableListOf<Case>()
        val tests = doc.getElementsByTagName("test")
        for (t in 0 until tests.length) {
            val test = tests.item(t) as Element
            val exprEl = firstChild(test, "expression") ?: continue
            val expression = exprEl.textContent?.trim().orEmpty()
            if (expression.isEmpty()) continue
            val groupName = (test.parentNode as? Element)?.getAttribute("name").orEmpty()
            val invalid =
                isInvalid(test.getAttribute("invalid")) || isInvalid(exprEl.getAttribute("invalid"))
            cases.add(
                Case(xml.name, groupName, test.getAttribute("name"), expression, childTexts(test, "output"), invalid)
            )
        }
        return cases
    }

    private fun firstChild(parent: Element, tag: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n is Element && n.tagName == tag) return n
        }
        return null
    }

    private fun childTexts(parent: Element, tag: String): List<String> {
        val out = mutableListOf<String>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n is Element && n.tagName == tag) out.add(n.textContent.trim())
        }
        return out
    }

    private fun isInvalid(attr: String?): kotlin.Boolean = !attr.isNullOrEmpty() && attr != "false"

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(160)

    private fun resolveTestDir(): File {
        System.getenv("CQL_TESTS_DIR")?.let { return File(it) }
        System.getProperty("cqlTestsDir")?.let { return File(it) }
        return File("cql-tests/tests/cql")
    }

    private fun layoutBuildDir(): File = File(System.getProperty("cqlHarness.buildDir", "build"))
}
