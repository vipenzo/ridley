//! Native Manifold CSG operations exposed via Tauri IPC.
//!
//! Works directly with manifold3d's C FFI (via sys bindings) since the
//! safe Rust wrapper doesn't expose MeshGL construction from raw data.

use manifold3d::sys::*;
use serde::{Deserialize, Serialize};
use std::alloc::{alloc, Layout};
use std::os::raw::c_void;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshData {
    pub vertices: Vec<[f32; 3]>,
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

/// Opaque MeshGL handle with Drop.
struct MeshGLHandle(*mut ManifoldMeshGL);

impl Drop for MeshGLHandle {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { manifold_delete_meshgl(self.0) }
        }
    }
}

fn mesh_to_manifold(mesh: &MeshData) -> Result<ManifoldHandle, String> {
    let n_verts = mesh.vertices.len();
    let n_tris = mesh.faces.len();

    // Flatten vertices: [x,y,z, x,y,z, ...]
    let mut vert_props: Vec<f32> = Vec::with_capacity(n_verts * 3);
    for v in &mesh.vertices {
        vert_props.extend_from_slice(v);
    }

    // Flatten faces: [i,j,k, i,j,k, ...]
    let mut tri_verts: Vec<u32> = Vec::with_capacity(n_tris * 3);
    for f in &mesh.faces {
        tri_verts.extend_from_slice(f);
    }

    // Create MeshGL
    let mesh_gl = unsafe {
        manifold_meshgl(
            manifold_alloc_meshgl() as *mut c_void,
            vert_props.as_mut_ptr(),
            n_verts,
            3, // num_prop = 3 (x, y, z)
            tri_verts.as_mut_ptr(),
            n_tris,
        )
    };
    let _mesh_gl_guard = MeshGLHandle(mesh_gl);

    // Create Manifold from MeshGL
    let manifold_ptr =
        unsafe { manifold_of_meshgl(manifold_alloc_manifold() as *mut c_void, mesh_gl) };

    // Check status
    let status = unsafe { manifold_status(manifold_ptr) };
    if status != ManifoldError_MANIFOLD_NO_ERROR {
        return Err(format!("Manifold creation failed (status: {})", status));
    }

    Ok(ManifoldHandle(manifold_ptr))
}

fn manifold_to_mesh(m: &ManifoldHandle) -> MeshData {
    // Get MeshGL from Manifold
    let mesh_gl =
        unsafe { manifold_get_meshgl(manifold_alloc_meshgl() as *mut c_void, m.0) };
    let _mesh_gl_guard = MeshGLHandle(mesh_gl);

    let n_props = unsafe { manifold_meshgl_num_prop(mesh_gl) } as usize;
    let n_verts = unsafe { manifold_meshgl_num_vert(mesh_gl) } as usize;
    let vert_prop_count = unsafe { manifold_meshgl_vert_properties_length(mesh_gl) };
    let tri_count = unsafe { manifold_meshgl_tri_length(mesh_gl) };

    // Extract vertex properties
    let vert_props = unsafe {
        let layout = Layout::array::<f32>(vert_prop_count).unwrap();
        let ptr = alloc(layout) as *mut f32;
        manifold_meshgl_vert_properties(ptr as *mut c_void, mesh_gl);
        Vec::from_raw_parts(ptr, vert_prop_count, vert_prop_count)
    };

    // Extract triangle indices
    let tri_verts = unsafe {
        let layout = Layout::array::<u32>(tri_count).unwrap();
        let ptr = alloc(layout) as *mut u32;
        manifold_meshgl_tri_verts(ptr as *mut c_void, mesh_gl);
        Vec::from_raw_parts(ptr, tri_count, tri_count)
    };

    // Pack vertices
    let mut vertices = Vec::with_capacity(n_verts);
    for i in 0..n_verts {
        let base = i * n_props;
        vertices.push([vert_props[base], vert_props[base + 1], vert_props[base + 2]]);
    }

    // Pack faces
    let mut faces = Vec::with_capacity(tri_verts.len() / 3);
    for chunk in tri_verts.chunks(3) {
        faces.push([chunk[0], chunk[1], chunk[2]]);
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
