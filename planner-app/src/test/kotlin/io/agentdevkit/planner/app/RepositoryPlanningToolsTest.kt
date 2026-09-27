package io.agentdevkit.planner.app

import io.agentdevkit.planner.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class RepositoryPlanningToolsTest {
    @Test fun `identical declaration text at different AST ranges remains ambiguous`(@TempDir root: File) {
        root.resolve("Two.kt").writeText("class A { fun validate() = true }\nclass B { fun validate() = true }\n")
        val matches = """[{"file":"Two.kt","text":"fun validate() = true","range":{"byteOffset":{"start":10,"end":31}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}},{"file":"Two.kt","text":"fun validate() = true","range":{"byteOffset":{"start":44,"end":65}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}}]"""
        val tools = RepositoryPlanningTools { args, _, _ ->
            if (args[3] == "fun \$NAME") CommandResult(0, matches) else CommandResult(1, "[]", "")
        }
        val result = tools.source(root, TargetRef("Two.kt", "validate", TargetKind.EXISTING))
        assertEquals(PlanningStatus.UNAVAILABLE, result.status)
        assertTrue(result.issues.any { it.code == "TARGET_AMBIGUOUS" })
    }

    @Test fun `new evidence collection gets a fresh deadline after agent delay`(@TempDir root: File) {
        var now = 1_000_000_000L
        val tools = RepositoryPlanningTools(budgetMs = 100, clock = { now }) { args, _, _ ->
            assertEquals("git", args.first())
            CommandResult(0, "0123456789abcdef0123456789abcdef01234567\n")
        }
        assertNotNull(tools.revision(root))
        now += TimeUnit.MILLISECONDS.toNanos(150)
        assertNull(tools.revision(root), "One evidence pass still has a bounded lifetime")
        assertNotNull(tools.freshCollection().revision(root), "Agent time must not exhaust the next evidence pass")
    }

    @Test fun `overall evidence deadline stops further AST commands`(@TempDir root: File) {
        root.resolve("Validator.kt").writeText("fun validate() = true")
        var now = 1_000_000_000L
        var commands = 0
        val tools = RepositoryPlanningTools(budgetMs = 100, clock = { now }) { _, _, _ ->
            commands++
            now += TimeUnit.MILLISECONDS.toNanos(101)
            CommandResult(0, """[{"file":"Validator.kt","text":"fun validate() = true","range":{"byteOffset":{"start":0,"end":21}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}}]""")
        }
        val source = tools.source(root, TargetRef("Validator.kt", "validate", TargetKind.EXISTING))
        assertEquals(PlanningStatus.UNAVAILABLE, source.status)
        assertEquals(1, commands)
        assertContains(source.issues.joinToString { it.message }, "timed out")
    }

    @Test fun `unmatched declaration patterns do not discard a verified function`(@TempDir root: File) {
        root.resolve("Validator.kt").writeText("fun validate() = true")
        val tools = RepositoryPlanningTools { args, _, _ ->
            if (args[3] == "fun \$NAME") CommandResult(0,
                """[{"file":"Validator.kt","text":"fun validate() = true","range":{"byteOffset":{"start":0,"end":21}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}}]""")
            else CommandResult(1, "[]", "") // ast-grep's ordinary no-match result
        }
        val result = tools.source(root, TargetRef("Validator.kt", "validate", TargetKind.EXISTING))
        assertEquals(PlanningStatus.OK, result.status, result.issues.toString())
        assertEquals("PARSER_VERIFIED", result.trust)
    }

    @Test fun `exact AST declaration and Codanna file-qualified identity provide verified impact`(@TempDir root: File) {
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("class Validator { fun validate() = true }") }
        val calls = mutableListOf<List<String>>()
        val tools = RepositoryPlanningTools { args, _, _ ->
            calls += args
            when {
                args.first() == "ast-grep" -> CommandResult(0, """[{"file":"src/Validator.kt","text":"fun validate() = true","range":{"byteOffset":{"start":0,"end":21}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}}]""")
                "semantic_search_docs" in args -> CommandResult(0, """{"data":[{"symbol":{"name":"validate","file_path":"src/Validator.kt"}}]}""")
                "search_symbols" in args -> CommandResult(0, """{"data":[{"symbol":{"id":11,"name":"validate","file_path":"other/Validator.kt"}},{"symbol":{"id":42,"name":"validate","file_path":"src/Validator.kt"}}]}""")
                "analyze_impact" in args -> CommandResult(0, """{"data":[{"name":"ValidatorController.submit"}]}""")
                args.first() == "git" -> CommandResult(0, "0123456789abcdef0123456789abcdef01234567\n")
                else -> error("Unexpected command: $args")
            }
        }
        val target = TargetRef("src/Validator.kt", "validate", TargetKind.EXISTING)
        assertEquals("PARSER_VERIFIED", tools.source(root, target).trust)
        val impact = tools.impact(root, target)
        assertEquals(PlanningStatus.OK, impact.status, impact.issues.toString())
        assertContains(impact.symbols, "ValidatorController.submit")
        assertTrue(calls.any { "symbol_id:42" in it })
        assertEquals("0123456789abcdef0123456789abcdef01234567", tools.revision(root))
        val candidates = tools.candidates(root, "reject invalid names")
        assertEquals(PlanningStatus.OK, candidates.status, candidates.issues.toString())
        assertEquals(listOf(target), candidates.candidates.map { it.target })
    }

    @Test fun `same-name overloads stay ambiguous and unavailable tools never pass as clean evidence`(@TempDir root: File) {
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val tools = RepositoryPlanningTools { args, _, _ ->
            if (args.first() == "ast-grep") CommandResult(0, """[{"file":"src/Validator.kt","text":"fun validate() = true","range":{"byteOffset":{"start":0,"end":21}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}}]""")
            else if ("search_symbols" in args) CommandResult(0, """{"data":[{"symbol":{"id":1,"name":"validate","file_path":"src/Validator.kt"}},{"symbol":{"id":2,"name":"validate","file_path":"src/Validator.kt"}}]}""")
            else CommandResult(null, "", "missing tool")
        }
        val impact = tools.impact(root, TargetRef("src/Validator.kt", "validate", TargetKind.EXISTING))
        assertEquals(PlanningStatus.UNAVAILABLE, impact.status)
        assertTrue(impact.issues.any { it.code == "TARGET_AMBIGUOUS" })
        assertEquals(PlanningStatus.UNAVAILABLE, tools.candidates(root, "validate input").status)
    }

    @Test fun `matching declaration from another file cannot verify the requested target`(@TempDir root: File) {
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val tools = RepositoryPlanningTools { args, _, _ ->
            if (args.first() == "ast-grep") CommandResult(0,
                """[{"file":"src/Other.kt","text":"fun validate() = true","range":{"byteOffset":{"start":0,"end":21}},"metaVariables":{"single":{"NAME":{"text":"validate"}}}}]""")
            else CommandResult(null, "", "unavailable")
        }
        val result = tools.source(root, TargetRef("src/Validator.kt", "validate", TargetKind.EXISTING))
        assertEquals(PlanningStatus.UNAVAILABLE, result.status)
        assertNotEquals("PARSER_VERIFIED", result.trust)
    }
}
