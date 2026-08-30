(start_tag
  ">" @end) @indent

(self_closing_tag
  "/>" @end) @indent

(element
  (start_tag) @start
  (end_tag)? @end) @indent

(if_statement
  (if_end)? @end) @indent

(each_statement
  (each_end)? @end) @indent

(await_statement
  (await_end)? @end) @indent

(key_statement
  (key_end)? @end) @indent

(snippet_statement
  (snippet_end)? @end) @indent
