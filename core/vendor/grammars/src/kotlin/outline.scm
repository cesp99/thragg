(class_declaration
  "class" @context
  name: (identifier) @name) @item

(class_declaration
  "interface" @context
  name: (identifier) @name) @item

(object_declaration
  "object" @context
  name: (identifier) @name) @item

(companion_object
  "companion" @context
  "object" @context) @item

(function_declaration
  "fun" @context
  name: (identifier) @name) @item

(property_declaration
  [
    "val"
    "var"
  ] @context
  (variable_declaration
    (identifier) @name)) @item

(type_alias
  "typealias" @context
  type: (identifier) @name) @item

(enum_entry
  (identifier) @name) @item
