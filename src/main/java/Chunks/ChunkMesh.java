package Chunks;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector2f;

import Cube.Blocks;
import Cube.Vertex;
import Models.CubeModel;
import ProjectV.GameLoop;

public class ChunkMesh {
  List<Vertex> vertices;

  private List<Float> positionList;
  private List<Float> uvsList;
  private List<Float> normalsList;
  private List<Float> aoList;
  // external occupied set deprecated after BitSet optimization

  public float[] positions, uvs, normals, aos;

  public Chunk chunk;
  // Precomputed highest filled y per (x,z) column for current build; -1 if empty
  private int[] surfaceHeights; // length SIZE*SIZE
  // Number of top layers along a column that always keep their side faces when a
  // neighbor
  // chunk is missing. Increased from 6 to 16 to preserve a thicker natural
  // terrain edge
  // while still suppressing deep interior vertical walls (performance + visuals).
  private static final int SURFACE_VISIBLE_LAYERS = 16;
  // Re-enable boundary suppression to prevent generating massive 512-block-tall
  // walls
  // on chunk borders (caused large vertex counts, memory spikes & freezing when
  // disabled).
  private static final boolean ENABLE_BOUNDARY_SUPPRESSION = true;

  public ChunkMesh(Chunk chunk) {
    this.chunk = chunk;
    vertices = new ArrayList<>();
    positionList = new ArrayList<>();
    uvsList = new ArrayList<>();
    normalsList = new ArrayList<>();
    aoList = new ArrayList<>();
    buildMesh();
    populateLists();
  }

  public void update(Chunk chunk) {
    this.chunk = chunk;

    buildMesh();
    populateLists();
  }

