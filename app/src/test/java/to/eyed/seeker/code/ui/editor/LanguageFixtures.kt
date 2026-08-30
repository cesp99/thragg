package to.eyed.seeker.code.ui.editor

/**
 * The configs `CoreBridge.languageConfig` hands back, verbatim, for the
 * languages these tests use.
 *
 * They are copies, and deliberately so. What they are copies *of* is the only
 * source of truth there is — `core/vendor/grammars/src/<language>/config.toml`
 * — and that the engine reads it faithfully is proved on the Rust side, in
 * `engine::language_config::tests`, against the real embedded files. What is
 * left for this side is that the editor *obeys* a config, which is what these
 * fixtures feed it. Regenerate one by printing
 * `engine::language_config_json("<language>")`.
 */
internal object LanguageFixtures {

    fun of(language: String?): String? = when (language) {
        "rust" -> Rust
        "python" -> Python
        "json" -> Json
        "bash" -> Bash
        "go" -> Go
        "markdown" -> Markdown
        "css" -> Css
        "yaml" -> Yaml
        "diff" -> Diff
        else -> null
    }

    const val Rust =
        """{"name":"Rust","line_comments":["// ","/// ","//! "],"block_comment":{"start":"/*","end":"*/","prefix":"* ","tab_size":1},"autoclose_before":";:.,=}])>","hard_tabs":false,"tab_size":null,"increase_indent_pattern":null,"brackets":[{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"r#\"","end":"\"#","close":true,"surround":true,"newline":true,"not_in":["string","comment"]},{"start":"r##\"","end":"\"##","close":true,"surround":true,"newline":true,"not_in":["string","comment"]},{"start":"r###\"","end":"\"###","close":true,"surround":true,"newline":true,"not_in":["string","comment"]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"<","end":">","close":false,"surround":true,"newline":true,"not_in":["string","comment"]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string"]},{"start":"`","end":"`","close":false,"surround":true,"newline":false,"not_in":["string"]},{"start":"/*","end":" */","close":true,"surround":true,"newline":false,"not_in":["string","comment"]}]}"""

    const val Python =
        """{"name":"Python","line_comments":["# "],"block_comment":{"start":"\"\"\"","end":"\"\"\"","prefix":"","tab_size":1},"autoclose_before":";:.,=}])>","hard_tabs":false,"tab_size":null,"increase_indent_pattern":"^[^#].*:\\s*(#.*)?${'$'}","brackets":[{"start":"f\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"f'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"b\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"b'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"u\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"u'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"r\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"r'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"rb\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"rb'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"t\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"t'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"\"\"\"","end":"\"\"\"","close":true,"surround":true,"newline":false,"not_in":["string"]},{"start":"'''","end":"'''","close":true,"surround":true,"newline":false,"not_in":["string"]},{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string"]},{"start":"'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string"]}]}"""

    const val Json =
        """{"name":"JSON","line_comments":["// "],"block_comment":null,"autoclose_before":",]}","hard_tabs":false,"tab_size":2,"increase_indent_pattern":null,"brackets":[{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":false,"not_in":[]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string"]}]}"""

    const val Bash =
        """{"name":"Shell Script","line_comments":["# "],"block_comment":null,"autoclose_before":"}])","hard_tabs":false,"tab_size":null,"increase_indent_pattern":"^\\s*(\\b(else|elif)\\b|([^#]+\\b(do|then|in)\\b)|([\\w\\*]+\\)))\\s*${'$'}","brackets":[{"start":"[","end":"]","close":true,"surround":true,"newline":false,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["comment","string"]},{"start":"'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"do","end":"done","close":false,"surround":true,"newline":true,"not_in":["comment","string"]},{"start":"then","end":"fi","close":false,"surround":true,"newline":true,"not_in":["comment","string"]},{"start":"then","end":"else","close":false,"surround":true,"newline":true,"not_in":["comment","string"]},{"start":"then","end":"elif","close":false,"surround":true,"newline":true,"not_in":["comment","string"]},{"start":"in","end":"esac","close":false,"surround":true,"newline":true,"not_in":["comment","string"]}]}"""

    const val Go =
        """{"name":"Go","line_comments":["// "],"block_comment":{"start":"/*","end":"*/","prefix":"","tab_size":1},"autoclose_before":";:.,=}])>","hard_tabs":true,"tab_size":4,"increase_indent_pattern":null,"brackets":[{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["comment","string"]},{"start":"'","end":"'","close":true,"surround":true,"newline":false,"not_in":["comment","string"]},{"start":"`","end":"`","close":true,"surround":true,"newline":false,"not_in":["comment","string"]},{"start":"/*","end":" */","close":true,"surround":true,"newline":false,"not_in":["comment","string"]}]}"""

    const val Markdown =
        """{"name":"Markdown","line_comments":[],"block_comment":{"start":"<!--","end":"-->","prefix":"","tab_size":1},"autoclose_before":";:.,=}])>","hard_tabs":false,"tab_size":2,"increase_indent_pattern":null,"brackets":[{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"<","end":">","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"\"","end":"\"","close":false,"surround":true,"newline":false,"not_in":[]},{"start":"'","end":"'","close":false,"surround":true,"newline":false,"not_in":[]},{"start":"`","end":"`","close":false,"surround":true,"newline":false,"not_in":[]},{"start":"*","end":"*","close":false,"surround":true,"newline":false,"not_in":[]},{"start":"~","end":"~","close":false,"surround":true,"newline":false,"not_in":[]}]}"""

    const val Css =
        """{"name":"CSS","line_comments":[],"block_comment":{"start":"/*","end":"*/","prefix":"* ","tab_size":1},"autoclose_before":";:.,=}])>","hard_tabs":false,"tab_size":null,"increase_indent_pattern":null,"brackets":[{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"(","end":")","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string","comment"]},{"start":"'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string","comment"]}]}"""

    const val Yaml =
        """{"name":"YAML","line_comments":["# "],"block_comment":null,"autoclose_before":",]}","hard_tabs":false,"tab_size":2,"increase_indent_pattern":"(:?^[^#]*:\\s*[|>]?\\s*${'$'})|(:?^\\s*-[^#]*:\\s*(:?#+.*)${'$'})","brackets":[{"start":"{","end":"}","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"[","end":"]","close":true,"surround":true,"newline":true,"not_in":[]},{"start":"\"","end":"\"","close":true,"surround":true,"newline":false,"not_in":["string"]},{"start":"'","end":"'","close":true,"surround":true,"newline":false,"not_in":["string"]}]}"""

    const val Diff =
        """{"name":"Diff","line_comments":[],"block_comment":null,"autoclose_before":"","hard_tabs":false,"tab_size":null,"increase_indent_pattern":null,"brackets":[]}"""
}
