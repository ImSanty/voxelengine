package RenderEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;

import Entities.Camera;
import Entities.Entity;
import Models.TexturedModel;
import Shaders.StaticShader;

public class MasterRenderer {
  Matrix4f projectionMatrix;
  private static final float BASE_FOV = 75f;
  private static final float SPRINT_FOV_DELTA = 4f; // subtle increase while sprinting
  private float currentFov = BASE_FOV;
  private float targetFov = BASE_FOV;
  private static final float FOV_LERP_SPEED = 6f; // 1/seconds toward target
  private static final float NEAR_PLANE = 0.1f;
  private static final float FAR_PLANE = 10000f;

  StaticShader shader = new StaticShader();
  EntityRenderer renderer = new EntityRenderer();
  Map<TexturedModel, List<Entity>> entities = new HashMap<TexturedModel, List<Entity>>();
  // Debug camera bounds line VAO
  private int camBoundsVao = -1;
  private int camBoundsVertexCount = 0;
  private int camBoundsVbo = -1; // dynamic lines buffer for bounds
  // Collision box (top-down square) and contact points
  private int colliderBoxVao = -1;
  private int colliderBoxCount = 0;
  private int contactPointsVao = -1; // dynamic (updated each frame)
  private int contactPointCount = 0;
  private int contactVbo = -1;
  // Last sweep hit block outline
  private int hitBlockVao = -1;
  private int hitBlockVbo = -1;

  public MasterRenderer() {
    createProjectionMatrix();
    shader.start();
    shader.loadProjectionMatrix(projectionMatrix);
    shader.stop();
    initCameraBoundsGeometry();
    initColliderBoxGeometry();
    initHitBlockGeometry();
  }

  public void prepare() {
    GL11.glEnable(GL11.GL_DEPTH_TEST);
    // Enable back-face culling normally; disable in normals debug to visualize all
    // faces
    if (ProjectV.GameLoop.debugRenderMode == 2) {
      GL11.glDisable(GL11.GL_CULL_FACE);
    } else {
      GL11.glEnable(GL11.GL_CULL_FACE);
      GL11.glCullFace(GL11.GL_BACK);
    }
    GL11.glClearColor(0.2f, 0.6f, 1.0f, 1);
    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
  }

  public void render(Camera camera) {
    prepare();
    // Dynamic FOV: adjust target based on sprint state, smooth toward it and
    // rebuild projection if changed
    targetFov = BASE_FOV + (camera.isSprinting() ? SPRINT_FOV_DELTA : 0f);
    float diff = targetFov - currentFov;
    if (Math.abs(diff) > 0.01f) {
      float dt = 1f / 60f; // fallback assumption; real delta not passed here. Consider plumbing if needed.
      float step = FOV_LERP_SPEED * dt;
      if (step > 1f)
        step = 1f;
      currentFov += diff * step;
      recreateProjectionMatrixIfNeeded();
    } else if (Math.abs(diff) <= 0.01f && Math.abs(targetFov - currentFov) > 0f) {
      currentFov = targetFov;
      recreateProjectionMatrixIfNeeded();
    }
    shader.start();
    shader.loadViewMatrix(camera);
    shader.loadTextureUnit(0);
    shader.loadDebugMode(ProjectV.GameLoop.debugRenderMode);
    // AO tuning (lighter): strength=0.50 (more lerp toward 1), clamp 0.6..1.0
    shader.loadAoParams(0.50f, 0.50f, 1.0f);
    // Fog parameters (simple linear fog)
    org.lwjgl.util.vector.Vector3f camPos = camera.getPosition();
    shader.loadCameraPosition(camPos);
    int rd = ProjectV.GameLoop.getRenderDistance();
    float fogStart = rd * 0.7f; // start fading after 70% of distance
    float fogEnd = rd * 1.05f; // fully fogged slightly past distance
    shader.loadFog(new org.lwjgl.util.vector.Vector3f(0.5f, 0.7f, 1.0f), fogStart, fogEnd);
    if (ProjectV.GameLoop.debugRenderMode == 5) {
      // In grid mode reduce overdraw: wireframe lines only
      GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
    }
    // Wireframe mode polygon setting
    if (ProjectV.GameLoop.debugRenderMode == 1) {
      GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
    } else {
      GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    }
    renderer.render(entities);
    shader.stop();

    // Restore fill mode so subsequent overlays (HUD text) render properly even if
    // world was drawn in wireframe/grid line mode.
    if (ProjectV.GameLoop.debugRenderMode == 1 || ProjectV.GameLoop.debugRenderMode == 5) {
      GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
    }

    // Debug: draw player collision bounding box when mode 4
    if (ProjectV.GameLoop.showPlayerBounds) {
      shader.start();
      drawCameraBoundingBox(camera);
      drawColliderBox(camera);
      drawContactPoints(camera);
      drawLastHitBlock(camera);
      shader.stop();
    }

    entities.clear();
  }

