//! Native Manifold CSG operations exposed via Tauri IPC.
//!
//! Uses the 64-bit MeshGL API (f64 vertices, u64 indices) to match
//! JavaScript's native double precision and avoid f32 rounding artifacts.

use manifold3d::sys::*;
use serde::{Deserialize, Serialize};
use std::alloc::{alloc, Layout};
use std::os::raw::c_void;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshData {
    pub vertices: Vec<[f64; 3]>,
    pub faces: Vec<[u32; 3]>,
}

/// Opaque Manifold handle with Drop.
struct ManifoldHandle(*mut ManifoldManifold);

impl Drop for ManifoldHandle {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { manifold_delete_manifold(self.0) }
        }
    }
}

/// Opaque MeshGL64 handle with Drop.
struct MeshGL64Handle(*mut ManifoldMeshGL64);

impl Drop for MeshGL64Handle {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { manifold_delete_meshgl64(self.0) }
        }
    }
}

fn mesh_to_manifold(mesh: &MeshData) -> Result<ManifoldHandle, String> {
    let n_verts = mesh.vertices.len();
    let n_tris = mesh.faces.len();

    // Flatten vertices: [x,y,z, x,y,z, ...] as f64
    let mut vert_props: Vec<f64> = Vec::with_capacity(n_verts * 3);
    for v in &mesh.vertices {
        vert_props.extend_from_slice(v);
    }

    // Flatten faces: [i,j,k, i,j,k, ...] as u64 (meshgl64 uses u64 indices)
    let mut tri_verts: Vec<u64> = Vec::with_capacity(n_tris * 3);
    for f in &mesh.faces {
        tri_verts.push(f[0] as u64);
        tri_verts.push(f[1] as u64);
        tri_verts.push(f[2] as u64);
    }

    // Create MeshGL64
    let mesh_gl = unsafe {
        manifold_meshgl64(
            manifold_alloc_meshgl64() as *mut c_void,
            vert_props.as_mut_ptr(),
            n_verts,
            3, // num_prop = 3 (x, y, z)
            tri_verts.as_mut_ptr(),
            n_tris,
        )
    };
    let _mesh_gl_guard = MeshGL64Handle(mesh_gl);

    // Create Manifold from MeshGL64
    let manifold_ptr =
        unsafe { manifold_of_meshgl64(manifold_alloc_manifold() as *mut c_void, mesh_gl) };

    // Check status
    let status = unsafe { manifold_status(manifold_ptr) };
    if status != ManifoldError_MANIFOLD_NO_ERROR {
        return Err(format!("Manifold creation failed (status: {})", status));
    }

    Ok(ManifoldHandle(manifold_ptr))
}

fn manifold_to_mesh(m: &ManifoldHandle) -> MeshData {
    // Get MeshGL64 from Manifold
    let mesh_gl =
        unsafe { manifold_get_meshgl64(manifold_alloc_meshgl64() as *mut c_void, m.0) };
    let _mesh_gl_guard = MeshGL64Handle(mesh_gl);

    let n_props = unsafe { manifold_meshgl64_num_prop(mesh_gl) };
    let n_verts = unsafe { manifold_meshgl64_num_vert(mesh_gl) };
    let vert_prop_count = unsafe { manifold_meshgl64_vert_properties_length(mesh_gl) };
    let tri_count = unsafe { manifold_meshgl64_tri_length(mesh_gl) };

    // Extract vertex properties as f64
    let vert_props = unsafe {
        let layout = Layout::array::<f64>(vert_prop_count).unwrap();
        let ptr = alloc(layout) as *mut f64;
        manifold_meshgl64_vert_properties(ptr as *mut c_void, mesh_gl);
        Vec::from_raw_parts(ptr, vert_prop_count, vert_prop_count)
    };

    // Extract triangle indices as u64
    let tri_verts = unsafe {
        let layout = Layout::array::<u64>(tri_count).unwrap();
        let ptr = alloc(layout) as *mut u64;
        manifold_meshgl64_tri_verts(ptr as *mut c_void, mesh_gl);
        Vec::from_raw_parts(ptr, tri_count, tri_count)
    };

    // Pack vertices
    let mut vertices = Vec::with_capacity(n_verts);
    for i in 0..n_verts {
        let base = i * n_props;
        vertices.push([vert_props[base], vert_props[base + 1], vert_props[base + 2]]);
    }

    // Pack faces (u64 -> u32, safe for any reasonable mesh)
    let mut faces = Vec::with_capacity(tri_verts.len() / 3);
    for chunk in tri_verts.chunks(3) {
        faces.push([chunk[0] as u32, chunk[1] as u32, chunk[2] as u32]);
    }

    MeshData { vertices, faces }
}

