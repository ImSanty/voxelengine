package ProjectV;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.lwjgl.opengl.Display;
import org.lwjgl.util.vector.Vector3f;

import Chunks.Chunk;
import Chunks.ChunkMesh;
import Cube.Blocks;
import Entities.Camera;
import Entities.Entity;
import Models.RawModel;
import Models.TexturedModel;
import RenderEngine.DisplayManager;
import RenderEngine.Loader;
import RenderEngine.MasterRenderer;
import Shaders.StaticShader;
import Textures.ModelTextures;
import ToolBox.PerlinNoiseGenerator;

public class GameLoop {
  private DisplayManager displayManager = new DisplayManager();
  public static StaticShader shader;
  public static Loader loader = new Loader();
  static Vector3f camPos = new Vector3f(0, 0, 0);
  static List<ChunkMesh> chunks = Collections.synchronizedList(new ArrayList<>());
  static List<Vector3f> usedPos = Collections.synchronizedList(new ArrayList<>());
  static List<Entity> entities = Collections.synchronizedList(new ArrayList<>());
  PerlinNoiseGenerator generator = new PerlinNoiseGenerator();

  int fpsCap = 240;
  int tickRate = 60;
  static final int chunkSize = 16;
  static final int renderDistance = 25 * chunkSize;
  static final int maxChunksPerIteration = 10;

  int showFPS;
  long drawTime;
  long lastTime = System.nanoTime();
  double nsPerUpdate = 1000000000.0 / tickRate;
  double nsPerRender = 1000000000.0 / fpsCap;
  double deltaUpdate = 0;
  double deltaRender = 0;
  long timer = System.currentTimeMillis();
  int frameCount = 0;
  int tickCount = 0;

  // Game Loop Core
  public void startGameLoop() {
    displayManager.createDisplay();
    GameLoop.shader = new StaticShader();
    MasterRenderer renderer = new MasterRenderer();
    ModelTextures textures = new ModelTextures(loader.loadTexture("DefaultPack"));
    Camera camera = new Camera(new Vector3f(0, 50f, 0), 0, 0, 0);

    // Terrain Generation Thread
    new Thread(() -> {
      while (!Display.isCloseRequested()) {
        generateTerrain();
      }
    }).start();

    // Main Game Loop
    while (!Display.isCloseRequested()) {
      render(renderer, camera, textures);
      update(renderer);
    }

    // Clean up
    DisplayManager.closeDisplay();
  }

  // Terrain generation logic
  private void generateTerrain() {
    int startX = ((int) camPos.x - renderDistance) / chunkSize;
    int startZ = ((int) camPos.z - renderDistance) / chunkSize;
    int endX = ((int) camPos.x + renderDistance) / chunkSize;
    int endZ = ((int) camPos.z + renderDistance) / chunkSize;

    List<Vector3f> newChunkPositions = new ArrayList<>();

    for (int x = startX; x <= endX; x++) {
      for (int z = startZ; z <= endZ; z++) {
        Vector3f chunkPos = new Vector3f(x * chunkSize, 0, z * chunkSize);
        newChunkPositions.add(chunkPos);
      }
    }

    // Sort the new chunk positions by their distance from the camera
    newChunkPositions.sort(Comparator.comparingDouble(
        pos -> Math.sqrt((pos.x - camPos.x) * (pos.x - camPos.x) + (pos.z - camPos.z) * (pos.z - camPos.z))));

    // Generate chunks in batches
    int chunksGenerated = 0;
    for (Vector3f chunkPos : newChunkPositions) {
      if (chunksGenerated >= maxChunksPerIteration) {
        break;
      }
      if (!usedPos.contains(chunkPos)) {
        List<Blocks> blocks = new ArrayList<>();
        for (int i = 0; i < chunkSize; i++) {
          for (int j = 0; j < chunkSize; j++) {
            blocks.add(new Blocks(i, (int) generator.generateHeight((int) chunkPos.x + i, (int) chunkPos.z + j),
                j, Blocks.GRASS));
          }
        }
        Chunk chunk = new Chunk(blocks, chunkPos);
        ChunkMesh mesh = new ChunkMesh(chunk);
        chunks.add(mesh);
        usedPos.add(chunkPos);
        chunksGenerated++;
      }
    }

  }

  // Update Thread
  private void update(MasterRenderer renderer) {
    // Draw Time
    long drawStart = System.nanoTime();
    long drawEnd = System.nanoTime();
    long passed = drawEnd - drawStart;
    drawTime = passed;

    if (System.currentTimeMillis() - timer > 1000) {
      timer += 1000;
      showFPS = frameCount;
      Display.setTitle(
          "Voxel Engine - dev build | Fps: " + showFPS + " - Tps: " + tickCount + " - DrawTime: " + drawTime + "ns");
      frameCount = 0;
      tickCount = 0;
    }
  }

  // Render Thread
  private void render(MasterRenderer renderer, Camera camera, ModelTextures textures) {
    Display.update();
    camera.move();
    camPos = camera.getPosition();
    renderer.render(camera);
    displayManager.KeyHandler();

    synchronized (chunks) {
      for (int i = 0; i < chunks.size(); i++) {
        ChunkMesh chunk = chunks.get(i);
        RawModel model = loader.loadToVAO(chunk.positions, chunk.uvs);
        TexturedModel texModel = new TexturedModel(model, textures);
        Entity entity = new Entity(texModel, chunk.chunk.origin, 0, 0, 0, 1);
        entities.add(entity);
        chunk.positions = null;
        chunk.normals = null;
        chunk.uvs = null;
      }
      chunks.clear();
    }

    synchronized (entities) {
      for (Entity entity : entities) {
        Vector3f origin = entity.getPosition();
        int distX = (int) (camPos.x - origin.x);
        int distZ = (int) (camPos.z - origin.z);
        if (Math.abs(distX) <= renderDistance && Math.abs(distZ) <= renderDistance) {
          renderer.addEntity(entity);
        }
      }
    }
  }
}
