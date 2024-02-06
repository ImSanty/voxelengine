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
  private static final float FOV = 75f;
  private static final float NEAR_PLANE = 0.1f;
  private static final float FAR_PLANE = 10000f;

  StaticShader shader = new StaticShader();
  EntityRenderer renderer = new EntityRenderer();
  Map<TexturedModel, List<Entity>> entities = new HashMap<TexturedModel, List<Entity>>();

  public MasterRenderer() {
    createProjectionMatrix();
    shader.start();
    shader.loadProjectionMatrix(projectionMatrix);
    shader.stop();
  }

  public void prepare() {
    GL11.glEnable(GL11.GL_DEPTH_TEST);
    GL11.glClearColor(0.2f, 0.6f, 1.0f, 1);
    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
  }

  public void render(Camera camera) {
    prepare();
    shader.start();
    shader.loadViewMatrix(camera);
    renderer.render(entities);
    shader.stop();

    entities.clear();
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
    float yScale = (float) (1f / Math.tan(Math.toRadians(FOV / 2)));
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
}
