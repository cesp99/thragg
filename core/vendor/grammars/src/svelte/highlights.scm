; Zed's html/highlights.scm (the svelte grammar is html's, extended), plus
; tree-sitter-svelte-ng's own block-tag keywords (MIT) renamed onto Zed's key
; set — see docs/THIRD_PARTY.md.
(tag_name) @tag

(doctype) @tag

(attribute_name) @attribute

[
  "\""
  "'"
  (attribute_value)
] @string

(comment) @comment

(entity) @string.special

"=" @punctuation.delimiter

[
  "<"
  ">"
  "<!"
  "</"
  "/>"
] @punctuation.bracket

; Svelte template syntax

[
  "as"
  "key"
  "html"
  "snippet"
  "render"
  "const"
  "if"
  "else"
  "else if"
  "then"
  "each"
  "await"
  "catch"
  "debug"
] @keyword

(snippet_name) @function

[
  "{"
  "}"
] @punctuation.special

[
  "#"
  ":"
  "/"
  "@"
] @punctuation.delimiter
