fn main() {
    tauri_build::build();

    // Set rpath so the binary finds dylibs in Frameworks/ inside the .app bundle
    // and also in the build output directory during development
    #[cfg(target_os = "macos")]
    {
        println!("cargo:rustc-link-arg=-Wl,-rpath,@executable_path/../Frameworks");
    }
}