  private void buildMesh() {
    // Reset vertex accumulator (object reused in update path)
    if (!vertices.isEmpty()) {
      vertices.clear();
    }
    final int size = Chunk.SIZE;
    // Recompute per-column surface heights so we can suppress only subterranean
    // side faces on chunk borders when the neighbor chunk is still missing. This
    // prevents tall temporary "walls" underground while preserving exposed
    // terrain silhouettes (top layers always shown).
    surfaceHeights = new int[size * size];
    for (int x = 0; x < size; x++) {
      for (int z = 0; z < size; z++) {
        int top = -1;
        for (int y = GameLoop.CHUNK_HEIGHT - 1; y >= 0; y--) {
          if (chunk.hasLocal(x, y, z)) {
            top = y;
            break;
          }
        }
        surfaceHeights[(z * size) + x] = top;
      }
    }
    // New implementation: use chunk BitSet for intra-chunk neighbors and direct
    // world chunk lookups across boundaries.
    if (chunk.blocks == null) {
      // Compacted chunk: iterate only occupied cells using BitSet nextSetBit to avoid
      // scanning entire 16*H*16 volume. This dramatically reduces work for sparse
      // chunks.
      int height = GameLoop.CHUNK_HEIGHT;
      for (int bit = chunk.occupancy.nextSetBit(0); bit >= 0; bit = chunk.occupancy.nextSetBit(bit + 1)) {
        int tmp = bit / size; // (y*size + z)
        int x = bit % size;
        int y = tmp / size;
        if (y >= height) // safety (shouldn't happen)
          continue;
        int z = tmp % size;
        int type = chunk.getType(x, y, z);
        emitBlock(x, y, z, type);
      }
    } else {
      for (Blocks block : chunk.blocks) {
        int originX = (int) Math.round(chunk.origin.x);
        int originY = (int) Math.round(chunk.origin.y);
        int originZ = (int) Math.round(chunk.origin.z);

        int absX = block.x + originX;
        int absY = block.y + originY;
        int absZ = block.z + originZ;

        // (removed old key strings and packed longs)

        // determine UV set per face based on block type (grass has different
        // top/side/bottom)
        Vector2f[] uv_px = CubeModel.UV_PX;
        Vector2f[] uv_nx = CubeModel.UV_NX;
        Vector2f[] uv_py = CubeModel.UV_PY;
        Vector2f[] uv_ny = CubeModel.UV_NY;
        Vector2f[] uv_pz = CubeModel.UV_PZ;
        Vector2f[] uv_nz = CubeModel.UV_NZ;

        int blockType = (chunk.blocks == null) ? chunk.getType(block.x, block.y, block.z) : block.type;
        if (blockType == Blocks.GRASS) {
          // Grass: top = UV_PY (grass top), bottom = UV_NY (dirt), sides = UV_PX (grass
          // side)
          uv_px = CubeModel.UV_PX;
          uv_nx = CubeModel.UV_NX;
          uv_pz = CubeModel.UV_PZ;
          uv_nz = CubeModel.UV_NZ;
          uv_py = CubeModel.UV_PY; // top (grass top)
          uv_ny = CubeModel.UV_NY; // bottom (dirt)
        } else if (blockType == Blocks.DIRT) {
          // Dirt: use bottom/dirt tile for all faces
          uv_px = uv_nx = uv_py = uv_ny = uv_pz = uv_nz = CubeModel.UV_NY;
        } else if (blockType == Blocks.STONE) {
          // Stone uniform texture
          uv_px = uv_nx = uv_py = uv_ny = uv_pz = uv_nz = CubeModel.UV_STONE;
        } else if (blockType == Blocks.TREEBARK) {
          // Bark: sides = bark side, top/bottom = rings
          uv_px = uv_nx = uv_pz = uv_nz = CubeModel.UV_BARK_SIDE;
          uv_py = CubeModel.UV_BARK_TOP;
          uv_ny = CubeModel.UV_BARK_TOP;
        } else if (blockType == Blocks.TREELEAF) {
          // Leaves uniform
          uv_px = uv_nx = uv_py = uv_ny = uv_pz = uv_nz = CubeModel.UV_LEAF;
        }

        {
          // PX
          boolean pxInside = (block.x + 1 < Chunk.SIZE && chunk.hasLocal(block.x + 1, block.y, block.z));
          boolean pxCross = (block.x + 1 >= Chunk.SIZE && hasBlockInWorld(absX + 1, absY, absZ));
          boolean pxNeighbor = pxInside || pxCross;
          boolean pxMissingNeighborChunk = (block.x + 1 >= Chunk.SIZE && !pxCross);
          int localSurface = surfaceHeights[block.z * size + block.x];
          if (!pxNeighbor && !shouldSuppressBoundaryFace(pxMissingNeighborChunk, block.y, localSurface)) {
            for (int k = 0; k < 6; k++) {
              Vector3f v = new Vector3f(CubeModel.PX_POS[k].x + block.x + 0.5f, CubeModel.PX_POS[k].y + block.y + 0.5f,
                  CubeModel.PX_POS[k].z + block.z + 0.5f);
              Vector2f uv = uv_px[k];
              Vector3f normal = CubeModel.NORMALS[0];
              vertices.add(new Vertex(v, uv, normal));
            }
          }
          // NX
          boolean nxInside = (block.x - 1 >= 0 && chunk.hasLocal(block.x - 1, block.y, block.z));
          boolean nxCross = (block.x - 1 < 0 && hasBlockInWorld(absX - 1, absY, absZ));
          boolean nxNeighbor = nxInside || nxCross;
          boolean nxMissingNeighborChunk = (block.x - 1 < 0 && !nxCross);
          if (!nxNeighbor && !shouldSuppressBoundaryFace(nxMissingNeighborChunk, block.y, localSurface)) {
            for (int k = 0; k < 6; k++) {
              Vector3f v = new Vector3f(CubeModel.NX_POS[k].x + block.x + 0.5f, CubeModel.NX_POS[k].y + block.y + 0.5f,
                  CubeModel.NX_POS[k].z + block.z + 0.5f);
              Vector2f uv = uv_nx[k];
              Vector3f normal = CubeModel.NORMALS[1];
              vertices.add(new Vertex(v, uv, normal));
            }
          }
          // PY (now also checks cross-chunk above if y+1 exits this vertical slice)
          boolean pyNeighbor = (block.y + 1 < GameLoop.CHUNK_HEIGHT && chunk.hasLocal(block.x, block.y + 1, block.z))
              || (block.y + 1 >= GameLoop.CHUNK_HEIGHT && hasBlockInWorld(absX, absY + 1, absZ));
          if (!pyNeighbor) {
            for (int k = 0; k < 6; k++) {
              Vector3f v = new Vector3f(CubeModel.PY_POS[k].x + block.x + 0.5f, CubeModel.PY_POS[k].y + block.y + 0.5f,
                  CubeModel.PY_POS[k].z + block.z + 0.5f);
              Vector2f uv = uv_py[k];
              Vector3f normal = CubeModel.NORMALS[2];
              vertices.add(new Vertex(v, uv, normal));
            }
          }
          // NY (cross-chunk below if y-1 exits slice)
          boolean nyNeighbor = (block.y - 1 >= 0 && chunk.hasLocal(block.x, block.y - 1, block.z))
              || (block.y - 1 < 0 && hasBlockInWorld(absX, absY - 1, absZ));
          if (!nyNeighbor) {
            for (int k = 0; k < 6; k++) {
              Vector3f v = new Vector3f(CubeModel.NY_POS[k].x + block.x + 0.5f, CubeModel.NY_POS[k].y + block.y + 0.5f,
                  CubeModel.NY_POS[k].z + block.z + 0.5f);
              Vector2f uv = uv_ny[k];
              Vector3f normal = CubeModel.NORMALS[3];
              vertices.add(new Vertex(v, uv, normal));
            }
          }
          // PZ
          boolean pzInside = (block.z + 1 < Chunk.SIZE && chunk.hasLocal(block.x, block.y, block.z + 1));
          boolean pzCross = (block.z + 1 >= Chunk.SIZE && hasBlockInWorld(absX, absY, absZ + 1));
          boolean pzNeighbor = pzInside || pzCross;
          boolean pzMissingNeighborChunk = (block.z + 1 >= Chunk.SIZE && !pzCross);
          if (!pzNeighbor && !shouldSuppressBoundaryFace(pzMissingNeighborChunk, block.y, localSurface)) {
            for (int k = 0; k < 6; k++) {
              Vector3f v = new Vector3f(CubeModel.PZ_POS[k].x + block.x + 0.5f, CubeModel.PZ_POS[k].y + block.y + 0.5f,
                  CubeModel.PZ_POS[k].z + block.z + 0.5f);
              Vector2f uv = uv_pz[k];
              Vector3f normal = CubeModel.NORMALS[4];
              vertices.add(new Vertex(v, uv, normal));
            }
          }
          // NZ
          boolean nzInside = (block.z - 1 >= 0 && chunk.hasLocal(block.x, block.y, block.z - 1));
          boolean nzCross = (block.z - 1 < 0 && hasBlockInWorld(absX, absY, absZ - 1));
          boolean nzNeighbor = nzInside || nzCross;
          boolean nzMissingNeighborChunk = (block.z - 1 < 0 && !nzCross);
          if (!nzNeighbor && !shouldSuppressBoundaryFace(nzMissingNeighborChunk, block.y, localSurface)) {
            for (int k = 0; k < 6; k++) {
              Vector3f v = new Vector3f(CubeModel.NZ_POS[k].x + block.x + 0.5f, CubeModel.NZ_POS[k].y + block.y + 0.5f,
                  CubeModel.NZ_POS[k].z + block.z + 0.5f);
              Vector2f uv = uv_nz[k];
              Vector3f normal = CubeModel.NORMALS[5];
              vertices.add(new Vertex(v, uv, normal));
            }
          }
        }
      }
    }
  }

