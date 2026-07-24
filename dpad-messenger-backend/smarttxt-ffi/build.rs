use std::io::Result;

fn main() -> Result<()> {
    // OpenBubbles' authoritative schema for the `dumb` hardware file. Decoding it
    // against the real schema — rather than reading field numbers off a hexdump —
    // is what keeps `rom` (field 11) and `io_mac_address` (field 2) straight.
    let mut prost_build = prost_build::Config::new();
    prost_build.protoc_arg("--experimental_allow_proto3_optional");
    prost_build.compile_protos(&["src/mac_hw_info.proto"], &["src/"])?;
    Ok(())
}
