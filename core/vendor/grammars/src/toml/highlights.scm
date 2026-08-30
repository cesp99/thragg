; Adapted from tree-sitter-toml-ng's own queries/highlights.scm (MIT) onto
; Zed's syntax key set — see docs/THIRD_PARTY.md.
(comment) @comment

(table
  [
    (bare_key)
    (dotted_key)
  ] @type)

(table_array_element
  [
    (bare_key)
    (dotted_key)
  ] @type)

(pair
  [
    (bare_key)
    (dotted_key)
    (quoted_key)
  ] @property)

(string) @string

(escape_sequence) @string.escape

(boolean) @boolean

[
  (integer)
  (float)
] @number

[
  (offset_date_time)
  (local_date_time)
  (local_date)
  (local_time)
] @string.special

"=" @operator

[
  "."
  ","
] @punctuation.delimiter

[
  "["
  "]"
  "[["
  "]]"
  "{"
  "}"
] @punctuation.bracket
