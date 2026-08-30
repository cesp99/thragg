; A recipe line is a shell command — the reason `make` files are worth an
; injection at all (Zed's Make extension does the same).
((recipe) @injection.content
  (#set! injection.language "bash"))

((shell_text) @injection.content
  (#set! injection.language "bash"))
