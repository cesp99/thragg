; tree-sitter-scss's own queries/highlights.scm (MIT), renamed onto Zed's key
; set and filled in from Zed's css/highlights.scm for the parts SCSS shares
; with CSS — the upstream query covers only the SCSS-specific statements.
; See docs/THIRD_PARTY.md.
[
  (comment)
  (js_comment)
] @comment

[
  (tag_name)
  (nesting_selector)
  (universal_selector)
] @tag

(id_name) @constant
(class_name) @attribute
(namespace_name) @type
(placeholder) @attribute
(feature_name) @property
(property_name) @property
(attribute_name) @attribute
(keyframes_name) @constructor

(function_name) @function
(keyword_query) @function

(mixin_statement
  name: (identifier) @function)

(function_statement
  name: (identifier) @function)

(include_statement
  (identifier) @function)

(call_expression
  (function_name) @function)

(pseudo_class_selector
  (class_name) @constructor)

(pseudo_element_selector
  (tag_name) @constructor)

(variable) @variable
(identifier) @variable

[
  (parameter)
  (argument)
] @variable.special

(string_value) @string
(color_value) @string.special

[
  (integer_value)
  (float_value)
] @number

(unit) @type

(important) @keyword

(at_keyword) @keyword

[
  "@at-root"
  "@debug"
  "@error"
  "@extend"
  "@forward"
  "@function"
  "@include"
  "@mixin"
  "@return"
  "@use"
  "@warn"
  "@while"
  "@each"
  "@for"
  "@media"
  "@import"
  "@charset"
  "@namespace"
  "@keyframes"
  "@supports"
  "@if"
  "@else"
  "from"
  "through"
  "in"
  (to)
  "and"
  "or"
  "not"
  "only"
] @keyword

[
  "~"
  ">"
  "+"
  "-"
  "*"
  "/"
  "="
  "^="
  "|="
  "~="
  "$="
  "*="
  ">="
  "<="
] @operator

[
  "#{"
  "}"
] @punctuation.special

[
  "("
  ")"
  "["
  "]"
] @punctuation.bracket

[
  ","
  ";"
  ":"
] @punctuation.delimiter
