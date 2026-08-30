; tree-sitter-svelte-ng's own queries/injections.scm (MIT), minus the `pug`
; rule — we carry no pug grammar. See docs/THIRD_PARTY.md.
((comment) @injection.content
  (#set! injection.language "comment"))

((style_element
  (start_tag
    (attribute
      (attribute_name) @_attr
      (quoted_attribute_value
        (attribute_value) @_lang)))
  (raw_text) @injection.content)
  (#eq? @_attr "lang")
  (#any-of? @_lang "scss" "postcss" "less")
  (#set! injection.language "scss"))

((style_element
  (raw_text) @injection.content)
  (#set! injection.language "css"))

((script_element
  (start_tag
    (attribute
      (attribute_name) @_attr
      (quoted_attribute_value
        (attribute_value) @_lang)))
  (raw_text) @injection.content)
  (#eq? @_attr "lang")
  (#any-of? @_lang "ts" "typescript")
  (#set! injection.language "typescript"))

((script_element
  (raw_text) @injection.content)
  (#set! injection.language "javascript"))

((svelte_raw_text) @injection.content
  (#set! injection.language "javascript"))