pub fn union(meshes: &[MeshData]) -> Result<MeshData, String> {
    if meshes.is_empty() {
        return Err("No meshes provided".into());
    }
    if meshes.len() == 1 {
        return Ok(meshes[0].clone());
    }

    let mut result = mesh_to_manifold(&meshes[0])?;
    for m in &meshes[1..] {
        let other = mesh_to_manifold(m)?;
        let new_ptr = unsafe {
            manifold_union(manifold_alloc_manifold() as *mut c_void, result.0, other.0)
        };
        result = ManifoldHandle(new_ptr);
    }
    Ok(manifold_to_mesh(&result))
}

pub fn difference(base: &MeshData, cutters: &[MeshData]) -> Result<MeshData, String> {
    let mut result = mesh_to_manifold(base)?;
    for c in cutters {
        let cutter = mesh_to_manifold(c)?;
        let new_ptr = unsafe {
            manifold_difference(manifold_alloc_manifold() as *mut c_void, result.0, cutter.0)
        };
        result = ManifoldHandle(new_ptr);
    }
    Ok(manifold_to_mesh(&result))
}

pub fn intersection(meshes: &[MeshData]) -> Result<MeshData, String> {
    if meshes.is_empty() {
        return Err("No meshes provided".into());
    }
    if meshes.len() == 1 {
        return Ok(meshes[0].clone());
    }

    let mut result = mesh_to_manifold(&meshes[0])?;
    for m in &meshes[1..] {
        let other = mesh_to_manifold(m)?;
        let new_ptr = unsafe {
            manifold_intersection(manifold_alloc_manifold() as *mut c_void, result.0, other.0)
        };
        result = ManifoldHandle(new_ptr);
    }
    Ok(manifold_to_mesh(&result))
}

/// Smooth a mesh via tangent-based subdivision.
///
/// Internally calls `manifold_smooth_out` to populate halfedge tangents
/// (edges with dihedral angle <= `min_sharp_angle` are flagged smooth),
/// then `manifold_refine(refine)` to subdivide each triangle into
/// `refine^2` sub-triangles, placing the new vertices on the tangent
/// curves rather than along straight lines. The result is a denser mesh
/// whose surface is C^1 wherever the original dihedral was below the
/// sharp threshold.
///
/// `refine = 1` skips the subdivision step (no-op for the smoothing,
/// since smooth_out only sets tangents). `refine >= 2` is the typical
/// use.
pub fn smooth(
    mesh: &MeshData,
    min_sharp_angle: f64,
    min_smoothness: f64,
    refine: i32,
) -> Result<MeshData, String> {
    let m = mesh_to_manifold(mesh)?;
    let smoothed = unsafe {
        manifold_smooth_out(
            manifold_alloc_manifold() as *mut c_void,
            m.0,
            min_sharp_angle,
            min_smoothness,
        )
    };
    let smoothed = ManifoldHandle(smoothed);
    let result = if refine >= 2 {
        let refined = unsafe {
            manifold_refine(
                manifold_alloc_manifold() as *mut c_void,
                smoothed.0,
                refine as ::std::os::raw::c_int,
            )
        };
        ManifoldHandle(refined)
    } else {
        smoothed
    };
    Ok(manifold_to_mesh(&result))
}

/// Refine a mesh by splitting every edge into `n` pieces (each triangle
/// becomes `n^2` sub-triangles). Without prior tangent data this is a
/// linear (planar) subdivision — the shape is unchanged, just denser.
pub fn refine(mesh: &MeshData, n: i32) -> Result<MeshData, String> {
    if n < 2 {
        return Ok(mesh.clone());
    }
    let m = mesh_to_manifold(mesh)?;
    let refined = unsafe {
        manifold_refine(
            manifold_alloc_manifold() as *mut c_void,
            m.0,
            n as ::std::os::raw::c_int,
        )
    };
    Ok(manifold_to_mesh(&ManifoldHandle(refined)))
}

pub fn hull(meshes: &[MeshData]) -> Result<MeshData, String> {
    if meshes.is_empty() {
        return Err("No meshes provided".into());
    }

    let first = mesh_to_manifold(&meshes[0])?;
    if meshes.len() == 1 {
        let new_ptr = unsafe {
            manifold_hull(manifold_alloc_manifold() as *mut c_void, first.0)
        };
        return Ok(manifold_to_mesh(&ManifoldHandle(new_ptr)));
    }

    // Union all, then hull
    let mut combined = first;
    for m in &meshes[1..] {
        let other = mesh_to_manifold(m)?;
        let new_ptr = unsafe {
            manifold_union(manifold_alloc_manifold() as *mut c_void, combined.0, other.0)
        };
        combined = ManifoldHandle(new_ptr);
    }
    let hull_ptr = unsafe {
        manifold_hull(manifold_alloc_manifold() as *mut c_void, combined.0)
    };
    Ok(manifold_to_mesh(&ManifoldHandle(hull_ptr)))
}
