use std::env;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    tauri_build::build();

    // Set rpath for Manifold + libfive dylibs
    #[cfg(target_os = "macos")]
    {
        println!("cargo:rustc-link-arg=-Wl,-rpath,@executable_path/../Frameworks");
    }

    // Build libfive from source if vendor/libfive exists
    let libfive_src = PathBuf::from("vendor/libfive");
    if libfive_src.exists() {
        build_libfive(&libfive_src);
    }
}

fn build_libfive(libfive_src: &PathBuf) {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let build_dir = out_dir.join("libfive-build");

    // Configure with CMake
    let status = Command::new("cmake")
        .args([
            "-S", libfive_src.to_str().unwrap(),
            "-B", build_dir.to_str().unwrap(),
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_STUDIO_APP=OFF",
            "-DBUILD_GUILE_BINDINGS=OFF",
            "-DBUILD_PYTHON_BINDINGS=OFF",
        ])
        .status()
        .expect("Failed to run cmake configure for libfive");
    assert!(status.success(), "libfive cmake configure failed");

    // Build
    let ncpu = std::thread::available_parallelism()
        .map(|n| n.get()).unwrap_or(4);
    let status = Command::new("cmake")
        .args([
            "--build", build_dir.to_str().unwrap(),
            "--config", "Release",
            "-j", &ncpu.to_string(),
        ])
        .status()
        .expect("Failed to run cmake build for libfive");
    assert!(status.success(), "libfive cmake build failed");

    // Link
    let lib_dir = build_dir.join("libfive").join("src");
    let stdlib_dir = build_dir.join("libfive").join("stdlib");
    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-link-search=native={}", stdlib_dir.display());
    println!("cargo:rustc-link-lib=dylib=five");
    println!("cargo:rustc-link-lib=dylib=five-stdlib");

    #[cfg(target_os = "macos")]
    {
        println!("cargo:rustc-link-lib=dylib=c++");
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", lib_dir.display());
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", stdlib_dir.display());
    }

    println!("cargo:rerun-if-changed=vendor/libfive");
}
