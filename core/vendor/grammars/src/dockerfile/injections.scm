; A RUN line is a shell script; the Dockerfile grammar hands it over whole.
((shell_fragment) @injection.content
  (#set! injection.language "bash"))

((heredoc_line) @injection.content
  (#set! injection.language "bash"))
