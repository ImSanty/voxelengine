package Chunks;

import java.util.List;
import java.util.BitSet;

import org.lwjgl.util.vector.Vector3f;

import Cube.Blocks;

public class Chunk {
  public List<Blocks> blocks;
  public Vector3f origin;
  public BitSet occupancy; // bit per (x,y,z) for fast neighbor queries
  public static final int SIZE = 16; // assume constant
  private byte[] types; // block type per (x,y,z); 0=air
  private boolean compacted = false;
  private int blockCount = 0; // original number of blocks
  // Track a lightweight adjacency signature (N,E,S,W presence). Used to trigger
  // a single remesh when neighbor configuration changes, avoiding repeated
  // scheduling storms.
  public volatile byte neighborMask = 0; // bit0=W, bit1=E, bit2=N, bit3=S

  public Chunk(List<Blocks> blocks, Vector3f origin) {
    this.blocks = blocks;
    this.origin = origin;
    this.blockCount = (blocks != null) ? blocks.size() : 0;
    this.lastUsedMs = System.currentTimeMillis();
    buildOccupancy();
  }

  // Optimized constructor for pre-built occupancy/types (avoids allocating a
  // Blocks list)
  public Chunk(java.util.BitSet occupancy, byte[] types, int blockCount, Vector3f origin) {
    this.blocks = null; // no raw list
    this.origin = origin;
    this.occupancy = occupancy;
    this.types = types;
    this.blockCount = blockCount;
    this.compacted = true; // already compact
    this.lastUsedMs = System.currentTimeMillis();
  }

  // --- Activity Tracking (for smarter unload hysteresis) ---
  public volatile long lastUsedMs; // last time (ms) this chunk was rendered / needed

  public void touch() {
    lastUsedMs = System.currentTimeMillis();
  }

  public long timeSinceLastUse(long nowMs) {
    return nowMs - lastUsedMs;
  }

  private void buildOccupancy() {
    int height = ProjectV.GameLoop.CHUNK_HEIGHT;
    occupancy = new BitSet(SIZE * height * SIZE);
    types = new byte[SIZE * height * SIZE];
    for (Blocks b : blocks) {
      if (b.x >= 0 && b.x < SIZE && b.z >= 0 && b.z < SIZE && b.y >= 0 && b.y < height) {
        int idx = index(b.x, b.y, b.z);
        occupancy.set(idx);
        types[idx] = (byte) b.type;
      }
    }
  }

  private int index(int x, int y, int z) {
    return (y * SIZE + z) * SIZE + x;
  }

  public boolean hasLocal(int x, int y, int z) {
    int height = ProjectV.GameLoop.CHUNK_HEIGHT;
    if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || y < 0 || y >= height)
      return false;
    return occupancy.get(index(x, y, z));
  }

  public int getType(int x, int y, int z) {
    int height = ProjectV.GameLoop.CHUNK_HEIGHT;
    if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || y < 0 || y >= height)
      return 0;
    return types[index(x, y, z)] & 0xFF;
  }

  // Mutate a local block (0 = air). Returns true if changed.
  public synchronized boolean setLocal(int x, int y, int z, int type) {
    int height = ProjectV.GameLoop.CHUNK_HEIGHT;
    if (x < 0 || x >= SIZE || z < 0 || z >= SIZE || y < 0 || y >= height)
      return false;
    int idx = index(x, y, z);
    int prevType = types[idx] & 0xFF;
    boolean wasOcc = occupancy.get(idx);
    boolean nowOcc = type != 0;
    // If nothing changes (both type and occupancy state), early exit
    if (prevType == (type & 0xFF) && wasOcc == nowOcc)
      return false;
    // Update type value regardless (texture id); occupancy is the solidity source
    // of truth
    types[idx] = (byte) (type & 0xFF);
    if (wasOcc != nowOcc) {
      occupancy.set(idx, nowOcc);
      if (nowOcc)
        blockCount++;
      else
        blockCount--;
      if (blockCount < 0)
        blockCount = 0;
    }
    compacted = true; // post-gen form
    touch();
    return true;
  }

  public Vector3f getOrigin() {
    return origin;
  }

  public void compact() {
    if (compacted)
      return;
    // free heavy list after transferring to array
    if (blocks != null) {
      blocks.clear();
      blocks = null;
    }
    compacted = true;
  }

  public boolean isCompacted() {
    return compacted;
  }

  public int getBlockCount() {
    return blockCount;
  }
}
