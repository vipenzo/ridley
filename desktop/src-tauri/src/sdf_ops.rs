//! SDF operations via libfive.
//!
//! Receives an SDF tree as JSON, builds it with libfive's C API,
//! meshes it, and returns triangle mesh data.

use crate::manifold_ops::MeshData;
use serde::Deserialize;
use std::os::raw::c_void;

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
    fn libfive_tree_delete(t: LibfiveTree);
    fn libfive_tree_render_mesh(t: LibfiveTree, region: LibfiveRegion3, resolution: f32) -> *mut LibfiveMesh;
    fn libfive_mesh_delete(m: *mut LibfiveMesh);

    // Stdlib primitives
    fn sphere(radius: LibfiveTree, center: TVec3) -> LibfiveTree;
    fn cylinder_z(r: LibfiveTree, h: LibfiveTree, base: TVec3) -> LibfiveTree;
    fn box_exact(a: TVec3, b: TVec3) -> LibfiveTree;

    // CSG
    fn _union(a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;
    fn difference(a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;
    fn intersection(a: LibfiveTree, b: LibfiveTree) -> LibfiveTree;

    // SDF-specific (not available in mesh boolean)
    fn shell(a: LibfiveTree, offset: LibfiveTree) -> LibfiveTree;
    fn offset(a: LibfiveTree, o: LibfiveTree) -> LibfiveTree;
    fn blend_expt_unit(a: LibfiveTree, b: LibfiveTree, m: LibfiveTree) -> LibfiveTree;
    fn morph(a: LibfiveTree, b: LibfiveTree, m: LibfiveTree) -> LibfiveTree;

    // Transforms
    #[link_name = "move"]
    fn move_(t: LibfiveTree, offset: TVec3) -> LibfiveTree;
    fn rotate_z(t: LibfiveTree, angle: LibfiveTree, center: TVec3) -> LibfiveTree;
}

fn tc(v: f32) -> LibfiveTree { unsafe { libfive_tree_const(v) } }
fn tv(x: f32, y: f32, z: f32) -> TVec3 { TVec3 { x: tc(x), y: tc(y), z: tc(z) } }
fn tv0() -> TVec3 { tv(0.0, 0.0, 0.0) }

/// SDF tree node — JSON representation from the JVM
#[derive(Debug, Deserialize)]
#[serde(tag = "op")]
pub enum SdfNode {
    #[serde(rename = "sphere")]
    Sphere { r: f64 },
    #[serde(rename = "box")]
    Box { sx: f64, sy: f64, sz: f64 },
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
    #[serde(rename = "shell")]
    Shell { a: std::boxed::Box<SdfNode>, thickness: f64 },
    #[serde(rename = "offset")]
    Offset { a: std::boxed::Box<SdfNode>, amount: f64 },
    #[serde(rename = "morph")]
    Morph { a: std::boxed::Box<SdfNode>, b: std::boxed::Box<SdfNode>, t: f64 },
    #[serde(rename = "move")]
    Move { a: std::boxed::Box<SdfNode>, dx: f64, dy: f64, dz: f64 },
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