  private void emitBlock(int x, int y, int z, int type) {
    int originX = (int) Math.round(chunk.origin.x);
    int originY = (int) Math.round(chunk.origin.y);
    int originZ = (int) Math.round(chunk.origin.z);
    int absX = x + originX;
    int absY = y + originY;
    int absZ = z + originZ;
    Vector2f[] uv_px = CubeModel.UV_PX;
    Vector2f[] uv_nx = CubeModel.UV_NX;
    Vector2f[] uv_py = CubeModel.UV_PY;
    Vector2f[] uv_ny = CubeModel.UV_NY;
    Vector2f[] uv_pz = CubeModel.UV_PZ;
    Vector2f[] uv_nz = CubeModel.UV_NZ;
    if (type == Blocks.GRASS) {
      uv_px = CubeModel.UV_PX;
      uv_nx = CubeModel.UV_NX;
      uv_pz = CubeModel.UV_PZ;
      uv_nz = CubeModel.UV_NZ;
      uv_py = CubeModel.UV_PY;
      uv_ny = CubeModel.UV_NY;
    } else if (type == Blocks.DIRT) {
      uv_px = uv_nx = uv_py = uv_ny = uv_pz = uv_nz = CubeModel.UV_NY;
    } else if (type == Blocks.STONE) {
      uv_px = uv_nx = uv_py = uv_ny = uv_pz = uv_nz = CubeModel.UV_STONE;
    } else if (type == Blocks.TREEBARK) {
      uv_px = uv_nx = uv_pz = uv_nz = CubeModel.UV_BARK_SIDE;
      uv_py = CubeModel.UV_BARK_TOP;
      uv_ny = CubeModel.UV_BARK_TOP;
    } else if (type == Blocks.TREELEAF) {
      uv_px = uv_nx = uv_py = uv_ny = uv_pz = uv_nz = CubeModel.UV_LEAF;
    }
    // Surface height for boundary suppression
    int localSurface = surfaceHeights[z * Chunk.SIZE + x];
    // PX
    boolean pxInside = (x + 1 < Chunk.SIZE && chunk.hasLocal(x + 1, y, z));
    boolean pxCross = (x + 1 >= Chunk.SIZE && hasBlockInWorld(absX + 1, absY, absZ));
    boolean pxNeighbor = pxInside || pxCross;
    boolean pxMissingNeighborChunk = (x + 1 >= Chunk.SIZE && !pxCross);
    if (!pxNeighbor && !shouldSuppressBoundaryFace(pxMissingNeighborChunk, y, localSurface))
      addFace(CubeModel.PX_POS, uv_px, CubeModel.NORMALS[0], x, y, z);
    // NX
    boolean nxInside = (x - 1 >= 0 && chunk.hasLocal(x - 1, y, z));
    boolean nxCross = (x - 1 < 0 && hasBlockInWorld(absX - 1, absY, absZ));
    boolean nxNeighbor = nxInside || nxCross;
    boolean nxMissingNeighborChunk = (x - 1 < 0 && !nxCross);
    if (!nxNeighbor && !shouldSuppressBoundaryFace(nxMissingNeighborChunk, y, localSurface))
      addFace(CubeModel.NX_POS, uv_nx, CubeModel.NORMALS[1], x, y, z);
    // PY cross-chunk aware
    boolean pyNeighbor = (y + 1 < GameLoop.CHUNK_HEIGHT && chunk.hasLocal(x, y + 1, z))
        || (y + 1 >= GameLoop.CHUNK_HEIGHT && hasBlockInWorld(absX, absY + 1, absZ));
    if (!pyNeighbor)
      addFace(CubeModel.PY_POS, uv_py, CubeModel.NORMALS[2], x, y, z);
    // NY cross-chunk aware
    boolean nyNeighbor = (y - 1 >= 0 && chunk.hasLocal(x, y - 1, z))
        || (y - 1 < 0 && hasBlockInWorld(absX, absY - 1, absZ));
    if (!nyNeighbor)
      addFace(CubeModel.NY_POS, uv_ny, CubeModel.NORMALS[3], x, y, z);
    // PZ
    boolean pzInside = (z + 1 < Chunk.SIZE && chunk.hasLocal(x, y, z + 1));
    boolean pzCross = (z + 1 >= Chunk.SIZE && hasBlockInWorld(absX, absY, absZ + 1));
    boolean pzNeighbor = pzInside || pzCross;
    boolean pzMissingNeighborChunk = (z + 1 >= Chunk.SIZE && !pzCross);
    if (!pzNeighbor && !shouldSuppressBoundaryFace(pzMissingNeighborChunk, y, localSurface))
      addFace(CubeModel.PZ_POS, uv_pz, CubeModel.NORMALS[4], x, y, z);
    // NZ
    boolean nzInside = (z - 1 >= 0 && chunk.hasLocal(x, y, z - 1));
    boolean nzCross = (z - 1 < 0 && hasBlockInWorld(absX, absY, absZ - 1));
    boolean nzNeighbor = nzInside || nzCross;
    boolean nzMissingNeighborChunk = (z - 1 < 0 && !nzCross);
    if (!nzNeighbor && !shouldSuppressBoundaryFace(nzMissingNeighborChunk, y, localSurface))
      addFace(CubeModel.NZ_POS, uv_nz, CubeModel.NORMALS[5], x, y, z);
  }

