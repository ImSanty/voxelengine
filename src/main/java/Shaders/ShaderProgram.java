package Shaders;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;

public abstract class ShaderProgram {
  int prorgamID;
  int vertexShaderID;
  int fragmentShaderID;

  FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

  public ShaderProgram(String vertexFile, String fragmentFile) {
    prorgamID = GL20.glCreateProgram();
    vertexShaderID = loadShader(vertexFile, GL20.GL_VERTEX_SHADER);
    fragmentShaderID = loadShader(fragmentFile, GL20.GL_FRAGMENT_SHADER);

    GL20.glAttachShader(prorgamID, vertexShaderID);
    GL20.glAttachShader(prorgamID, fragmentShaderID);
    bindAttributes();
    GL20.glLinkProgram(prorgamID);
    GL20.glValidateProgram(prorgamID);

    getAllUniformLocations();

  }

  protected abstract void getAllUniformLocations();

  protected int getUniformLocation(String varName) {
    return GL20.glGetUniformLocation(prorgamID, varName);
  }

  protected abstract void bindAttributes();

  protected void loadFloat(int location, float value) {
    GL20.glUniform1f(location, value);
  }

  protected void load2DVector(int location, Vector2f vec) {
    GL20.glUniform2f(location, vec.x, vec.y);
  }

  protected void load3DVector(int location, Vector3f vec) {
    GL20.glUniform3f(location, vec.x, vec.y, vec.z);
  }

  protected void loadMatrix(int location, Matrix4f mat) {
    mat.store(matrixBuffer);
    matrixBuffer.flip();

    GL20.glUniformMatrix4(location, false, matrixBuffer);
  }

  protected void loadBoolean(int location, boolean bool) {
    float value = 0;

    if (bool) {
      value = 1;
    }
    GL20.glUniform1f(location, value);
  }

  protected void bindAttribute(String variableName, int attribute) {
    GL20.glBindAttribLocation(prorgamID, attribute, variableName);
  }

  public void start() {
    GL20.glUseProgram(prorgamID);
  }

  public void stop() {
    GL20.glUseProgram(0);
  }

  public void cleanUp() {
    stop();
    GL20.glDetachShader(prorgamID, vertexShaderID);
    GL20.glDetachShader(prorgamID, fragmentShaderID);
    GL20.glDeleteShader(vertexShaderID);
    GL20.glDeleteShader(fragmentShaderID);
    GL20.glDeleteProgram(prorgamID);
  }

  private int loadShader(String file, int type) {
    StringBuilder shaderSource = new StringBuilder();
    InputStream in = getClass().getResourceAsStream(file);
    if (in == null) {
      System.err.println("Shader file not found: " + file);
      System.exit(-1);
    }
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));

    String line;
    try {
      while ((line = reader.readLine()) != null) {
        shaderSource.append(line).append("\n");
      }
      reader.close();
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println("Could not load the shader file!");
      System.exit(-1);
    }

    int shaderID = GL20.glCreateShader(type);
    GL20.glShaderSource(shaderID, shaderSource);
    GL20.glCompileShader(shaderID);

    if (GL20.glGetShaderi(shaderID, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
      System.out.println(GL20.glGetShaderInfoLog(shaderID, 2000));
      System.err.println("Could not compile the shader!");
      System.exit(-1);
    }
    return shaderID;
  }
}