  private void drawCameraBoundingBox(Camera cam) {
    if (camBoundsVao < 0)
      return;
    // Rebuild line vertices each frame from current collider dimensions so it
    // exactly matches collision logic.
    final float R = Camera.getPlayerRadius();
    final float H = Camera.getPlayerHeight();
    float minX = -R;
    float maxX = R;
    float minZ = -R;
    float maxZ = R;
    float y0 = 0f;
    float y1 = H;
    float[] verts = new float[] {
        // bottom square
        minX, y0, minZ, maxX, y0, minZ,
        maxX, y0, minZ, maxX, y0, maxZ,
        maxX, y0, maxZ, minX, y0, maxZ,
        minX, y0, maxZ, minX, y0, minZ,
        // top square
        minX, y1, minZ, maxX, y1, minZ,
        maxX, y1, minZ, maxX, y1, maxZ,
        maxX, y1, maxZ, minX, y1, maxZ,
        minX, y1, maxZ, minX, y1, minZ,
        // verticals
        minX, y0, minZ, minX, y1, minZ,
        maxX, y0, minZ, maxX, y1, minZ,
        maxX, y0, maxZ, maxX, y1, maxZ,
        minX, y0, maxZ, minX, y1, maxZ
    };
    org.lwjgl.opengl.GL30.glBindVertexArray(camBoundsVao);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, camBoundsVbo);
    java.nio.FloatBuffer bb = org.lwjgl.BufferUtils.createFloatBuffer(verts.length);
    bb.put(verts).flip();
    org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0, bb);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
    // Build model matrix translating unit box lines to camera feet
    org.lwjgl.util.vector.Vector3f feet = cam.getFeetPosition();
    org.lwjgl.util.vector.Matrix4f transform = new org.lwjgl.util.vector.Matrix4f();
    transform.setIdentity();
    org.lwjgl.util.vector.Matrix4f.translate(feet, transform, transform);
    shader.loadTransformationMatrix(transform);
    // Use special debug mode 10 in fragment shader for solid red color
    shader.loadDebugMode(10);
    GL11.glLineWidth(3f); // slightly thicker
    org.lwjgl.opengl.GL30.glBindVertexArray(camBoundsVao);
    org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
    GL11.glDrawArrays(GL11.GL_LINES, 0, camBoundsVertexCount);
    org.lwjgl.opengl.GL20.glDisableVertexAttribArray(0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  private void initColliderBoxGeometry() {
    final float R = Camera.getPlayerRadius();
    float min = -R;
    float max = R;
    float[] verts = new float[] {
        min, 0, min, max, 0, min,
        max, 0, min, max, 0, max,
        max, 0, max, min, 0, max,
        min, 0, max, min, 0, min
    }; // 4 line segments (8 vertices => 8/1.5? but we store as pairs) total 8 vertices
       // => 4 lines
    colliderBoxCount = 8; // number of vertices
    colliderBoxVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
    org.lwjgl.opengl.GL30.glBindVertexArray(colliderBoxVao);
    int vbo = org.lwjgl.opengl.GL15.glGenBuffers();
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, vbo);
    java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(verts.length);
    buf.put(verts).flip();
    org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, buf,
        org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
    org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
    // Prepare contact points VBO (dynamic, initially empty)
    contactPointsVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
    org.lwjgl.opengl.GL30.glBindVertexArray(contactPointsVao);
    contactVbo = org.lwjgl.opengl.GL15.glGenBuffers();
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, contactVbo);
    org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 3 * 64,
        org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW); // space for ~7 points
    org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  private void drawColliderBox(Camera cam) {
    if (colliderBoxVao < 0)
      return;
    org.lwjgl.util.vector.Vector3f feet = cam.getFeetPosition();
    org.lwjgl.util.vector.Matrix4f transform = new org.lwjgl.util.vector.Matrix4f();
    transform.setIdentity();
    org.lwjgl.util.vector.Matrix4f.translate(feet, transform, transform);
    shader.loadTransformationMatrix(transform);
    shader.loadDebugMode(12); // cyan
    GL11.glLineWidth(1.5f);
    org.lwjgl.opengl.GL30.glBindVertexArray(colliderBoxVao);
    org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
    GL11.glDrawArrays(GL11.GL_LINES, 0, colliderBoxCount);
    org.lwjgl.opengl.GL20.glDisableVertexAttribArray(0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  private void drawContactPoints(Camera cam) {
    if (contactPointsVao < 0)
      return;
    float[] pts = cam.getRecentContactPoints();
    contactPointCount = pts == null ? 0 : pts.length / 3;
    if (contactPointCount == 0)
      return;
    org.lwjgl.opengl.GL30.glBindVertexArray(contactPointsVao);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, contactVbo);
    java.nio.FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(pts.length);
    fb.put(pts).flip();
    org.lwjgl.opengl.GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0, fb);
    org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
    org.lwjgl.util.vector.Vector3f feet = cam.getFeetPosition();
    org.lwjgl.util.vector.Matrix4f transform = new org.lwjgl.util.vector.Matrix4f();
    transform.setIdentity();
    org.lwjgl.util.vector.Matrix4f.translate(feet, transform, transform);
    shader.loadTransformationMatrix(transform);
    shader.loadDebugMode(11); // yellow
    GL11.glPointSize(6f);
    GL11.glDrawArrays(GL11.GL_POINTS, 0, contactPointCount);
    org.lwjgl.opengl.GL20.glDisableVertexAttribArray(0);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  private void initCameraBoundsGeometry() {
    // Allocate dynamic buffer large enough for 24 line segments * 2 verts = 48
    // vertices * 3 floats = 144 floats (we only use 72 floats currently).
    camBoundsVertexCount = 72 / 3; // 24 lines
    camBoundsVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
    org.lwjgl.opengl.GL30.glBindVertexArray(camBoundsVao);
    camBoundsVbo = org.lwjgl.opengl.GL15.glGenBuffers();
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, camBoundsVbo);
    org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 72 * 4,
        org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
    org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  private void initHitBlockGeometry() {
    // Unit cube wireframe from (0,0,0) to (1,1,1)
    float[] v = new float[] {
        0, 0, 0, 1, 0, 0,
        1, 0, 0, 1, 0, 1,
        1, 0, 1, 0, 0, 1,
        0, 0, 1, 0, 0, 0,
        0, 1, 0, 1, 1, 0,
        1, 1, 0, 1, 1, 1,
        1, 1, 1, 0, 1, 1,
        0, 1, 1, 0, 1, 0,
        0, 0, 0, 0, 1, 0,
        1, 0, 0, 1, 1, 0,
        1, 0, 1, 1, 1, 1,
        0, 0, 1, 0, 1, 1
    };
    hitBlockVao = org.lwjgl.opengl.GL30.glGenVertexArrays();
    org.lwjgl.opengl.GL30.glBindVertexArray(hitBlockVao);
    hitBlockVbo = org.lwjgl.opengl.GL15.glGenBuffers();
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, hitBlockVbo);
    java.nio.FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(v.length);
    buf.put(v).flip();
    org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, buf,
        org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
    org.lwjgl.opengl.GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  private void drawLastHitBlock(Camera cam) {
    if (hitBlockVao < 0 || !cam.hasLastSweepHit())
      return;
    int bx = cam.getLastSweepBlockX();
    int by = cam.getLastSweepBlockY();
    int bz = cam.getLastSweepBlockZ();
    org.lwjgl.util.vector.Matrix4f transform = new org.lwjgl.util.vector.Matrix4f();
    transform.setIdentity();
    org.lwjgl.util.vector.Vector3f base = new org.lwjgl.util.vector.Vector3f(bx, by, bz);
    org.lwjgl.util.vector.Matrix4f.translate(base, transform, transform);
    shader.loadTransformationMatrix(transform);
    shader.loadDebugMode(11); // yellow
    GL11.glLineWidth(2f);
    org.lwjgl.opengl.GL30.glBindVertexArray(hitBlockVao);
    org.lwjgl.opengl.GL20.glEnableVertexAttribArray(0);
    GL11.glDrawArrays(GL11.GL_LINES, 0, 24 * 2); // 24 segments *2 verts? Actually 24 lines defined
    org.lwjgl.opengl.GL20.glDisableVertexAttribArray(0);
    org.lwjgl.opengl.GL30.glBindVertexArray(0);
  }

  public void addEntity(Entity entity) {
    TexturedModel model = entity.getModel();
    List<Entity> batch = entities.get(entity.getModel());

    if (batch != null) {
      batch.add(entity);
    } else {
      List<Entity> newBatch = new ArrayList<Entity>();
      newBatch.add(entity);
      entities.put(model, newBatch);
    }
  }

  public void createProjectionMatrix() {
    projectionMatrix = new Matrix4f();
    float aspect = (float) Display.getWidth() / (float) Display.getHeight();
    float yScale = (float) (1f / Math.tan(Math.toRadians(currentFov / 2)));
    float xScale = yScale / aspect;
    float zp = FAR_PLANE + NEAR_PLANE;
    float zm = FAR_PLANE - NEAR_PLANE;

    projectionMatrix.m00 = xScale;
    projectionMatrix.m11 = yScale;
    projectionMatrix.m22 = -zp / zm;
    projectionMatrix.m23 = -1;
    projectionMatrix.m32 = -(2 * FAR_PLANE * NEAR_PLANE) / zm;
    projectionMatrix.m33 = 0;
  }

  private void recreateProjectionMatrixIfNeeded() {
    createProjectionMatrix();
    shader.start();
    shader.loadProjectionMatrix(projectionMatrix);
    shader.stop();
  }
}