  // Decide whether to suppress a boundary face when neighbor chunk is missing.
  private boolean shouldSuppressBoundaryFace(boolean missingNeighborChunk, int blockY, int localSurfaceY) {
    if (!ENABLE_BOUNDARY_SUPPRESSION)
      return false; // disabled globally
    if (!missingNeighborChunk)
      return false; // only consider suppression if chunk truly missing
    if (localSurfaceY < 0)
      return false; // empty column
    if (blockY <= 2)
      return false; // never hide near bedrock / base safety
    int depthFromSurface = localSurfaceY - blockY; // 0 = surface
    if (depthFromSurface < SURFACE_VISIBLE_LAYERS)
      return false; // keep top band
    return true; // deep interior side face: safe to hide
  }

  private void addFace(Vector3f[] src, Vector2f[] uvs, Vector3f normal, int bx, int by, int bz) {
    // Safety: abort if vertex count grows unreasonably large (indicative of logic
    // bug)
    if (vertices.size() > 2_000_000) {
      System.err
          .println("[ChunkMesh] Abort mesh build for chunk at " + (int) chunk.origin.x + "," + (int) chunk.origin.z +
              " due to excessive vertex count=" + vertices.size());
      return; // skip adding more
    }
    // Defensive: ensure resulting Y within valid chunk vertical bounds; if not,
    // skip face.
    // (Helps catch corruption causing vertical pillars.)
    boolean invalid = false;
    for (int k = 0; k < 6; k++) {
      float vy = src[k].y + by + 0.5f;
      if (vy < 0 || vy > GameLoop.CHUNK_HEIGHT + 1) { // slight tolerance
        invalid = true;
        break;
      }
    }
    if (invalid) {
      // Log once per chunk build (simple stdout; can be gated later)
      System.out.println("Skipped invalid face at local block (" + bx + "," + by + "," + bz + ") in chunk "
          + (int) chunk.origin.x + "," + (int) chunk.origin.z);
      return;
    }
    for (int k = 0; k < 6; k++) {
      Vector3f v = new Vector3f(src[k].x + bx + 0.5f, src[k].y + by + 0.5f, src[k].z + bz + 0.5f);
      Vector2f uv = uvs[k];
      float ao = computeVertexAO(bx, by, bz, src[k], normal);
      vertices.add(new Vertex(v, uv, normal, ao));
    }
  }

