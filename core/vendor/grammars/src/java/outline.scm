(class_declaration
  "class" @context
  name: (identifier) @name) @item

(interface_declaration
  "interface" @context
  name: (identifier) @name) @item

(enum_declaration
  "enum" @context
  name: (identifier) @name) @item

(record_declaration
  "record" @context
  name: (identifier) @name) @item

(annotation_type_declaration
  "@interface" @context
  name: (identifier) @name) @item

(method_declaration
  type: (_) @context
  name: (identifier) @name
  parameters: (formal_parameters) @context) @item

(constructor_declaration
  name: (identifier) @name
  parameters: (formal_parameters) @context) @item

(field_declaration
  type: (_) @context
  declarator: (variable_declarator
    name: (identifier) @name)) @item
