//! Compile the vendored Dockerfile parser, the way every generated
//! tree-sitter binding's own `build.rs` does.

fn main() {
    let dir = std::path::Path::new("grammar");
    let mut build = cc::Build::new();
    build
        .include(dir)
        .flag_if_supported("-Wno-unused-parameter")
        .flag_if_supported("-Wno-unused-but-set-variable")
        .flag_if_supported("-Wno-trigraphs")
        .file(dir.join("parser.c"))
        .file(dir.join("scanner.c"));
    build.compile("tree-sitter-dockerfile");

    println!("cargo:rerun-if-changed=grammar/parser.c");
    println!("cargo:rerun-if-changed=grammar/scanner.c");
    println!("cargo:rerun-if-changed=grammar/tree_sitter/parser.h");
}
