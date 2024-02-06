package Chunks;

import java.util.List;

import org.lwjgl.util.vector.Vector3f;

import Cube.Blocks;

public class Chunk {
  public List<Blocks> blocks;
  public Vector3f origin;

  public Chunk(List<Blocks> blocks, Vector3f origin) {
    this.blocks = blocks;
    this.origin = origin;
  }
}
