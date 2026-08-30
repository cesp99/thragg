(rule_set
  (selectors) @name) @item

(mixin_statement
  "@mixin" @context
  name: (identifier) @name) @item

(function_statement
  "@function" @context
  name: (identifier) @name) @item

(keyframes_statement
  "@keyframes" @context
  (keyframes_name) @name) @item

(declaration
  (variable) @name) @item
