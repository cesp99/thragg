; Written for tree-sitter-kotlin-ng's node names (src/node-types.json), in
; Zed's capture vocabulary — the grammar ships no queries of its own.
[
  (line_comment)
  (block_comment)
] @comment

(shebang) @comment

; Declarations

(class_declaration
  name: (identifier) @type)

(object_declaration
  name: (identifier) @type)

(companion_object
  name: (identifier) @type)

(type_alias
  type: (identifier) @type)

(enum_entry
  (identifier) @constant)

(user_type
  (identifier) @type)

(function_declaration
  name: (identifier) @function)

(call_expression
  (navigation_expression
    (identifier) @function .))

(call_expression
  .
  (identifier) @function)

(parameter
  (identifier) @variable)

(class_parameter
  (identifier) @variable)

(variable_declaration
  (identifier) @variable)

(navigation_expression
  (identifier) @property .)

(annotation) @attribute

(label) @label

; Literals

(string_literal) @string

(character_literal) @string

(multiline_string_literal) @string

(escape_sequence) @string.escape

(interpolation
  [
    "${"
    "}"
  ] @punctuation.special)

[
  (number_literal)
  (float_literal)
] @number

((identifier) @constant
  (#match? @constant "^_*[A-Z][A-Z\\d_]+$"))

; Keywords

[
  "abstract"
  "actual"
  "annotation"
  "as"
  "as?"
  "by"
  "catch"
  "class"
  "companion"
  "const"
  "constructor"
  "crossinline"
  "data"
  "do"
  "dynamic"
  "else"
  "enum"
  "expect"
  "external"
  "final"
  "finally"
  "for"
  "fun"
  "get"
  "if"
  "import"
  "in"
  "infix"
  "init"
  "inline"
  "inner"
  "interface"
  "internal"
  "is"
  "lateinit"
  "noinline"
  "object"
  "open"
  "operator"
  "out"
  "override"
  "package"
  "private"
  "protected"
  "public"
  "return"
  "return@"
  "sealed"
  "set"
  "suspend"
  "tailrec"
  "throw"
  "try"
  "typealias"
  "val"
  "value"
  "var"
  "vararg"
  "when"
  "where"
  "while"
  "!in"
  "!is"
] @keyword

[
  "this"
  "this@"
  "super"
  "super@"
] @variable.special

; Punctuation and operators

[
  ";"
  "."
  ","
  ":"
  "::"
  "?."
] @punctuation.delimiter

[
  "("
  ")"
  "["
  "]"
  "{"
  "}"
] @punctuation.bracket

[
  "!"
  "!!"
  "!="
  "!=="
  "%"
  "%="
  "&"
  "&&"
  "*"
  "*="
  "+"
  "++"
  "+="
  "-"
  "--"
  "-="
  "->"
  ".."
  "..<"
  "/"
  "/="
  "<"
  "<="
  "="
  "=="
  "==="
  ">"
  ">="
  "?"
  "?:"
  "@"
  "||"
] @operator
