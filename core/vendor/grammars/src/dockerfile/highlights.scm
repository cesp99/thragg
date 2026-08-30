; tree-sitter-dockerfile's own queries/highlights.scm (MIT), with the `@none`
; capture dropped — Zed has no such style key. See docs/THIRD_PARTY.md.
[
  "FROM"
  "AS"
  "RUN"
  "CMD"
  "LABEL"
  "EXPOSE"
  "ENV"
  "ADD"
  "COPY"
  "ENTRYPOINT"
  "VOLUME"
  "USER"
  "WORKDIR"
  "ARG"
  "ONBUILD"
  "STOPSIGNAL"
  "HEALTHCHECK"
  "SHELL"
  "MAINTAINER"
  "CROSS_BUILD"
  (heredoc_marker)
  (heredoc_end)
] @keyword

[
  ":"
  "@"
] @operator

(comment) @comment

(image_name) @type

(image_alias) @constructor

(image_spec
  (image_tag
    ":" @punctuation.special)
  (image_digest
    "@" @punctuation.special))

[
  (double_quoted_string)
  (single_quoted_string)
  (json_string)
  (heredoc_line)
] @string

(escape_sequence) @string.escape

(expansion
  [
    "$"
    "{"
    "}"
  ] @punctuation.special)

((variable) @constant
  (#match? @constant "^[A-Z][A-Z_0-9]*$"))
