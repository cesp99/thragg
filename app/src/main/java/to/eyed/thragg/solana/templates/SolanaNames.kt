package to.eyed.thragg.solana.templates

/**
 * The three names a Solana project needs, derived from the one the user typed.
 *
 * A project is a directory, and [to.eyed.thragg.core.ProjectsRoot] is
 * happy with almost anything that is not a path — "My Project" is a perfectly
 * good folder. Cargo is not so relaxed: a package name is kebab-case, a `[lib]`
 * target name has to be a Rust identifier (so snake_case, no leading digit, not
 * a keyword), and Anchor's TypeScript client names the IDL type in PascalCase.
 * Scaffolding "My Project" therefore has to produce `my-project`, `my_project`
 * and `MyProject`, and it has to do it the same way every time or the four
 * files that mention the program disagree with each other.
 *
 * Deriving rather than rejecting, unlike `ProjectsRoot.nameError`: there the
 * name *is* the thing being made, so silently changing it would be a lie; here
 * the crate name is a detail of the scaffold, and refusing "My Project" because
 * cargo dislikes the space would be refusing a name the filesystem accepts.
 * What is refused is only a name with nothing usable left in it at all — "…" —
 * and [error] says so before the dialog lets Create through.
 */
object SolanaNames {

    /**
     * Rust's strict and reserved keywords (2015 through 2024 editions).
     *
     * A `[lib] name` that is one of these produces `pub mod match { … }`, which
     * does not compile — and the failure arrives at the end of a several-minute
     * SBF build rather than in the dialog. Reserved words are in the list too:
     * they are not keywords *yet*, but a project scaffolded today outlives the
     * edition it was scaffolded in.
     */
    private val RUST_KEYWORDS = setOf(
        "as", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop",
        "match", "mod", "move", "mut", "pub", "ref", "return", "self", "static",
        "struct", "super", "trait", "true", "type", "unsafe", "use", "where",
        "while", "async", "await", "gen", "try", "union",
        // Reserved for future use.
        "abstract", "become", "box", "do", "final", "macro", "override", "priv",
        "typeof", "unsized", "virtual", "yield",
    )

    /**
     * The words of a name: ASCII alphanumeric runs, with camelCase split.
     *
     * Anything else — a space, an emoji, a hyphen, an accented letter — is a
     * boundary, which is what makes "My Project", "my-project" and "MyProject"
     * all land on the same crate. The camelCase split is the one piece of
     * cleverness and it earns its place: without it `MyProject` becomes the
     * single word `myproject`, and a scaffold nobody would have named by hand.
     */
    private fun words(displayName: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var previous = ' '
        for (char in displayName) {
            val boundary = char.code < 128 &&
                char.isUpperCase() &&
                (previous.isLowerCase() || previous.isDigit())
            if (boundary && current.isNotEmpty()) {
                words += current.toString()
                current.clear()
            }
            if (char.code < 128 && (char.isLetterOrDigit())) current.append(char.lowercaseChar())
            else if (current.isNotEmpty()) {
                words += current.toString()
                current.clear()
            }
            previous = char
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    /**
     * A leading digit is legal in a directory name and illegal in a Rust
     * identifier, so `2048` would otherwise scaffold a program that cannot be
     * declared. `program_` in front is what a person would have typed.
     */
    private fun usableWords(displayName: String): List<String> {
        val words = words(displayName)
        return when {
            words.isEmpty() -> emptyList()
            words.first().first().isDigit() -> listOf("program") + words
            words.size == 1 && words.first() in RUST_KEYWORDS -> words + "program"
            else -> words
        }
    }

    /** The cargo package name: `my-project`. */
    fun crateName(displayName: String): String = usableWords(displayName).joinToString("-")

    /**
     * The `[lib] name`, the `#[program]` module and the Python module: the
     * one of the three that has to be a valid Rust identifier.
     */
    fun moduleName(displayName: String): String = usableWords(displayName).joinToString("_")

    /**
     * The IDL type name Anchor's TypeScript client generates from the module —
     * `my_project` becomes `MyProject`, which is also the `anchor.workspace`
     * key the test file looks the program up under.
     */
    fun typeName(displayName: String): String =
        usableWords(displayName).joinToString("") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }

    /**
     * Why [displayName] cannot become a program, or null if it can.
     *
     * Only the one failure the derivation cannot paper over: a name with no
     * ASCII letters or digits anywhere in it. `ProjectsRoot.nameError` has
     * already had its say about the directory by the time this is asked.
     */
    fun error(displayName: String): String? =
        if (usableWords(displayName).isEmpty()) {
            "Name needs at least one letter or digit a crate can be named after"
        } else {
            null
        }
}