  // Compute per-vertex ambient occlusion (classic 3-side + corner rule). Vertex
  // local offset describes which corner.
  private float computeVertexAO(int bx, int by, int bz, Vector3f cornerOffset, Vector3f faceNormal) {
    int sx = cornerOffset.x > 0 ? 1 : -1; // for faces we only need sign
    int sy = cornerOffset.y > 0 ? 1 : -1;
    int sz = cornerOffset.z > 0 ? 1 : -1;

    boolean sideA, sideB, corner;

    if (faceNormal.x != 0) { // +/-X face: tangent axes Y,Z (sample within same block column)
      sideA = solidLocal(bx, by + sy, bz); // along Y
      sideB = solidLocal(bx, by, bz + sz); // along Z
      corner = solidLocal(bx, by + sy, bz + sz); // diagonal inside
    } else if (faceNormal.y != 0) { // +/-Y face: tangent axes X,Z
      sideA = solidLocal(bx + sx, by, bz); // along X
      sideB = solidLocal(bx, by, bz + sz); // along Z
      corner = solidLocal(bx + sx, by, bz + sz);
    } else { // Z face: tangent axes X,Y
      sideA = solidLocal(bx + sx, by, bz); // along X
      sideB = solidLocal(bx, by + sy, bz); // along Y
      corner = solidLocal(bx + sx, by + sy, bz);
    }

    int occ;
    if (sideA && sideB) {
      // If both orthogonal sides are filled, treat as maximum occlusion (corner
      // hidden)
      occ = 3;
    } else {
      occ = (sideA ? 1 : 0) + (sideB ? 1 : 0) + (corner ? 1 : 0);
    }
    // Softer brightness LUT (reduced contrast)
    // 0:1.00, 1:0.85, 2:0.70, 3:0.55
    float[] lut = { 1.0f, 0.85f, 0.70f, 0.55f };
    return lut[occ];
  }

