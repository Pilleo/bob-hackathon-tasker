package io.agentdevkit.planner

/** Explicit output contract supplied on both initial review and its single correction attempt. */
internal object ReviewResponseContract {
    val instructions = """
        Return only a JSON object, without Markdown or commentary. All keys below are required:
        {"verdict":"NEEDS_CLARIFICATION","summary":"Resolve the open decision","findings":[],"questions":[{"question":"Should null be rejected?","blocks":"S1","kind":"decision"}]}
        verdict is exactly ACCEPT, CHANGES_REQUESTED, or NEEDS_CLARIFICATION. summary is nonblank text.
        findings is an array of objects with id, category, severity, blocking, detail, resolution, evidence.
        id/category/severity/detail/resolution are strings; blocking is a JSON boolean; evidence is an array of strings.
        questions is an array of objects with question, blocks, kind; all three fields are nonblank strings.
        ACCEPT requires zero blocking findings AND an empty questions array. If something blocks implementation, choose CHANGES_REQUESTED or NEEDS_CLARIFICATION instead.
        Missing source/impact evidence is unknown, not proof of safety. Do not mark missing evidence as clean.
        Use [] for absent findings or questions. Never include placeholder questions in an ACCEPT verdict.
    """.trimIndent()
}
