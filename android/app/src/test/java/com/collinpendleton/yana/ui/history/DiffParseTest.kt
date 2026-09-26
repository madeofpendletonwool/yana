package com.collinpendleton.yana.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parser sees the unified diffs the server's `git diff --no-color` writes. */
class DiffParseTest {
    @Test fun headersDropAndHunksStay() {
        val diff = """
            diff --git a/work/a.md b/work/a.md
            index 1a2b3c4..5d6e7f8 100644
            --- a/work/a.md
            +++ b/work/a.md
            @@ -1,4 +1,4 @@
             keep
            -old
            +new
             keep two
            @@ -10,3 +10,4 @@
             tail
            +added tail
            \ No newline at end of file
        """.trimIndent()

        val lines = DiffParser.parse(diff)
        assertEquals(
            listOf(
                DiffLine.Hunk("@@ -1,4 +1,4 @@"),
                DiffLine.Body(' ', "keep"),
                DiffLine.Body('-', "old"),
                DiffLine.Body('+', "new"),
                DiffLine.Body(' ', "keep two"),
                DiffLine.Hunk("@@ -10,3 +10,4 @@"),
                DiffLine.Body(' ', "tail"),
                DiffLine.Body('+', "added tail"),
                DiffLine.Body('\\', " No newline at end of file"),
            ),
            lines,
        )
    }

    @Test fun countsTallyAddsAndDeletes() {
        val diff = """
            --- a/x.md
            +++ b/x.md
            @@ -1,2 +1,3 @@
             ctx
            -gone
            +here
            +also here
        """.trimIndent()
        assertEquals(2 to 1, diffCounts(diff))
    }

    @Test fun emptyDiffParsesToNothing() {
        assertTrue(DiffParser.parse("").isEmpty())
        assertEquals(0 to 0, diffCounts(""))
    }

    @Test fun textBeforeTheFirstHunkIsSkipped() {
        val lines = DiffParser.parse("diff --git a/x b/x\nindex abc..def\ntext without a hunk")
        assertTrue(lines.isEmpty())
    }
}
