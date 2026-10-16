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

    // Linux: link the GNU C++ runtime (not libc++). The loader looks first in
    // $ORIGIN/../lib/ridley — where the .deb/.rpm install the .so files
    // (bundle.linux.*.files in tauri.conf.json) — then in the absolute build
    // dirs, so running straight from target/ keeps working. The AppImage needs
    // neither: linuxdeploy copies the .so into usr/lib and rewrites the rpath.
    #[cfg(target_os = "linux")]
    {
        println!("cargo:rustc-link-lib=dylib=stdc++");
        println!("cargo:rustc-link-arg=-Wl,-rpath,$ORIGIN/../lib/ridley");
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", lib_dir.display());
        println!("cargo:rustc-link-arg=-Wl,-rpath,{}", stdlib_dir.display());
        stage_linux_libs(&out_dir, &[lib_dir.join("libfive.so"), stdlib_dir.join("libfive-stdlib.so")]);
    }

    println!("cargo:rerun-if-changed=vendor/libfive");
}

/// Copy the libfive .so files to a fixed path, target/<profile>/libfive/, so
/// tauri.conf.json can name them: the build dir carries a hash that changes,
/// and stale ones linger next to it. Stripped, since the debug info makes
/// libfive.so ~200 MB.
#[cfg(target_os = "linux")]
fn stage_linux_libs(out_dir: &PathBuf, libs: &[PathBuf]) {
    // OUT_DIR = target/<profile>/build/<pkg>-<hash>/out
    let profile_dir = out_dir.ancestors().nth(3).expect("unexpected OUT_DIR layout");
    let stage = profile_dir.join("libfive");
    std::fs::create_dir_all(&stage).expect("create libfive stage dir");
    for lib in libs {
        let dest = stage.join(lib.file_name().unwrap());
        std::fs::copy(lib, &dest).expect("copy libfive .so to stage dir");
        // Best effort: an unstripped copy still works, it is only bigger.
        let _ = Command::new("strip").arg("--strip-unneeded").arg(&dest).status();
    }
}
