; Trimmed from tree-sitter-make's own queries/highlights.scm (MIT) onto Zed's
; capture vocabulary — the upstream query names Neovim scopes (@conditional,
; @include, @exception) that no syntax theme here has. See docs/THIRD_PARTY.md.
(comment) @comment

(targets
  (word) @function)

(variable_assignment
  name: (word) @property)

(variable_reference
  (word) @variable)

(automatic_variable) @variable.special

(function_call
  function: _ @function)

[
  (text)
  (string)
  (raw_text)
] @string

(shell_text) @embedded

[
  "ifeq"
  "ifneq"
  "ifdef"
  "ifndef"
  "else"
  "endif"
  "if"
  "or"
  "and"
  "foreach"
  "define"
  "endef"
  "vpath"
  "undefine"
  "export"
  "unexport"
  "override"
  "private"
  "include"
  "sinclude"
  "-include"
] @keyword

[
  "="
  ":="
  "::="
  "?="
  "+="
  "!="
  "@"
  "-"
  "+"
] @operator

[
  "("
  ")"
  "{"
  "}"
] @punctuation.bracket

[
  ":"
  "&:"
  "::"
  "|"
  ";"
  ","
] @punctuation.delimiter

[
  "$"
  "$$"
] @punctuation.special