  private boolean solidLocal(int lx, int ly, int lz) {
    if (ly < 0 || ly >= GameLoop.CHUNK_HEIGHT)
      return false;
    if (lx < 0 || lx >= Chunk.SIZE || lz < 0 || lz >= Chunk.SIZE) {
      // Cross chunk sample
      int gx = (int) chunk.origin.x + lx;
      int gy = (int) chunk.origin.y + ly;
      int gz = (int) chunk.origin.z + lz;
      return hasBlockInWorld(gx, gy, gz);
    }
    return chunk.hasLocal(lx, ly, lz);
  }

  // Fallback: check the persistent worldChunks map for a block at absolute
  // coordinates (neighbor may belong to a different chunk). Uses chunkSize=16.
  private boolean hasBlockInWorld(int absX, int absY, int absZ) {
    final int chunkSize = 16;
    final int chunkHeight = GameLoop.CHUNK_HEIGHT;
    int originX = Math.floorDiv(absX, chunkSize) * chunkSize;
    int originY = Math.floorDiv(absY, chunkHeight) * chunkHeight;
    int originZ = Math.floorDiv(absZ, chunkSize) * chunkSize;
    String key = originX + "|" + originY + "|" + originZ;
    Chunk c = GameLoop.worldChunks.get(key);
    if (c == null)
      return false;
    int localX = absX - originX;
    int localY = absY - originY;
    int localZ = absZ - originZ;
    return c.hasLocal(localX, localY, localZ);
  }

  private void populateLists() {
    for (int i = 0; i < vertices.size(); i++) {
      positionList.add(vertices.get(i).positions.x);
      positionList.add(vertices.get(i).positions.y);
      positionList.add(vertices.get(i).positions.z);
      uvsList.add(vertices.get(i).uvs.x);
      uvsList.add(vertices.get(i).uvs.y);
      normalsList.add(vertices.get(i).normals.x);
      normalsList.add(vertices.get(i).normals.y);
      normalsList.add(vertices.get(i).normals.z);
      aoList.add(vertices.get(i).ao);
    }

    positions = new float[positionList.size()];
    uvs = new float[uvsList.size()];
    normals = new float[normalsList.size()];
    aos = new float[aoList.size()];

    for (int i = 0; i < positionList.size(); i++) {
      positions[i] = positionList.get(i);
    }
    for (int i = 0; i < uvsList.size(); i++) {
      uvs[i] = uvsList.get(i);
    }
    for (int i = 0; i < normalsList.size(); i++) {
      normals[i] = normalsList.get(i);
    }
    for (int i = 0; i < aoList.size(); i++) {
      aos[i] = aoList.get(i);
    }

    positionList.clear();
    uvsList.clear();
    normalsList.clear();
    aoList.clear();
  }
}
