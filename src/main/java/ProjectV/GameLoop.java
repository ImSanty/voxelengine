package ProjectV;

import java.util.ArrayList;
import java.util.Collections;
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
  static List<ChunkMesh> chunks = Collections.synchronizedList(new ArrayList<ChunkMesh>());
  static List<Vector3f> usedPos = new ArrayList<Vector3f>();
  static List<Entity> entities = new ArrayList<Entity>();

  int fpsCap = 240;
  int tickRate = 60;
  static final int chunkSize = 16;
  static final int renderDistance = 16 * chunkSize;

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
    ModelTextures textures = new ModelTextures(loader.loadTexture("spruce_planks"));
    Camera camera = new Camera(new Vector3f(0, 2f, 0), 0, 0, 0);
    PerlinNoiseGenerator generator = new PerlinNoiseGenerator();

    // Thread-1 - Terrain Generation
    while (!Display.isCloseRequested()) {
      new Thread(new Runnable() {
        public void run() {
          while (!Display.isCloseRequested()) {
            for (int x = (int) (camPos.x - renderDistance) / chunkSize; x < (camPos.x +
                renderDistance)
                / chunkSize; x++) {
              for (int z = (int) (camPos.z - renderDistance) / chunkSize; z < (camPos.z +
                  renderDistance)
                  / chunkSize; z++) {
                if (!usedPos.contains(new Vector3f(x * chunkSize, 0 * chunkSize, z *
                    chunkSize))) {
                  List<Blocks> blocks = new ArrayList<Blocks>();
                  for (int i = 0; i < chunkSize; i++) {
                    for (int j = 0; j < chunkSize; j++) {
                      blocks.add(new Blocks(i, (int) generator.generateHeight(i + (x * chunkSize), j + (z * chunkSize)),
                          j, Blocks.TYPE.DIRT));
                    }
                  }
                  Chunk chunk = new Chunk(blocks, new Vector3f(x * chunkSize, 0, z * chunkSize));
                  ChunkMesh mesh = new ChunkMesh(chunk);

                  chunks.add(mesh);
                  usedPos.add(new Vector3f(x * chunkSize, 0 * chunkSize, z * chunkSize));
                }
              }
            }
          }
        }
      }).start();

      while (!Display.isCloseRequested()) {
        render(renderer, camera, textures);

        // Debug
        long now = System.nanoTime();
        deltaUpdate += (now - lastTime) / nsPerUpdate;
        deltaRender += (now - lastTime) / nsPerRender;
        lastTime = now;

        while (deltaUpdate >= 1) {
          update(renderer);
          deltaUpdate--;
          tickCount++;
        }

        while (deltaRender >= 1) {
          deltaRender--;
          frameCount++;
        }

        // Draw Time
        long drawStart = System.nanoTime();
        long drawEnd = System.nanoTime();
        long passed = drawEnd - drawStart;
        drawTime = passed;

      }
      DisplayManager.closeDisplay();
    }
  }

  // Update Thread
  private void update(MasterRenderer renderer) {
    if (System.currentTimeMillis() - timer > 1000) {
      timer += 1000;
      showFPS = frameCount;
      Display.setTitle(
          "Voxel Engine - dev build | Fps: " + showFPS + " - Tps: " + tickCount + " - DrawTime: " + drawTime
              + "ns");
      frameCount = 0;
      tickCount = 0;
    }
  }

  // Render Thread
  int index = 0;

  private void render(MasterRenderer renderer, Camera camera, ModelTextures textures) {
    Display.update();
    camera.move();
    camPos = camera.getPosition();
    renderer.render(camera);
    displayManager.KeyHandler();

    if (index < chunks.size()) {
      RawModel model123 = loader.loadToVAO(chunks.get(index).positions, chunks.get(index).uvs);
      TexturedModel texModel123 = new TexturedModel(model123, textures);
      Entity entity = new Entity(texModel123, chunks.get(index).chunk.origin, 0, 0, 0, 1);
      entities.add(entity);
      chunks.get(index).positions = null;
      chunks.get(index).normals = null;
      chunks.get(index).uvs = null;

      index++;
    }

    for (int i = 0; i < entities.size(); i++) {
      Vector3f origin = entities.get(i).getPosition();
      int distX = (int) (camPos.x - origin.x);
      int distZ = (int) (camPos.z - origin.z);

      if (distX < 0) {
        distX = -distX;
      }
      if (distZ < 0) {
        distZ = -distZ;
      }
      if ((distX <= renderDistance) && (distZ <= renderDistance)) {
        renderer.addEntity(entities.get(i));
      }
    }
  }
}