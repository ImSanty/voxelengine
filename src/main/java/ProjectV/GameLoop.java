package ProjectV;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.atomic.LongAdder;

import org.lwjgl.opengl.Display;
import org.lwjgl.input.Mouse;
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
import RenderEngine.TextRenderer;
import Shaders.StaticShader;
import Textures.ModelTextures;
import ToolBox.PerlinNoiseGenerator;

public class GameLoop {
  // ===== Core Engine Objects =====
  private DisplayManager displayManager = new DisplayManager();
  public static StaticShader shader;
  public static Loader loader = new Loader();
  static Vector3f camPos = new Vector3f(0, 0, 0);
  static List<ChunkMesh> chunks = Collections.synchronizedList(new ArrayList<>()); // legacy
  static java.util.Set<String> usedPos = java.util.Collections.synchronizedSet(new java.util.HashSet<>()); // legacy
  static List<Entity> entities = Collections.synchronizedList(new ArrayList<>()); // legacy

  // ===== World / Generation State =====
  static int lastBaseChunkX = Integer.MIN_VALUE;
  static int lastBaseChunkZ = Integer.MIN_VALUE;
  static java.util.List<Vector3f> generationQueue = Collections.synchronizedList(new ArrayList<>());
  static int generationIndex = 0;
  public static Map<String, Chunk> worldChunks = Collections.synchronizedMap(new HashMap<>());

