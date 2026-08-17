
fn main() {
    let mut build = cc::Build::new();

    let patterns = [
        "c-code/**/*.c",
    ];

    for pattern in patterns {
        for entry in glob::glob(pattern).expect("Failed to read glob pattern") {
            let path = entry.expect("Failed to read C file path");
            println!("cargo:rerun-if-changed={}", path.display());
            build.file(path);
        }
    }

    println!("cargo:rerun-if-changed=src/evs.c");

    build
        .include("c")
        .include("c-code/lib_enc")
        .include("c-code/lib_dec")
        .include("c-code/lib_com")
        .file("src/evs.c")
        .compile("evs_coder");
}