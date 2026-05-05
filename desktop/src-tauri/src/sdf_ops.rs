//! SDF operations via libfive.
//!
//! Receives an SDF tree as JSON, builds it with libfive's C API,
//! meshes it, and returns triangle mesh data.

use serde::{Deserialize, Serialize};
use std::os::raw::c_void;

/// Triangle mesh payload exchanged with the frontend.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshData {
    pub vertices: Vec<[f64; 3]>,
    pub faces: Vec<[u32; 3]>,
}

// libfive C API bindings (minimal, hand-written)
type LibfiveTree = *mut c_void;

#[repr(C)]
struct LibfiveInterval { lower: f32, upper: f32 }

#[repr(C)]
struct LibfiveRegion3 { x: LibfiveInterval, y: LibfiveInterval, z: LibfiveInterval }

#[repr(C)]
struct LibfiveVec3 { x: f32, y: f32, z: f32 }

#[repr(C)]
struct LibfiveTri { a: u32, b: u32, c: u32 }

#[repr(C)]
struct LibfiveMesh {
    verts: *mut LibfiveVec3,
    tris: *mut LibfiveTri,
    tri_count: u32,
    vert_count: u32,
}

// tvec3 for stdlib: each component is a tree
#[repr(C)]
struct TVec3 { x: LibfiveTree, y: LibfiveTree, z: LibfiveTree }

