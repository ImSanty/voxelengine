package Shaders;

import org.lwjgl.util.vector.Matrix4f;

import Entities.Camera;
import ToolBox.MathUtils;

public class StaticShader extends ShaderProgram {

  private static final String vertexFile = "/Shaders/Shader/vertexShader.glsl";
  private static final String fragmentFile = "/Shaders/Shader/fragmentShader.glsl";

  int location_transformationMatrix;
  int location_projectionMatrix;
  int location_viewMatrix;
  int location_textureSampler;
  int location_debugMode;
  int location_cameraPos;
  int location_fogColor;
  int location_fogStart;
  int location_fogEnd;
  int location_aoStrength;
  int location_aoMin;
  int location_aoMax;

  public StaticShader() {
    super(vertexFile, fragmentFile);
  }

  @Override
  protected void bindAttributes() {
    super.bindAttribute("position", 0);
    super.bindAttribute("textureCoords", 1);
    // bind normal attribute (matches Loader attribute index 2)
    super.bindAttribute("normal", 2);
    // per-vertex ambient occlusion factor
    super.bindAttribute("ao", 3);
  }

  @Override
  protected void getAllUniformLocations() {
    location_transformationMatrix = super.getUniformLocation("transformationMatrix");
    location_projectionMatrix = super.getUniformLocation("projectionMatrix");
    location_viewMatrix = super.getUniformLocation("viewMatrix");
    location_textureSampler = super.getUniformLocation("textureSampler");
    location_debugMode = super.getUniformLocation("debugMode");
    location_cameraPos = super.getUniformLocation("cameraPos");
    location_fogColor = super.getUniformLocation("fogColor");
    location_fogStart = super.getUniformLocation("fogStart");
    location_fogEnd = super.getUniformLocation("fogEnd");
    location_aoStrength = super.getUniformLocation("aoStrength");
    location_aoMin = super.getUniformLocation("aoMin");
    location_aoMax = super.getUniformLocation("aoMax");
  }

  public void loadTransformationMatrix(Matrix4f matrix) {
    super.loadMatrix(location_transformationMatrix, matrix);
  }

  public void loadProjectionMatrix(Matrix4f matrix) {
    super.loadMatrix(location_projectionMatrix, matrix);
  }

  public void loadViewMatrix(Camera camera) {
    super.loadMatrix(location_viewMatrix, MathUtils.createViewMatrix(camera));
  }

  public void loadTextureUnit(int unit) {
    // ensure shader uses texture unit index (e.g. 0)
    org.lwjgl.opengl.GL20.glUniform1i(location_textureSampler, unit);
  }

  public void loadDebugMode(int mode) {
    org.lwjgl.opengl.GL20.glUniform1i(location_debugMode, mode);
  }

  public void loadCameraPosition(org.lwjgl.util.vector.Vector3f pos) {
    org.lwjgl.opengl.GL20.glUniform3f(location_cameraPos, pos.x, pos.y, pos.z);
  }

  public void loadFog(org.lwjgl.util.vector.Vector3f color, float start, float end) {
    org.lwjgl.opengl.GL20.glUniform3f(location_fogColor, color.x, color.y, color.z);
    org.lwjgl.opengl.GL20.glUniform1f(location_fogStart, start);
    org.lwjgl.opengl.GL20.glUniform1f(location_fogEnd, end);
  }

  public void loadAoParams(float strength, float minVal, float maxVal) {
    org.lwjgl.opengl.GL20.glUniform1f(location_aoStrength, strength);
    org.lwjgl.opengl.GL20.glUniform1f(location_aoMin, minVal);
    org.lwjgl.opengl.GL20.glUniform1f(location_aoMax, maxVal);
  }
}
