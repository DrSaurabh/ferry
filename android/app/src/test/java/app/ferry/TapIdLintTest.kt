package app.ferry

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every clickable in `ui/` passes a tap id (design §7.1, H74, plan CP111): each line with `onClick =`, `.clickable`,
 * `.tvClickable(` or `onCheckedChange =` must use `onClickLogged(` / `TapLog.tap(` / `tvClickable(id = …)` on that
 * line, or carry a `// tap-id: <id>` comment saying where the id is logged.
 */
class TapIdLintTest {
    private val clickable = Regex("""onClick\s*=|\.clickable\b|\.tvClickable\(|onCheckedChange\s*=|combinedClickable|onLongClick\s*=""")
    private val logged = Regex("""onClickLogged\(|TapLog\.tap\(|tvClickable\(id = |// tap-id: \S""")

    @Test fun everyClickableInUiHasATapId() {
        val files = RepoFiles.kotlinFiles("android/app/src/main/java/app/ferry/ui")
        assertTrue("no ui files found", files.size > 20)
        val bad = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("import ")) return@mapIndexedNotNull null
                if (clickable.containsMatchIn(line) && !logged.containsMatchIn(line)) "${f.name}:${i + 1}: $t" else null
            }
        }
        assertTrue("clickables without a tap id (use onClickLogged(\"<id>\") { … }):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theRuleCatchesAPlainButton() {
        assertTrue(clickable.containsMatchIn("""Button(onClick = { go() }) { Text("x") }"""))
        assertTrue(!logged.containsMatchIn("""Button(onClick = { go() }) { Text("x") }"""))
        assertTrue(logged.containsMatchIn("""Button(onClick = onClickLogged("home.go") { go() })"""))
        assertTrue(clickable.containsMatchIn("""Card(Modifier.clickable { open() })"""))
    }
}
