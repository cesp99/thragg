(create_table
  (keyword_create) @context
  (keyword_table) @context
  (object_reference
    name: (identifier) @name)) @item

(create_view
  (keyword_create) @context
  (keyword_view) @context
  (object_reference
    name: (identifier) @name)) @item

(create_index
  (keyword_create) @context
  (keyword_index) @context
  (identifier) @name) @item

(create_function
  (keyword_create) @context
  (keyword_function) @context
  (object_reference
    name: (identifier) @name)) @item

(create_type
  (keyword_create) @context
  (keyword_type) @context
  (object_reference
    name: (identifier) @name)) @item

(column_definition
  name: (identifier) @name) @item
