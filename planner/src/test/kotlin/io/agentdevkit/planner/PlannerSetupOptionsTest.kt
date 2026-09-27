package io.agentdevkit.planner

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlannerSetupOptionsTest {
    @Test fun `named agent keeps ordered startup arguments with flag-like values`() {
        val result = PlannerSetupOptions.parse(listOf("--arg", "--stdio", "--name", "sample", "--agent", "/bin/true",
            "--arg", "--profile=fixture", "--model", "custom"), allowDiscovery = false)
        val options = assertIs<SetupOptionsResult.Valid>(result).options
        assertEquals("sample", options.name)
        assertEquals("/bin/true", options.agent)
        assertEquals(listOf("--stdio", "--profile=fixture"), options.arguments)
        assertEquals("custom", options.model)
    }

    @Test fun `discovery is explicit and duplicate switches are rejected`() {
        assertEquals("antigravity", assertIs<SetupOptionsResult.Valid>(
            PlannerSetupOptions.parse(emptyList(), allowDiscovery = true)).options.name)
        assertIs<SetupOptionsResult.Invalid>(PlannerSetupOptions.parse(emptyList(), allowDiscovery = false))
        assertIs<SetupOptionsResult.Invalid>(PlannerSetupOptions.parse(listOf("--name", "first", "--name", "second",
            "--agent", "/bin/true"), allowDiscovery = false))
        assertIs<SetupOptionsResult.Invalid>(PlannerSetupOptions.parse(listOf("--name", "custom"), allowDiscovery = true))
    }
}
