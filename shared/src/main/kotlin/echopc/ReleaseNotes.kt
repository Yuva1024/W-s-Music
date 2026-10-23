package echopc

/** Release notes as plain text: GitHub writes them in Markdown (**bold**, `code`, # headings, - lists). */
fun plainReleaseNotes(markdown: String): String =
    markdown.lines().joinToString("\n") { line ->
        line.replace("**", "").replace("`", "")
            .replace(Regex("""^#+\s*"""), "")
            .replace(Regex("""^(\s*)[-*] """), "$1• ")
    }.replace(Regex("""\n{3,}"""), "\n\n").trim()