extern "C" {
    fn libfive_tree_const(f: f32) -> LibfiveTree;
    fn libfive_tree_x() -> LibfiveTree;
    fn libfive_tree_y() -> LibfiveTree;
    fn libfive_tree_z() -> LibfiveTree;
    fn libfive_tree_unary(op: i32, a: LibfiveTree) -> LibfiveTree;
    fn libfive_tree_binary(op: i32, a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;
    fn libfive_tree_remap(p: LibfiveTree, x: LibfiveTree, y: LibfiveTree, z: LibfiveTree) -> LibfiveTree;
    fn libfive_tree_delete(t: LibfiveTree);
    fn libfive_tree_render_mesh(t: LibfiveTree, region: LibfiveRegion3, resolution: f32) -> *mut LibfiveMesh;
    fn libfive_mesh_delete(m: *mut LibfiveMesh);

    // Stdlib primitives
    fn sphere(radius: LibfiveTree, center: TVec3) -> LibfiveTree;
    fn cylinder_z(r: LibfiveTree, h: LibfiveTree, base: TVec3) -> LibfiveTree;
    fn box_exact(a: TVec3, b: TVec3) -> LibfiveTree;
    fn rounded_box(a: TVec3, b: TVec3, r: LibfiveTree) -> LibfiveTree;

    // CSG
    fn _union(a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;
    fn difference(a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;
    fn intersection(a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;

    // SDF-specific (not available in mesh boolean)
    fn shell(a: LibfiveTree, offset: LibfiveTree) -> LibfiveTree;
    fn offset(a: LibfiveTree, o: LibfiveTree) -> LibfiveTree;
    fn blend_expt_unit(a: LibfiveTree, b: LibfiveTree, m: LibfiveTree) -> LibfiveTree;
    fn blend_difference(a: LibfiveTree, b: LibfiveTree, m: LibfiveTree, o: LibfiveTree) -> LibfiveTree;
    fn morph(a: LibfiveTree, b: LibfiveTree, m: LibfiveTree) -> LibfiveTree;

    // Transforms
    #[link_name = "move"]
    fn move_(t: LibfiveTree, offset: TVec3) -> LibfiveTree;
    fn rotate_x(t: LibfiveTree, angle: LibfiveTree, center: TVec3) -> LibfiveTree;
    fn rotate_y(t: LibfiveTree, angle: LibfiveTree, center: TVec3) -> LibfiveTree;
    fn rotate_z(t: LibfiveTree, angle: LibfiveTree, center: TVec3) -> LibfiveTree;
    fn scale_xyz(t: LibfiveTree, s: TVec3, center: TVec3) -> LibfiveTree;
}

fn tc(v: f32) -> LibfiveTree { unsafe { libfive_tree_const(v) } }
fn tv(x: f32, y: f32, z: f32) -> TVec3 { TVec3 { x: tc(x), y: tc(y), z: tc(z) } }
fn tv0() -> TVec3 { tv(0.0, 0.0, 0.0) }

// libfive opcodes (from libfive/include/libfive/tree/opcode.hpp)
const OP_SQUARE: i32 = 7;
const OP_SQRT: i32 = 8;
const OP_NEG: i32 = 9;
const OP_SIN: i32 = 10;
const OP_COS: i32 = 11;
const OP_TAN: i32 = 12;
const OP_ASIN: i32 = 13;
const OP_ACOS: i32 = 14;
const OP_ATAN: i32 = 15;
const OP_EXP: i32 = 16;
const OP_ABS: i32 = 28;
const OP_LOG: i32 = 30;
const OP_ADD: i32 = 17;
const OP_MUL: i32 = 18;
const OP_MIN: i32 = 19;
const OP_MAX: i32 = 20;
const OP_SUB: i32 = 21;
const OP_DIV: i32 = 22;
const OP_ATAN2: i32 = 23;
const OP_POW: i32 = 24;
const OP_MOD: i32 = 26;

fn unary_opcode(name: &str) -> Option<i32> {
    match name {
        "square" => Some(OP_SQUARE),
        "sqrt" => Some(OP_SQRT),
        "neg" => Some(OP_NEG),
        "sin" => Some(OP_SIN),
        "cos" => Some(OP_COS),
        "tan" => Some(OP_TAN),
        "asin" => Some(OP_ASIN),
        "acos" => Some(OP_ACOS),
        "atan" => Some(OP_ATAN),
        "exp" => Some(OP_EXP),
        "abs" => Some(OP_ABS),
        "log" => Some(OP_LOG),
        _ => None,
    }
}

fn binary_opcode(name: &str) -> Option<i32> {
    match name {
        "add" => Some(OP_ADD),
        "mul" => Some(OP_MUL),
        "min" => Some(OP_MIN),
        "max" => Some(OP_MAX),
        "sub" => Some(OP_SUB),
        "div" => Some(OP_DIV),
        "atan2" => Some(OP_ATAN2),
        "pow" => Some(OP_POW),
        "mod" => Some(OP_MOD),
        _ => None,
    }
}

/// SDF tree node — JSON representation from the JVM
#[derive(Debug, Deserialize)]
#[serde(tag = "op")]
pub enum SdfNode {
    #[serde(rename = "sphere")]
    Sphere { r: f64 },
    #[serde(rename = "box")]
    Box { sx: f64, sy: f64, sz: f64 },
    #[serde(rename = "rounded-box")]
    RoundedBox { sx: f64, sy: f64, sz: f64, r: f64 },
    #[serde(rename = "cyl")]
    Cyl { r: f64, h: f64 },
    #[serde(rename = "union")]
    Union { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode> },
    #[serde(rename = "difference")]
    Difference { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode> },
    #[serde(rename = "intersection")]
    Intersection { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode> },
    #[serde(rename = "blend")]
    Blend { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode>, k: f64 },
    #[serde(rename = "blend-difference")]
    BlendDifference { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode>, k: f64 },
    #[serde(rename = "shell")]
    Shell { a: std::boxed::Box<SdfNode>, thickness: f64 },
    #[serde(rename = "offset")]
    Offset { a: std::boxed::Box<SdfNode>, amount: f64 },
    #[serde(rename = "morph")]
    Morph { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode>, t: f64 },
    #[serde(rename = "move")]
    Move { a: std::boxed::Box<SdfNode>, dx: f64, dy: f64, dz: f64 },
    #[serde(rename = "rotate")]
    Rotate { a: std::boxed::Box<SdfNode>, axis: String, angle: f64 },
    #[serde(rename = "scale")]
    Scale { a: std::boxed::Box<SdfNode>, sx: f64, sy: f64, sz: f64 },
    // Expression nodes (for sdf-formula)
    #[serde(rename = "var")]
    Var { name: String },
    #[serde(rename = "const")]
    Const { value: f64 },
    #[serde(rename = "unary")]
    Unary { fn_name: String, a: std::boxed::Box<SdfNode> },
    #[serde(rename = "binary")]
    Binary { fn_name: String, a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode> },
    /// Revolve a 2D SDF (in the X/Y plane) around the Z axis.
    /// Maps X → sqrt(X²+Y²), Y → Z, Z → 0 in the child tree.
    #[serde(rename = "revolve")]
    Revolve { a: std::boxed::Box<SdfNode> },
}

#[derive(Debug, Deserialize)]
pub struct SdfMeshRequest {
    pub tree: SdfNode,
    pub bounds: [[f64; 2]; 3],
    pub resolution: f64,
}

/// Recursively build a libfive tree from the JSON node.
fn build_tree(node: &SdfNode) -> LibfiveTree {
    unsafe {
        match node {
            SdfNode::Sphere { r } => {
                sphere(tc(*r as f32), tv0())
            }
            SdfNode::Box { sx, sy, sz } => {
                let hx = (*sx as f32) / 2.0;
                let hy = (*sy as f32) / 2.0;
                let hz = (*sz as f32) / 2.0;
                box_exact(tv(-hx, -hy, -hz), tv(hx, hy, hz))
            }
            SdfNode::RoundedBox { sx, sy, sz, r } => {
                // libfive's rounded_box takes radius as 0-1 fraction of half-shortest-side
                let hx = (*sx as f32) / 2.0;
                let hy = (*sy as f32) / 2.0;
                let hz = (*sz as f32) / 2.0;
                let min_half = hx.min(hy).min(hz);
                let frac = (*r as f32 / min_half).clamp(0.0, 1.0);
                rounded_box(tv(-hx, -hy, -hz), tv(hx, hy, hz), tc(frac))
            }
            SdfNode::Cyl { r, h } => {
                // cylinder_z is centered at base, so offset by -h/2 for centering
                let tree = cylinder_z(tc(*r as f32), tc(*h as f32), tv0());
                move_(tree, tv(0.0, 0.0, -(*h as f32) / 2.0))
            }
            SdfNode::Union { a, b } => _union(build_tree(a), build_tree(b)),
            SdfNode::Difference { a, b } => difference(build_tree(a), build_tree(b)),
            SdfNode::Intersection { a, b } => intersection(build_tree(a), build_tree(b)),
            SdfNode::Blend { a, b, k } => {
                blend_expt_unit(build_tree(a), build_tree(b), tc(*k as f32))
            }
            SdfNode::BlendDifference { a, b, k } => {
                blend_difference(build_tree(a), build_tree(b), tc(*k as f32), tc(0.0))
            }
            SdfNode::Shell { a, thickness } => {
                shell(build_tree(a), tc(*thickness as f32))
            }
            SdfNode::Offset { a, amount } => {
                offset(build_tree(a), tc(*amount as f32))
            }
            SdfNode::Morph { a, b, t } => {
                morph(build_tree(a), build_tree(b), tc(*t as f32))
            }
            SdfNode::Move { a, dx, dy, dz } => {
                move_(build_tree(a), tv(*dx as f32, *dy as f32, *dz as f32))
            }
            SdfNode::Rotate { a, axis, angle } => {
                let rad = (*angle as f32).to_radians();
                let tree = build_tree(a);
                match axis.as_str() {
                    "x" => rotate_x(tree, tc(rad), tv0()),
                    "y" => rotate_y(tree, tc(rad), tv0()),
                    _   => rotate_z(tree, tc(rad), tv0()),
                }
            }
            SdfNode::Scale { a, sx, sy, sz } => {
                scale_xyz(build_tree(a), tv(*sx as f32, *sy as f32, *sz as f32), tv0())
            }
            // Expression nodes
            SdfNode::Var { name } => {
                match name.as_str() {
                    "x" => libfive_tree_x(),
                    "y" => libfive_tree_y(),
                    "z" => libfive_tree_z(),
                    _ => tc(0.0),
                }
            }
            SdfNode::Const { value } => tc(*value as f32),
            SdfNode::Unary { fn_name, a } => {
                let op = unary_opcode(fn_name)
                    .unwrap_or_else(|| panic!("Unknown unary op: {}", fn_name));
                libfive_tree_unary(op, build_tree(a))
            }
            SdfNode::Binary { fn_name, a, b } => {
                let op = binary_opcode(fn_name)
                    .unwrap_or_else(|| panic!("Unknown binary op: {}", fn_name));
                libfive_tree_binary(op, build_tree(a), build_tree(b))
            }
            SdfNode::Revolve { a } => {
                // Build the 2D SDF child, then remap variables:
                //   X → sqrt(X² + Y²)   (rho = cylindrical radius)
                //   Y → Z               (height)
                //   Z → 0               (unused in 2D)
                // Uses OP_SQUARE instead of OP_MUL(x,x) so libfive's interval
                // arithmetic knows x² ≥ 0 (prevents axis artifacts in marching cubes).
                let child = build_tree(a);
                let x = libfive_tree_x();
                let y = libfive_tree_y();
                let z = libfive_tree_z();
                let x2 = libfive_tree_unary(OP_SQUARE, x);
                let y2 = libfive_tree_unary(OP_SQUARE, y);
                let sum = libfive_tree_binary(OP_ADD, x2, y2);
                let rho = libfive_tree_unary(OP_SQRT, sum);
                libfive_tree_remap(child, rho, z, tc(0.0))
            }
        }
    }
}

/// Build SDF tree, mesh it, return triangle mesh.
pub fn sdf_to_mesh(req: &SdfMeshRequest) -> Result<MeshData, String> {
    let tree = build_tree(&req.tree);

    let region = LibfiveRegion3 {
        x: LibfiveInterval { lower: req.bounds[0][0] as f32, upper: req.bounds[0][1] as f32 },
        y: LibfiveInterval { lower: req.bounds[1][0] as f32, upper: req.bounds[1][1] as f32 },
        z: LibfiveInterval { lower: req.bounds[2][0] as f32, upper: req.bounds[2][1] as f32 },
    };

    let mesh_ptr = unsafe {
        libfive_tree_render_mesh(tree, region, req.resolution as f32)
    };

    if mesh_ptr.is_null() {
        unsafe { libfive_tree_delete(tree) };
        return Err("libfive meshing returned null".into());
    }

    let result = unsafe {
        let mesh = &*mesh_ptr;
        let verts = std::slice::from_raw_parts(mesh.verts, mesh.vert_count as usize);
        let tris = std::slice::from_raw_parts(mesh.tris, mesh.tri_count as usize);

        let vertices: Vec<[f64; 3]> = verts.iter()
            .map(|v| [v.x as f64, v.y as f64, v.z as f64])
            .collect();
        let faces: Vec<[u32; 3]> = tris.iter()
            .map(|t| [t.a, t.b, t.c])
            .collect();

        MeshData { vertices, faces }
    };

    unsafe {
        libfive_mesh_delete(mesh_ptr);
        libfive_tree_delete(tree);
    };

    Ok(result)
}