  // ===== Caches =====
  // (Define MAX_LOADED_CHUNKS first, then derive CACHE_CAP below)
  static java.util.Map<String, RawModel> rawModelCache = Collections
      .synchronizedMap(new LinkedHashMap<String, RawModel>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RawModel> eldest) {
          return size() > CACHE_CAP;
        }
      });

  // Entity cache per chunk origin
  static java.util.Map<String, Entity> entityCache = Collections.synchronizedMap(new HashMap<>());
  static final int ENTITY_CACHE_CAP = 512; // legacy cap, not aggressively enforced
  // Raise loaded chunk cap to reduce unload/reload thrash when moving fast;
  // memory usage is still modest.
  static final int MAX_LOADED_CHUNKS = 1000; // slightly lower to reduce memory pressure
  // Raw model (VAO) cache capacity. Previously fixed small (256) causing eviction
  // churn.
  static final int CACHE_CAP = MAX_LOADED_CHUNKS + 64;

  // Chunks awaiting remesh (face changes)
  static java.util.Set<String> remeshPending = Collections.synchronizedSet(new HashSet<>());
  // Deterministic world seed
  static final int WORLD_SEED = 321316789; // change for new world
  static final PerlinNoiseGenerator GENERATOR = new PerlinNoiseGenerator(WORLD_SEED);

  // Mesh build workers
  static final ExecutorService meshExecutor = Executors
      .newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
  // Completed meshes pending VAO upload (render thread only)
  static final ConcurrentLinkedQueue<ChunkMesh> completedMeshes = new ConcurrentLinkedQueue<>();
  // Chunk generation workers
  static final ExecutorService chunkGenExecutor = Executors
      .newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
  // Newly generated chunks pending mesh
  static final ConcurrentLinkedQueue<Chunk> completedChunkGenerations = new ConcurrentLinkedQueue<>();
  // Limits / counters
  // Allow higher parallel chunk generation to prevent checkerboard gaps when
  // moving quickly. (Was *3) Raise to *5 minimum 16.
  static final int MAX_CHUNK_GENERATIONS = Math.max(12, Runtime.getRuntime().availableProcessors() * 4);
  static volatile int chunkGenerationsInProgress = 0;

  int fpsCap = 240;
  int tickRate = 60;
  static final int chunkSize = 16;
  // Mesh build concurrency limit
  // Allow more concurrent mesh builds (was half cores capped by /2). Use up to
  // cores-1 but at least 2.
  static final int MAX_MESH_BUILDS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2 + 1);
  static volatile int meshBuildsInProgress = 0;
  static final boolean FAST_MODE = true;
  // Mesh scheduling queues
  static final ConcurrentLinkedQueue<Chunk> pendingNewMesh = new ConcurrentLinkedQueue<>();
  static final ConcurrentLinkedQueue<Chunk> pendingRemesh = new ConcurrentLinkedQueue<>();
  static final java.util.Set<String> scheduledMeshKeys = java.util.Collections
      .synchronizedSet(new java.util.HashSet<>());
  // Track generation tasks that have been scheduled but not yet inserted into
  // worldChunks (prevents duplicate scheduling)
  static final java.util.Set<String> inflightGeneration = java.util.Collections
      .synchronizedSet(new java.util.HashSet<>());
  // Chunk vertical size
  public static final int CHUNK_HEIGHT = 512;
  // Air gap above surface
  public static final int MIN_AIR_ABOVE = 16;
  // Minimum ground thickness (including top)
  public static final int MIN_GROUND_DEPTH = 8; // reduced from 128
  public static final int TOP_SOIL_DEPTH = 2; // grass + dirt depth
  public static final boolean SOLID_COLUMNS = true; // solid columns from y=0 up to surface
  // View distance in blocks (square / Chebyshev)
  static final int renderDistance = 8 * chunkSize;

  /** Public accessor for render distance in blocks (Chebyshev axis limit). */
  public static int getRenderDistance() {
    return renderDistance;
  }

  // Max new chunk meshes per pass (before adaptation)
  // Upper cap on per-iteration generation scheduling (raised from 64 to 128 to
  // fill distant gaps faster when player speed is high).
  static final int maxChunksPerIteration = 64; // slightly reduced to smooth CPU bursts
  // Adaptive burst (auto tuned)
  static volatile int dynamicChunkBurst = maxChunksPerIteration;
  // Terrain shaping
  static final boolean SMOOTH_TERRAIN = false;
  static final float SMOOTH_BLEND = 0.75f; // weight of smoothed height (0..1)
  // RAW_BLEND complements SMOOTH_BLEND (sum = 1)
  static final float RAW_BLEND = 1f - SMOOTH_BLEND; // weight of raw noise height
  // Optional slope limiting
  static final boolean LIMIT_SLOPE = false; // disable spike limiter to restore natural variation
  static final int MAX_SLOPE_DELTA = 32; // retained for potential future use
  static final int SLOPE_PASSES = 1; // number of limiter passes
  // Vertical scaling factor
  static final float TERRAIN_HEIGHT_FACTOR = 1.0f; // full vertical range
  // Smoothing kernel radius
  static final int SMOOTH_RADIUS = 1;
  // Per-chunk BitSet handles neighbor culling (no global occupancy)
  // Performance target
  static final double TARGET_FRAME_MS = 1000.0 / 144.0; // assume desired smooth frame; can tie to fpsCap
  static long lastAdaptTime = System.currentTimeMillis();
  // Mesh upload pacing (prevent large spikes on render thread)
  static final int MAX_MESH_UPLOADS_PER_FRAME = 4; // hard cap per frame
  static final long MAX_MESH_UPLOAD_BUDGET_NS = 2_000_000; // ~2ms budget for VAO creation (tweak)
  static int lastFrameMeshUploads = 0;
  // Debug render mode (cycled with F3):
  // 0=Textured,1=Wireframe,2=Normals,3=Height,4=FaceDirections
  public static volatile int debugRenderMode = 0;
  // Separate toggle (F3+B) for player bounding box visualization
  public static volatile boolean showPlayerBounds = false;
  // Toggle overall debug HUD (toggled with plain F3 now)
  public static volatile boolean showDebugHUD = true;

  int showFPS;
  int showTPS;
  static final java.util.concurrent.atomic.AtomicLong blocksGenerated = new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicLong chunksGenerated = new java.util.concurrent.atomic.AtomicLong();
  static final java.util.concurrent.atomic.AtomicLong loadedBlocks = new java.util.concurrent.atomic.AtomicLong();
  long lastBlocksGeneratedSnapshot = 0;
  long lastChunksGeneratedSnapshot = 0;
  long drawTime;
  // Diagnostics
  static volatile int dbgPendingGenQueue = 0;
  static volatile int dbgPendingMeshQueue = 0;
  static final LongAdder genTimeNs = new LongAdder();
  static final LongAdder genTasks = new LongAdder();
  static final LongAdder meshTimeNs = new LongAdder();
  static final LongAdder meshTasks = new LongAdder();
  // Memory diagnostics
  static long lastMemUsed = 0;
  static long lastMemSampleTime = System.currentTimeMillis();
  static long memUsed = 0;
  static long memDelta = 0;
  static long maxHeap = Runtime.getRuntime().maxMemory();
  static final double HEAP_SOFT_LIMIT_FRACTION = 0.80; // slow generation above 80%
  static final double HEAP_HARD_LIMIT_FRACTION = 0.90; // pause generation above 90%
  static long lastMemCheckNs = 0L;
  long lastTime = System.nanoTime();
  double nsPerUpdate = 1000000000.0 / tickRate;
  double nsPerRender = 1000000000.0 / fpsCap;
  double deltaUpdate = 0;
  double deltaRender = 0;
  long lastCleanupTime = System.currentTimeMillis();
  static final long CLEANUP_INTERVAL_MS = 2000; // unload cadence ms
  // Extra keep radius beyond renderDistance before a chunk is eligible for
  // unload.
  // Previously this was 1 chunk (16 blocks) which caused thrashing (rapid
  // unload/reload)
  // at high camera speeds, leading to visible "holes" as border chunks
  // disappeared
  // while their replacements had not finished generation/meshing yet. We now
  // provide
  // a larger hysteresis margin. You can tune HYSTERESIS_CHUNKS; 3 gives 48 block
  // buffer.
  static final int UNLOAD_HYSTERESIS_CHUNKS = 3; // how many extra chunks past view to keep
  static final int UNLOAD_MARGIN_BLOCKS = UNLOAD_HYSTERESIS_CHUNKS * chunkSize; // derived buffer in blocks
  // Minimum time (ms) a chunk must remain unused (not rendered) after leaving the
  // hysteresis boundary before it is eligible for unload. Prevents rapid
  // disappear/reappear of top faces at movement edges.
  static final long UNLOAD_IDLE_MS = 5_000; // 5s grace
  long timer = System.currentTimeMillis();
  int frameCount = 0;
  int tickCount = 0;
  TextRenderer textRenderer = new TextRenderer();
  private Camera camera;
  // Input debounce
  private boolean prevLeftDown = false;

  // Game Loop Core
  public void startGameLoop() {
    displayManager.createDisplay();
    GameLoop.shader = new StaticShader();
    MasterRenderer renderer = new MasterRenderer();
    ModelTextures textures = new ModelTextures(loader.loadTexture("DefaultPack"));
    camera = new Camera(new Vector3f(0, 5f, 0), 0, 0, 0);
    textRenderer.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 18));

    // --- Terrain Generation Thread ---
    new Thread(() -> {
      // Wait for display creation
      while (!Display.isCreated()) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e) {
          // ignored
        }
      }

      // Generate while display lives
      while (Display.isCreated() && !Display.isCloseRequested()) {
        try {
          generateTerrain();
          // Adaptive throttle: lighter sleep if queue large
          int q = generationQueue.size() - generationIndex;
          if (q > 256) {
            Thread.sleep(2);
          } else if (q > 64) {
            Thread.sleep(5);
          } else {
            Thread.sleep(12);
          }
        } catch (InterruptedException e) {
          // ignored
        } catch (Throwable t) {
          // Keep thread alive despite errors
          t.printStackTrace();
        }
      }
    }).start();

    // --- Chunk Unload Thread ---
    new Thread(() -> {
      while (Display.isCreated() && !Display.isCloseRequested()) {
        try {
          unloadFarChunks();
          Thread.sleep(CLEANUP_INTERVAL_MS);
        } catch (InterruptedException ie) {
          // ignore
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }
    }, "Chunk-Unload-Thread").start();

    // --- Mesh Scheduler Thread ---
    new Thread(() -> {
      while (Display.isCreated() && !Display.isCloseRequested()) {
        try {
          // Prefer remesh tasks first
          if (meshBuildsInProgress < MAX_MESH_BUILDS) {
            Chunk polled = pendingRemesh.poll();
            if (polled == null)
              polled = pendingNewMesh.poll();
            if (polled != null) {
              meshBuildsInProgress++;
              final Chunk task = polled;
              meshExecutor.submit(() -> {
                try {
                  long meshStart = System.nanoTime();
                  ChunkMesh mesh = new ChunkMesh(task);
                  completedMeshes.add(mesh);
                  long meshEnd = System.nanoTime();
                  meshTimeNs.add(meshEnd - meshStart);
                  meshTasks.increment();
                } finally {
                  meshBuildsInProgress--;
                }
              });
              continue; // loop again if capacity remains
            }
          }
          Thread.sleep(3);
        } catch (InterruptedException ie) {
          // ignore
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }
    }, "Mesh-Scheduler").start();

    // --- Main Game Loop ---
    long lastTimeLocal = System.nanoTime();
    double logicAccumulator = 0.0;
    while (!Display.isCloseRequested()) {
      long now = System.nanoTime();
      long frameNanos = now - lastTimeLocal;
      lastTimeLocal = now;
      logicAccumulator += frameNanos;

      // Fixed updates
      while (logicAccumulator >= nsPerUpdate) {
        update(renderer); // counts ticks internally
        logicAccumulator -= nsPerUpdate;
      }

      // Render pass
      render(renderer, camera, textures);

      // Optional FPS cap
      if (fpsCap > 0) {
        long postRender = System.nanoTime();
        long targetFrameNanos = (long) nsPerRender;
        long used = postRender - now;
        long remaining = targetFrameNanos - used;
        if (remaining > 100000) { // >0.1ms
          try {
            Thread.sleep(Math.max(0L, remaining / 1_000_000L));
          } catch (InterruptedException ie) {
          }
        }
      }
    }

    // Shutdown
    DisplayManager.closeDisplay();
  }

  // ===== Terrain Generation =====
  private void generateTerrain() {
    // Back-pressure: if too many completed meshes awaiting upload, pause new
    // terrain generation
    int pendingUploadsSnapshot = completedMeshes.size();
    if (pendingUploadsSnapshot > 1500) {
      // Strong back-pressure: skip scheduling entirely this cycle
      return;
    } else if (pendingUploadsSnapshot > 800) {
      // Light back-pressure: halve dynamic burst to slow inflow
      dynamicChunkBurst = Math.max(1, dynamicChunkBurst / 2);
    }
    // Periodic memory pressure check (~50ms)
    long nowNsCheck = System.nanoTime();
    if (nowNsCheck - lastMemCheckNs > 50_000_000L) { // 50ms
      Runtime rt = Runtime.getRuntime();
      long used = rt.totalMemory() - rt.freeMemory();
      memUsed = used;
      lastMemCheckNs = nowNsCheck;
      double frac = (double) used / (double) rt.maxMemory();
      if (frac >= HEAP_HARD_LIMIT_FRACTION) {
        return; // hard stop scheduling this cycle
      } else if (frac >= HEAP_SOFT_LIMIT_FRACTION) {
        dynamicChunkBurst = Math.max(1, dynamicChunkBurst / 2);
      }
    }
    // Queue/generate chunks in concentric priority around camera
    int baseChunkX = Math.floorDiv((int) Math.floor(camPos.x), chunkSize) * chunkSize;
    int baseChunkZ = Math.floorDiv((int) Math.floor(camPos.z), chunkSize) * chunkSize;

    // Rebuild queue only when entering new base chunk (prevents oscillation)
    int chunkRadius = Math.max(1, (int) Math.ceil((double) renderDistance / chunkSize));
    // Rebuild conditions
    boolean needRebuild = (baseChunkX != lastBaseChunkX) || (baseChunkZ != lastBaseChunkZ);
    if (!needRebuild && generationQueue.isEmpty()) {
      // Nothing new to generate; world around current base chunk already populated.
      return;
    }
    if (needRebuild) {
      generationQueue.clear();
      generationIndex = 0;
      // Build diamond-first order then square remainder
      java.util.List<int[]> diamondOffsets = new java.util.ArrayList<>();
      for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
          if (Math.abs(dx) + Math.abs(dz) <= chunkRadius) {
            diamondOffsets.add(new int[] { dx, dz });
          }
        }
      }

      // Sort by manhattan, cardinals before diagonals
      java.util.Collections.sort(diamondOffsets, (a, b) -> {
        int ad = Math.abs(a[0]) + Math.abs(a[1]);
        int bd = Math.abs(b[0]) + Math.abs(b[1]);
        if (ad != bd)
          return Integer.compare(ad, bd);
        int aAxis = (a[0] == 0 || a[1] == 0) ? 0 : 1;
        int bAxis = (b[0] == 0 || b[1] == 0) ? 0 : 1;
        if (aAxis != bAxis)
          return Integer.compare(aAxis, bAxis);
        int aMax = Math.max(Math.abs(a[0]), Math.abs(a[1]));
        int bMax = Math.max(Math.abs(b[0]), Math.abs(b[1]));
        return Integer.compare(aMax, bMax);
      });

      // Put camera chunk first if applicable
      java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
      int camChunkX = Math.floorDiv((int) Math.floor(camPos.x), chunkSize) * chunkSize;
      int camChunkZ = Math.floorDiv((int) Math.floor(camPos.z), chunkSize) * chunkSize;
      int dxCam = (camChunkX - baseChunkX) / chunkSize;
      int dzCam = (camChunkZ - baseChunkZ) / chunkSize;
      if (Math.abs(dxCam) + Math.abs(dzCam) <= chunkRadius) {
        ordered.add(camChunkX + "," + camChunkZ);
      }

      // Local seed ordering
      int[][] preferred = new int[][] { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 },
          { -1, 1 }, { -1, -1 } };
      for (int[] p : preferred) {
        int ox = baseChunkX + p[0] * chunkSize;
        int oz = baseChunkZ + p[1] * chunkSize;
        if (Math.abs(p[0]) + Math.abs(p[1]) <= chunkRadius) {
          ordered.add(ox + "," + oz);
        }
      }

      // Remaining diamond
      for (int[] off : diamondOffsets) {
        int ox = baseChunkX + off[0] * chunkSize;
        int oz = baseChunkZ + off[1] * chunkSize;
        String key = ox + "," + oz;
        ordered.add(key); // set handles duplicates
      }

      // Square remainder
      java.util.List<int[]> squareRemainder = new java.util.ArrayList<>();
      for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
          if (Math.abs(dx) + Math.abs(dz) > chunkRadius) { // outside diamond
            squareRemainder.add(new int[] { dx, dz });
          }
        }
      }
      java.util.Collections.sort(squareRemainder, (a, b) -> {
        int aMax = Math.max(Math.abs(a[0]), Math.abs(a[1]));
        int bMax = Math.max(Math.abs(b[0]), Math.abs(b[1]));
        if (aMax != bMax)
          return Integer.compare(aMax, bMax);
        int aMan = Math.abs(a[0]) + Math.abs(a[1]);
        int bMan = Math.abs(b[0]) + Math.abs(b[1]);
        return Integer.compare(aMan, bMan);
      });
      for (int[] off : squareRemainder) {
        int ox = baseChunkX + off[0] * chunkSize;
        int oz = baseChunkZ + off[1] * chunkSize;
        String key = ox + "," + oz;
        ordered.add(key);
      }

      // Materialize queue
      for (String s : ordered) {
        String[] parts = s.split(",");
        int ox = Integer.parseInt(parts[0]);
        int oz = Integer.parseInt(parts[1]);
        generationQueue.add(new Vector3f(ox, 0, oz));
      }
      // Record base
      lastBaseChunkX = baseChunkX;
      lastBaseChunkZ = baseChunkZ;
    }

    // Collect newly generated chunks
    java.util.List<Chunk> rawNewChunks = new java.util.ArrayList<>();
    // Process a batch (adaptive burst)
    int remaining = generationQueue.size() - generationIndex;
    // Determine how many chunk positions to process this iteration.
    // Optionally scale down when far from the camera to smooth CPU usage.
    // Scale burst up aggressively if backlog large.
    if (remaining > 512) {
      dynamicChunkBurst = Math.min(maxChunksPerIteration, Math.max(dynamicChunkBurst, 96));
    } else if (remaining > 256) {
      dynamicChunkBurst = Math.min(maxChunksPerIteration, Math.max(dynamicChunkBurst, 80));
    } else if (remaining > 128) {
      dynamicChunkBurst = Math.min(maxChunksPerIteration, Math.max(dynamicChunkBurst, 64));
    }
    int perIter = Math.min(remaining, dynamicChunkBurst);
    int scheduledThisCycle = 0;
    for (int g = 0; g < perIter; g++) {
      if (generationIndex >= generationQueue.size())
        break;
      // Back-pressure: pause if saturated
      if (chunkGenerationsInProgress >= MAX_CHUNK_GENERATIONS || meshBuildsInProgress >= MAX_MESH_BUILDS)
        break; // saturated; let existing work drain
      Vector3f chunkPos = generationQueue.get(generationIndex);
      generationIndex++;
      String posKey = ((int) chunkPos.x) + "|" + ((int) chunkPos.y) + "|" + ((int) chunkPos.z);
      if (!worldChunks.containsKey(posKey) && inflightGeneration.add(posKey)) {
        chunkGenerationsInProgress++;
        // Async generation task
        chunkGenExecutor.submit(() -> {
          try {
            // Build occupancy/types directly to reduce allocation pressure when
            // SOLID_COLUMNS true
            java.util.BitSet occ = new java.util.BitSet(chunkSize * CHUNK_HEIGHT * chunkSize);
            byte[] types = new byte[chunkSize * CHUNK_HEIGHT * chunkSize];
            int producedBlocks = 0;
            long genStart = System.nanoTime();
            int chunkHeight = CHUNK_HEIGHT;
            // Optional smoothing sample grid
            int pad = SMOOTH_TERRAIN ? SMOOTH_RADIUS : 0;
            int sampleSize = chunkSize + pad * 2;
            float[][] raw = new float[sampleSize][sampleSize];
            int originWorldX = (int) chunkPos.x - pad;
            int originWorldZ = (int) chunkPos.z - pad;
            for (int i = 0; i < sampleSize; i++) {
              for (int j = 0; j < sampleSize; j++) {
                int worldX = originWorldX + i;
                int worldZ = originWorldZ + j;
                // Normalized multi-octave noise [0,1]
                float normalized = GENERATOR.normalizedHeight(worldX, worldZ);
                if (normalized < 0f)
                  normalized = 0f;
                if (normalized > 1f)
                  normalized = 1f;
                raw[i][j] = normalized; // store normalized directly
              }
            }
            float[][] smoothed = raw;
            if (SMOOTH_TERRAIN) {
              smoothed = new float[sampleSize][sampleSize];
              for (int i = 0; i < sampleSize; i++) {
                for (int j = 0; j < sampleSize; j++) {
                  float acc = 0f;
                  int count = 0;
                  for (int di = -SMOOTH_RADIUS; di <= SMOOTH_RADIUS; di++) {
                    for (int dj = -SMOOTH_RADIUS; dj <= SMOOTH_RADIUS; dj++) {
                      int ni = i + di;
                      int nj = j + dj;
                      if (ni >= 0 && ni < sampleSize && nj >= 0 && nj < sampleSize) {
                        acc += raw[ni][nj];
                        count++;
                      }
                    }
                  }
                  smoothed[i][j] = acc / (float) count;
                }
              }
            }
            // Surface heights without forced large plateau clamp
            int maxSurfaceY = chunkHeight - 1 - MIN_AIR_ABOVE;
            if (maxSurfaceY < 0)
              maxSurfaceY = Math.max(0, chunkHeight - 1);
            int[][] surface = new int[chunkSize][chunkSize];
            for (int i = 0; i < chunkSize; i++) {
              for (int j = 0; j < chunkSize; j++) {
                float baseH = raw[i + pad][j + pad];
                float smoothH = smoothed[i + pad][j + pad];
                float h = SMOOTH_TERRAIN ? (baseH * RAW_BLEND + smoothH * SMOOTH_BLEND) : baseH;
                h = Math.max(0f, Math.min(1f, h));
                surface[i][j] = (int) Math.floor(h * maxSurfaceY);
              }
            }
            for (int i = 0; i < chunkSize; i++) {
              for (int j = 0; j < chunkSize; j++) {
                int surfaceY = surface[i][j];
                int minBase = SOLID_COLUMNS ? 0 : Math.max(0, surfaceY - (MIN_GROUND_DEPTH - 1));
                for (int y = minBase; y <= surfaceY; y++) {
                  int depthFromTop = surfaceY - y;
                  int type;
                  if (depthFromTop == 0)
                    type = Blocks.GRASS;
                  else if (depthFromTop < TOP_SOIL_DEPTH)
                    type = Blocks.DIRT;
                  else
                    type = Blocks.STONE;
                  int idx = (y * chunkSize + j) * chunkSize + i;
                  occ.set(idx);
                  types[idx] = (byte) type;
                  producedBlocks++;
                }
              }
            }
            Chunk chunk = new Chunk(occ, types, producedBlocks, chunkPos);
            chunksGenerated.incrementAndGet();
            blocksGenerated.addAndGet(producedBlocks);
            synchronized (worldChunks) {
              worldChunks.put(posKey, chunk);
            }
            loadedBlocks.addAndGet(producedBlocks);
            completedChunkGenerations.add(chunk);
            long genEnd = System.nanoTime();
            genTimeNs.add(genEnd - genStart);
            genTasks.increment();
          } catch (Throwable t) {
            t.printStackTrace();
          } finally {
            chunkGenerationsInProgress--;
            inflightGeneration.remove(posKey);
          }
        });
        scheduledThisCycle++;
      }
      if (generationIndex >= generationQueue.size()) {
        generationQueue.clear();
        generationIndex = 0;
        break;
      }
    }

    // Fallback on-the-fly scan: fill any remaining gaps not currently queued to
    // avoid checkerboard holes.
    if (scheduledThisCycle < dynamicChunkBurst && chunkGenerationsInProgress < MAX_CHUNK_GENERATIONS) {
      int remainingBudget = dynamicChunkBurst - scheduledThisCycle;
      int baseChunkX2 = Math.floorDiv((int) Math.floor(camPos.x), chunkSize) * chunkSize;
      int baseChunkZ2 = Math.floorDiv((int) Math.floor(camPos.z), chunkSize) * chunkSize;
      int chunkRadius2 = Math.max(1, (int) Math.ceil((double) renderDistance / chunkSize));
      outer: for (int ring = 0; ring <= chunkRadius2; ring++) {
        for (int dx = -ring; dx <= ring; dx++) {
          int dz1 = ring - Math.abs(dx);
          for (int s = -1; s <= 1; s += 2) { // two dz (positive/negative)
            int dz = dz1 * s;
            if (Math.abs(dx) + Math.abs(dz) != ring)
              continue;
            int ox = baseChunkX2 + dx * chunkSize;
            int oz = baseChunkZ2 + dz * chunkSize;
            String key = ox + "|0|" + oz;
            if (worldChunks.containsKey(key) || inflightGeneration.contains(key))
              continue;
            if (chunkGenerationsInProgress >= MAX_CHUNK_GENERATIONS || meshBuildsInProgress >= MAX_MESH_BUILDS)
              break outer;
            if (!inflightGeneration.add(key))
              continue;
            chunkGenerationsInProgress++;
            final String taskKey = key;
            final int taskOx = ox;
            final int taskOz = oz;
            chunkGenExecutor.submit(() -> {
              try {
                java.util.BitSet occ = new java.util.BitSet(chunkSize * CHUNK_HEIGHT * chunkSize);
                byte[] types = new byte[chunkSize * CHUNK_HEIGHT * chunkSize];
                int producedBlocks = 0;
                long genStart = System.nanoTime();
                int chunkHeight = CHUNK_HEIGHT;
                int pad = SMOOTH_TERRAIN ? SMOOTH_RADIUS : 0;
                int sampleSize = chunkSize + pad * 2;
                float[][] raw = new float[sampleSize][sampleSize];
                int originWorldX = taskOx - pad;
                int originWorldZ = taskOz - pad;
                for (int i = 0; i < sampleSize; i++) {
                  for (int j = 0; j < sampleSize; j++) {
                    int worldX = originWorldX + i;
                    int worldZ = originWorldZ + j;
                    float normalized = GENERATOR.normalizedHeight(worldX, worldZ);
                    if (normalized < 0f)
                      normalized = 0f;
                    if (normalized > 1f)
                      normalized = 1f;
                    raw[i][j] = normalized;
                  }
                }
                float[][] smoothed = raw;
                if (SMOOTH_TERRAIN) {
                  smoothed = new float[sampleSize][sampleSize];
                  for (int i = 0; i < sampleSize; i++) {
                    for (int j = 0; j < sampleSize; j++) {
                      float acc = 0f;
                      int count = 0;
                      for (int di = -SMOOTH_RADIUS; di <= SMOOTH_RADIUS; di++) {
                        for (int dj = -SMOOTH_RADIUS; dj <= SMOOTH_RADIUS; dj++) {
                          int ni = i + di;
                          int nj = j + dj;
                          if (ni >= 0 && ni < sampleSize && nj >= 0 && nj < sampleSize) {
                            acc += raw[ni][nj];
                            count++;
                          }
                        }
                      }
                      smoothed[i][j] = acc / (float) count;
                    }
                  }
                }
                int maxSurfaceY = chunkHeight - 1 - MIN_AIR_ABOVE;
                if (maxSurfaceY < 0)
                  maxSurfaceY = Math.max(0, chunkHeight - 1);
                int[][] surface = new int[chunkSize][chunkSize];
                for (int i = 0; i < chunkSize; i++) {
                  for (int j = 0; j < chunkSize; j++) {
                    float baseH = raw[i + pad][j + pad];
                    float smoothH = smoothed[i + pad][j + pad];
                    float h = SMOOTH_TERRAIN ? (baseH * RAW_BLEND + smoothH * SMOOTH_BLEND) : baseH;
                    h = Math.max(0f, Math.min(1f, h));
                    surface[i][j] = (int) Math.floor(h * maxSurfaceY);
                  }
                }
                for (int i = 0; i < chunkSize; i++) {
                  for (int j = 0; j < chunkSize; j++) {
                    int surfaceY = surface[i][j];
                    int minBase = SOLID_COLUMNS ? 0 : Math.max(0, surfaceY - (MIN_GROUND_DEPTH - 1));
                    for (int y = minBase; y <= surfaceY; y++) {
                      int depthFromTop = surfaceY - y;
                      int type;
                      if (depthFromTop == 0)
                        type = Blocks.GRASS;
                      else if (depthFromTop < TOP_SOIL_DEPTH)
                        type = Blocks.DIRT;
                      else
                        type = Blocks.STONE;
                      int idx = (y * chunkSize + j) * chunkSize + i;
                      occ.set(idx);
                      types[idx] = (byte) type;
                      producedBlocks++;
                    }
                  }
                }
                Chunk chunk = new Chunk(occ, types, producedBlocks, new Vector3f(taskOx, 0, taskOz));
                chunksGenerated.incrementAndGet();
                blocksGenerated.addAndGet(producedBlocks);
                synchronized (worldChunks) {
                  worldChunks.put(taskKey, chunk);
                }
                loadedBlocks.addAndGet(producedBlocks);
                completedChunkGenerations.add(chunk);
                long genEnd = System.nanoTime();
                genTimeNs.add(genEnd - genStart);
                genTasks.increment();
              } catch (Throwable t) {
                t.printStackTrace();
              } finally {
                chunkGenerationsInProgress--;
                inflightGeneration.remove(taskKey);
              }
            });
            remainingBudget--;
            if (remainingBudget <= 0)
              break outer;
          }
        }
      }
    }

    // Drain any completed chunk generations into rawNewChunks for meshing this pass
    Chunk ready;
    while ((ready = completedChunkGenerations.poll()) != null) {
      rawNewChunks.add(ready);
    }

    if (!rawNewChunks.isEmpty()) {

      // Prioritize mesh builds for chunks closest to the camera
      rawNewChunks.sort((a, b) -> {
        float da = Vector3f.sub(a.origin, camPos, null).length();
        float db = Vector3f.sub(b.origin, camPos, null).length();
        return Float.compare(da, db);
      });
      for (Chunk c : rawNewChunks) {
        String key = ((int) c.origin.x) + "|0|" + ((int) c.origin.z);
        // Mesh if first time or entity missing
        if (scheduledMeshKeys.add(key) || !entityCache.containsKey(key)) {
          pendingNewMesh.add(c);
        }
        // Neighbor remesh
        int originX = (int) c.origin.x;
        int originZ = (int) c.origin.z;
        // Track neighbor mask for new chunk and existing neighbors; schedule remesh
        // ONLY if adjacency changed. Mask bits: 0=W,1=E,2=N,3=S
        byte newMask = 0;
        Chunk west = worldChunks.get((originX - chunkSize) + "|0|" + originZ);
        if (west != null)
          newMask |= 1 << 0;
        Chunk east = worldChunks.get((originX + chunkSize) + "|0|" + originZ);
        if (east != null)
          newMask |= 1 << 1;
        Chunk north = worldChunks.get(originX + "|0|" + (originZ - chunkSize));
        if (north != null)
          newMask |= 1 << 2;
        Chunk south = worldChunks.get(originX + "|0|" + (originZ + chunkSize));
        if (south != null)
          newMask |= 1 << 3;
        if (c.neighborMask != newMask) {
          c.neighborMask = newMask;
          // New chunk gained neighbors -> ensure its own remesh if already meshed once
          if (!pendingNewMesh.contains(c) && !remeshPending.contains(key)) {
            remeshPending.add(key);
            pendingRemesh.add(c);
          }
        }
        // For each neighbor whose mask changes due to this new chunk, schedule one
        // remesh
        scheduleNeighborMaskUpdate(west, (byte) (1 << 1)); // west gains east
        scheduleNeighborMaskUpdate(east, (byte) (1 << 0)); // east gains west
        scheduleNeighborMaskUpdate(north, (byte) (1 << 3)); // north gains south
        scheduleNeighborMaskUpdate(south, (byte) (1 << 2)); // south gains north
      }
    }

    // (Neighbor remesh scheduling merged above)
  }

  // Update a neighbor's mask with addedBits; if changed schedule single remesh
  private static void scheduleNeighborMaskUpdate(Chunk neighbor, byte addedBits) {
    if (neighbor == null)
      return;
    byte oldMask = neighbor.neighborMask;
    byte newMask = (byte) (oldMask | addedBits);
    if (newMask != oldMask) {
      neighbor.neighborMask = newMask;
      String key = ((int) neighbor.origin.x) + "|0|" + ((int) neighbor.origin.z);
      if (!remeshPending.contains(key)) {
        remeshPending.add(key);
        pendingRemesh.add(neighbor);
      }
    }
  }

  // ===== Fixed Update =====
  private void update(MasterRenderer renderer) {
    // Tick counter
    tickCount++;

    // Camera movement (fixed timestep)
    double deltaSeconds = 1.0 / tickRate; // fixed step
    camera.move(deltaSeconds);
    camPos = camera.getPosition();

    // Handle block breaking on left-click (debounced)
    boolean leftDown = Mouse.isButtonDown(0);
    if (leftDown && !prevLeftDown) {
      handleLeftClickBreak(camera);
    }
    prevLeftDown = leftDown;

    // Update FPS once per second
    if (System.currentTimeMillis() - timer >= 1000) {
      timer += 1000;
      showFPS = frameCount;
      showTPS = tickCount;
      lastBlocksGeneratedSnapshot = blocksGenerated.get();
      lastChunksGeneratedSnapshot = chunksGenerated.get();
      long reconcileLoaded = 0L;
      synchronized (worldChunks) {
        for (Chunk c : worldChunks.values()) {
          if (c != null)
            reconcileLoaded += c.getBlockCount();
        }
      }
      loadedBlocks.set(reconcileLoaded);
      frameCount = 0;
      tickCount = 0;
      // Memory sample
      Runtime rt = Runtime.getRuntime();
      long used = rt.totalMemory() - rt.freeMemory();
      memDelta = used - lastMemUsed;
      lastMemUsed = used;
      memUsed = used;
    }
  }

  // ===== Block Breaking =====
  private void handleLeftClickBreak(Camera cam) {
    // Raycast from eye along view direction (3D DDA) to the first solid block
    Vector3f eye = cam.getPosition();
    // Build forward direction to match movement/diagnostics (sin(yaw), -cos(yaw))
    float yawRad = (float) Math.toRadians(cam.getRotY());
    float pitchRad = (float) Math.toRadians(cam.getRotX());
    float cosP = (float) Math.cos(pitchRad);
    float dirX = (float) (Math.sin(yawRad) * cosP);
    float dirZ = (float) (-Math.cos(yawRad) * cosP);
    float dirY = (float) (-Math.sin(pitchRad));
    // Normalize
    float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
    if (len < 1e-6f)
      return;
    dirX /= len;
    dirY /= len;
    dirZ /= len;
    float maxDist = 6.0f;
    int[] hit = raycastVoxel(eye.x + dirX * 0.001f, eye.y + dirY * 0.001f, eye.z + dirZ * 0.001f, dirX, dirY, dirZ,
        maxDist);
    if (hit != null) {
      breakBlockAt(hit[0], hit[1], hit[2]);
    }
  }

  // Voxel DDA raycast; returns {bx,by,bz} of first solid block within maxDist
  private int[] raycastVoxel(float sx, float sy, float sz, float dx, float dy, float dz, float maxDist) {
    int x = (int) Math.floor(sx);
    int y = (int) Math.floor(sy);
    int z = (int) Math.floor(sz);
    int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
    int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
    int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
    float tMaxX, tMaxY, tMaxZ;
    float tDeltaX, tDeltaY, tDeltaZ;
    final float INF = 1e30f;
    if (stepX != 0) {
      float nextX = x + (stepX > 0 ? 1f : 0f);
      tMaxX = (nextX - sx) / dx;
      tDeltaX = 1f / Math.abs(dx);
    } else {
      tMaxX = INF;
      tDeltaX = INF;
    }
    if (stepY != 0) {
      float nextY = y + (stepY > 0 ? 1f : 0f);
      tMaxY = (nextY - sy) / dy;
      tDeltaY = 1f / Math.abs(dy);
    } else {
      tMaxY = INF;
      tDeltaY = INF;
    }
    if (stepZ != 0) {
      float nextZ = z + (stepZ > 0 ? 1f : 0f);
      tMaxZ = (nextZ - sz) / dz;
      tDeltaZ = 1f / Math.abs(dz);
    } else {
      tMaxZ = INF;
      tDeltaZ = INF;
    }
    float t = 0f;
    while (t <= maxDist) {
      // Step to next cell boundary
      if (tMaxX < tMaxY) {
        if (tMaxX < tMaxZ) {
          x += stepX;
          t = tMaxX;
          tMaxX += tDeltaX;
        } else {
          z += stepZ;
          t = tMaxZ;
          tMaxZ += tDeltaZ;
        }
      } else {
        if (tMaxY < tMaxZ) {
          y += stepY;
          t = tMaxY;
          tMaxY += tDeltaY;
        } else {
          z += stepZ;
          t = tMaxZ;
          tMaxZ += tDeltaZ;
        }
      }
      if (t > maxDist)
        break;
      if (isSolidAt(x, y, z)) {
        return new int[] { x, y, z };
      }
    }
    return null;
  }

  private boolean isSolidAt(int wx, int wy, int wz) {
    if (wy < 0 || wy >= CHUNK_HEIGHT)
      return false;
    int originX = Math.floorDiv(wx, chunkSize) * chunkSize;
    int originZ = Math.floorDiv(wz, chunkSize) * chunkSize;
    String key = originX + "|0|" + originZ;
    Chunk c = worldChunks.get(key);
    if (c == null)
      return false;
    int lx = wx - originX;
    int lz = wz - originZ;
    return c.hasLocal(lx, wy, lz);
  }

  private void breakBlockAt(int wx, int wy, int wz) {
    int originX = Math.floorDiv(wx, chunkSize) * chunkSize;
    int originZ = Math.floorDiv(wz, chunkSize) * chunkSize;
    String key = originX + "|0|" + originZ;
    Chunk c;
    synchronized (worldChunks) {
      c = worldChunks.get(key);
      if (c == null)
        return;
      int lx = wx - originX;
      int lz = wz - originZ;
      // Set to air
      if (!c.setLocal(lx, wy, lz, 0)) {
        return; // no change
      }
    }
    // Schedule remesh for this chunk
    scheduleRemeshKey(key);
    // If block on chunk boundary, also remesh neighbor chunk on that side
    int lx = wx - originX;
    int lz = wz - originZ;
    if (lx == 0)
      scheduleRemeshKey((originX - chunkSize) + "|0|" + originZ);
    if (lx == chunkSize - 1)
      scheduleRemeshKey((originX + chunkSize) + "|0|" + originZ);
    if (lz == 0)
      scheduleRemeshKey(originX + "|0|" + (originZ - chunkSize));
    if (lz == chunkSize - 1)
      scheduleRemeshKey(originX + "|0|" + (originZ + chunkSize));
  }

  private void scheduleRemeshKey(String key) {
    Chunk ch = worldChunks.get(key);
    if (ch == null)
      return;
    if (!remeshPending.contains(key)) {
      remeshPending.add(key);
      pendingRemesh.add(ch);
    }
    // Also drop existing VAO/entity to force rebuild if necessary
    rawModelCache.remove(key);
    entityCache.remove(key);
  }

  // ===== Unload Far Chunks =====
  private void unloadFarChunks() {
    // Chebyshev distance
    // Use a larger hysteresis distance to avoid unloading chunks that are about to
    // re-enter view when moving fast. Chunks are generated for renderDistance,
    // but we only unload once they exit renderDistance + margin.
    int maxAxisDist = renderDistance + UNLOAD_MARGIN_BLOCKS; // in blocks
    java.util.List<String> toRemove = new java.util.ArrayList<>();
    long nowMs = System.currentTimeMillis();
    synchronized (worldChunks) {
      for (String key : worldChunks.keySet()) {
        // key format: x|0|z
        String[] parts = key.split("\\|");
        if (parts.length < 3)
          continue;
        int x;
        int z;
        try {
          x = Integer.parseInt(parts[0]);
          z = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
          continue;
        }
        int dx = (int) (camPos.x - x);
        int dz = (int) (camPos.z - z);
        if (Math.abs(dx) > maxAxisDist || Math.abs(dz) > maxAxisDist) {
          Chunk c = worldChunks.get(key);
          if (c != null && c.timeSinceLastUse(nowMs) >= UNLOAD_IDLE_MS) {
            toRemove.add(key);
          }
        }
      }
      if (!toRemove.isEmpty()) {
        for (String key : toRemove) {
          Chunk c = worldChunks.remove(key);
          if (c != null) {
            loadedBlocks.addAndGet(-c.getBlockCount());
            // Neighbor faces exposed -> remesh
            int originX = (int) c.origin.x;
            int originZ = (int) Math.round(c.origin.z);
            String[] neighborKeys = new String[] {
                (originX - chunkSize) + "|0|" + originZ,
                (originX + chunkSize) + "|0|" + originZ,
                originX + "|0|" + (originZ - chunkSize),
                originX + "|0|" + (originZ + chunkSize)
            };
            for (String nk : neighborKeys) {
              Chunk neighbor = worldChunks.get(nk);
              if (neighbor == null)
                continue;
              if (remeshPending.contains(nk))
                continue;
              remeshPending.add(nk);
              pendingRemesh.add(neighbor);
            }
          }
          // Purge caches
          entityCache.remove(key);
          scheduledMeshKeys.remove(key); // allow re-mesh when regenerated
          remeshPending.remove(key);
          rawModelCache.remove(key);
        }
      }
      // Hard cap trim (farthest first)
      if (worldChunks.size() > MAX_LOADED_CHUNKS) {
        java.util.List<String> keys = new java.util.ArrayList<>(worldChunks.keySet());
        java.util.Collections.sort(keys, (a, b) -> {
          String[] pa = a.split("\\|");
          String[] pb = b.split("\\|");
          if (pa.length < 3 || pb.length < 3)
            return 0;
          int ax = Integer.parseInt(pa[0]);
          int az = Integer.parseInt(pa[2]);
          int bx = Integer.parseInt(pb[0]);
          int bz = Integer.parseInt(pb[2]);
          int da = Math.max(Math.abs((int) camPos.x - ax), Math.abs((int) camPos.z - az));
          int db = Math.max(Math.abs((int) camPos.x - bx), Math.abs((int) camPos.z - bz));
          return Integer.compare(db, da); // farthest first
        });
        for (String key : keys) {
          if (worldChunks.size() <= MAX_LOADED_CHUNKS)
            break;
          Chunk c = worldChunks.remove(key);
          if (c != null) {
            loadedBlocks.addAndGet(-c.getBlockCount());
          }
          entityCache.remove(key);
          scheduledMeshKeys.remove(key);
          remeshPending.remove(key);
          rawModelCache.remove(key);
        }
      }
    }
  }

  // ===== Render =====
  private void render(MasterRenderer renderer, Camera camera, ModelTextures textures) {
    // Input events (movement handled in update)
    displayManager.KeyHandler();

    // Upload completed meshes & create/update entities (pacing to avoid frame
    // spikes)
    long uploadStartFrame = System.nanoTime();
    int uploads = 0;
    while (!completedMeshes.isEmpty()) {
      int backlog = completedMeshes.size();
      // Adaptive per-frame quantity cap based on backlog size
      int adaptiveCap;
      if (backlog > 1024)
        adaptiveCap = 96;
      else if (backlog > 768)
        adaptiveCap = 72;
      else if (backlog > 512)
        adaptiveCap = 48;
      else if (backlog > 256)
        adaptiveCap = 32;
      else if (backlog > 128)
        adaptiveCap = 16;
      else if (backlog > 64)
        adaptiveCap = 8;
      else
        adaptiveCap = MAX_MESH_UPLOADS_PER_FRAME;
      if (uploads >= adaptiveCap)
        break; // quantity cap
      // Adaptive time budget scaling
      long dynamicBudget = MAX_MESH_UPLOAD_BUDGET_NS;
      if (backlog > 512)
        dynamicBudget = 10_000_000L; // 10ms
      else if (backlog > 256)
        dynamicBudget = 6_000_000L; // 6ms
      else if (backlog > 128)
        dynamicBudget = 4_000_000L; // 4ms
      long elapsedNs = System.nanoTime() - uploadStartFrame;
      if (elapsedNs >= dynamicBudget)
        break; // time budget cap

      ChunkMesh chunk = completedMeshes.poll();
      if (chunk == null)
        break;
      String originKey = ((int) chunk.chunk.origin.x) + "|" + ((int) chunk.chunk.origin.y) + "|"
          + ((int) chunk.chunk.origin.z);
      RawModel model = rawModelCache.get(originKey);
      boolean isPendingRemesh = remeshPending.contains(originKey);
      if (isPendingRemesh || model == null) {
        // Safety: avoid uploading absurdly large buffers (corruption or runaway)
        if (chunk.positions != null && chunk.positions.length > 5_000_000) { // ~ >5M floats ~60MB
          System.err.println("[Mesh] Skip HUGE mesh positions=" + chunk.positions.length + " for chunk " + originKey);
          continue;
        }
        if (chunk.aos != null && chunk.aos.length == (chunk.positions.length / 3)) {
          model = loader.loadToVAO(chunk.positions, chunk.uvs, chunk.normals, chunk.aos);
        } else {
          model = loader.loadToVAO(chunk.positions, chunk.uvs, chunk.normals);
        }
        rawModelCache.put(originKey, model);
      }
      if (isPendingRemesh || !entityCache.containsKey(originKey)) {
        TexturedModel texModel = new TexturedModel(model, textures);
        Entity entity = new Entity(texModel, chunk.chunk.origin, 0, 0, 0, 1);
        entityCache.put(originKey, entity);
        remeshPending.remove(originKey);
      }
      if (!chunk.chunk.isCompacted()) {
        chunk.chunk.compact();
      }
      // Release heavy arrays early
      chunk.positions = null;
      chunk.normals = null;
      chunk.uvs = null;
      uploads++;
    }
    lastFrameMeshUploads = uploads;
    // Safety purge: if backlog still extreme after uploads, drop oldest to avoid
    // OOM
    if (completedMeshes.size() > 5000) {
      int toDrop = completedMeshes.size() - 5000;
      while (toDrop-- > 0) {
        ChunkMesh drop = completedMeshes.poll();
        if (drop == null)
          break;
        // help GC
        drop.positions = null;
        drop.normals = null;
        drop.uvs = null;
        drop.aos = null;
      }
    }

    // Repair: schedule missing entities
    int repairs = 0;
    if (repairs < 32) { // per-frame cap
      synchronized (worldChunks) {
        for (Map.Entry<String, Chunk> e : worldChunks.entrySet()) {
          if (repairs >= 32)
            break;
          String key = e.getKey();
          if (!entityCache.containsKey(key) && !remeshPending.contains(key)) {
            // Force re-schedule
            scheduledMeshKeys.remove(key);
            scheduledMeshKeys.add(key);
            pendingNewMesh.add(e.getValue());
            repairs++;
          }
        }
      }
    }

    // Frustum-free simple distance cull
    synchronized (entityCache) {
      for (Map.Entry<String, Entity> e : entityCache.entrySet()) {
        Entity entity = e.getValue();
        Vector3f origin = entity.getPosition();
        int distX = (int) (camPos.x - origin.x);
        int distZ = (int) (camPos.z - origin.z);
        if (Math.abs(distX) <= renderDistance && Math.abs(distZ) <= renderDistance) {
          renderer.addEntity(entity);
          // Mark chunk as recently used
          Chunk c = worldChunks.get(((int) origin.x) + "|0|" + ((int) origin.z));
          if (c != null)
            c.touch();
        }
      }
    }

    // Draw world
    long renderStart = System.nanoTime();
    renderer.render(camera);
    long renderEnd = System.nanoTime();
    drawTime = renderEnd - renderStart;

    // Adaptive generation burst tuning (500ms cadence)
    long nowMs = System.currentTimeMillis();
    if (nowMs - lastAdaptTime >= 500) {
      double frameMs = (drawTime / 1_000_000.0);
      double target = TARGET_FRAME_MS;
      // Adjust burst size
      if (frameMs > target * 1.35) {
        // Heavy overload
        dynamicChunkBurst = Math.max(1, dynamicChunkBurst / 2);
      } else if (frameMs > target * 1.15) {
        // Mild overload
        dynamicChunkBurst = Math.max(1, dynamicChunkBurst - 2);
      } else if (frameMs < target * 0.60) {
        // Plenty headroom
        dynamicChunkBurst = Math.min(maxChunksPerIteration, dynamicChunkBurst + 2);
      } else if (frameMs < target * 0.85) {
        // Slight headroom
        dynamicChunkBurst = Math.min(maxChunksPerIteration, dynamicChunkBurst + 1);
      }
      lastAdaptTime = nowMs;
    }

    // HUD
    dbgPendingGenQueue = generationQueue.size() - generationIndex;
    dbgPendingMeshQueue = completedChunkGenerations.size();
    double avgGenMs = genTasks.longValue() == 0 ? 0.0 : (genTimeNs.doubleValue() / genTasks.longValue()) / 1_000_000.0;
    double avgMeshMs = meshTasks.longValue() == 0 ? 0.0
        : (meshTimeNs.doubleValue() / meshTasks.longValue()) / 1_000_000.0;
    // Compute facing cardinal direction from camera yaw
    float yaw = camera.getRotY();
    float yaw360 = (yaw % 360f + 360f) % 360f; // 0..360
    int sector = (int) Math.floor((yaw360 + 22.5f) / 45f) & 7; // 8 sectors
    String facingDir;
    switch (sector) {
      case 0:
        facingDir = "N";
        break;
      case 1:
        facingDir = "NE";
        break;
      case 2:
        facingDir = "E";
        break;
      case 3:
        facingDir = "SE";
        break;
      case 4:
        facingDir = "S";
        break;
      case 5:
        facingDir = "SW";
        break;
      case 6:
        facingDir = "W";
        break;
      case 7:
        facingDir = "NW";
        break;
      default:
        facingDir = "?";
        break;
    }

    if (showDebugHUD) {
      String statsText = "FPS: " + showFPS +
          "\nTPS: " + showTPS +
          "\nBlocksGenerated: " + blocksGenerated.get() +
          "\nChunksGenerated: " + chunksGenerated.get() +
          "\nLoadedChunks: " + worldChunks.size() +
          "\nLoadedBlocks: " + loadedBlocks.get() +
          "\nGenerationQueue: " + dbgPendingGenQueue +
          "\nMeshQueue: " + dbgPendingMeshQueue +
          "\nPendingUploads: " + completedMeshes.size() +
          "\nUploadsThisFrame: " + lastFrameMeshUploads +
          "\nGenAvg(ms): " + String.format(java.util.Locale.US, "%.2f", avgGenMs) +
          "\nMeshAvg(ms): " + String.format(java.util.Locale.US, "%.2f", avgMeshMs) +
          "\nMem(MB): " + (memUsed / (1024 * 1024)) + " / " + (maxHeap / (1024 * 1024)) + " (d="
          + (memDelta / (1024 * 1024)) + ")" +
          "\nSpeed: " + String.format(java.util.Locale.US, "%.1f", camera.getSpeed()) +
          "\nState: " + (camera.isNoclip() ? "Noclip" : "Walk")
          + (camera.isSprinting() ? "+Sprint" : (camera.isCrouching() ? "+Crouch" : "")) +
          "\nXYZ: " + (int) camPos.x + " " + (int) camPos.y + " " + (int) camPos.z +
          "\nFacing: " + facingDir +
          "\nDistN/E/S/W: " + String.format(java.util.Locale.US, "%.2f", camera.getDistNorth()) + "/" +
          String.format(java.util.Locale.US, "%.2f", camera.getDistEast()) + "/" +
          String.format(java.util.Locale.US, "%.2f", camera.getDistSouth()) + "/" +
          String.format(java.util.Locale.US, "%.2f", camera.getDistWest()) +
          "\nDistF/L/R: " + String.format(java.util.Locale.US, "%.2f", camera.getDistForward()) + "/" +
          String.format(java.util.Locale.US, "%.2f", camera.getDistLeft()) + "/" +
          String.format(java.util.Locale.US, "%.2f", camera.getDistRight()) +
          "\nDebugMode: " + debugRenderMode;
      textRenderer.updateText(statsText);
      textRenderer.render(8, 8);
    }
    frameCount++;

    // Swap buffers
    Display.update();
  }
}
