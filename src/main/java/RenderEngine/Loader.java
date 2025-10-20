// ...existing code...

// ...existing code...

package RenderEngine;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import Models.RawModel;

public class Loader {
  // ...existing code...
  static List<Integer> VAOs = new ArrayList<Integer>();
  static List<Integer> VBOs = new ArrayList<Integer>();
  static List<Integer> textures = new ArrayList<Integer>();

  public RawModel loadToVAO(float[] vertices, int[] indices, float[] uv) {
    int vaoID = createVAO();
    storeDataInAttributeList(vertices, 0, 3);
    storeDataInAttributeList(uv, 1, 2);
    bindIndicesBuffer(indices);
    GL30.glBindVertexArray(0);
    return new RawModel(vaoID, indices.length);
  }

  public RawModel loadToVAO(float[] vertices, float[] uv) {
    int vaoID = createVAO();
    storeDataInAttributeList(vertices, 0, 3);
    storeDataInAttributeList(uv, 1, 2);
    GL30.glBindVertexArray(0);
    return new RawModel(vaoID, vertices.length);
  }

  // New method: load positions, uvs, and normals into VAO
  public RawModel loadToVAO(float[] vertices, float[] uv, float[] normals) {
    int vaoID = createVAO();
    storeDataInAttributeList(vertices, 0, 3);
    storeDataInAttributeList(uv, 1, 2);
    storeDataInAttributeList(normals, 2, 3);
    GL30.glBindVertexArray(0);
    return new RawModel(vaoID, vertices.length / 3);
  }

  // Extended method: positions, uvs, normals, per-vertex AO (single float)
  public RawModel loadToVAO(float[] vertices, float[] uv, float[] normals, float[] aos) {
    int vaoID = createVAO();
    storeDataInAttributeList(vertices, 0, 3);
    storeDataInAttributeList(uv, 1, 2);
    storeDataInAttributeList(normals, 2, 3);
    storeDataInAttributeList(aos, 3, 1);
    GL30.glBindVertexArray(0);
    return new RawModel(vaoID, vertices.length / 3);
  }

  private int createVAO() {
    int vaoID = GL30.glGenVertexArrays();
    VAOs.add(vaoID);
    GL30.glBindVertexArray(vaoID);

    return vaoID;
  }

  public int loadTexture(String fileName) {
    int textureID = -1;
    InputStream is = getClass().getResourceAsStream("../Resources/textures/" + fileName + ".png");
    if (is == null) {
      // try absolute resource path
      is = getClass().getResourceAsStream("/Resources/textures/" + fileName + ".png");
    }
    if (is == null) {
      // fallback to project filesystem locations (useful during development)
      String[] fallbacks = new String[] {
          "src/main/resources/Resources/textures/" + fileName + ".png",
          "src/main/java/Resources/textures/" + fileName + ".png"
      };
      for (String p : fallbacks) {
        java.io.File f = new java.io.File(p);
        if (f.exists()) {
          try {
            is = new java.io.FileInputStream(f);
            break;
          } catch (java.io.FileNotFoundException e) {
            // try next fallback
          }
        }
      }
    }
    if (is == null) {
      // texture not found in classpath or fallback locations
      System.err.println("Texture not found: " + fileName);
      return -1;
    }
    try {
      BufferedImage image = ImageIO.read(is);
      int width = image.getWidth();
      int height = image.getHeight();
      int[] pixelsRaw = new int[width * height];
      image.getRGB(0, 0, width, height, pixelsRaw, 0, width);

      ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      // image.getRGB returns ARGB ints; convert to RGBA
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          int pixel = pixelsRaw[y * width + x];
          pixels.put((byte) ((pixel >> 16) & 0xFF)); // R
          pixels.put((byte) ((pixel >> 8) & 0xFF)); // G
          pixels.put((byte) (pixel & 0xFF)); // B
          pixels.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
      }
      pixels.flip();

      textureID = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureID);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
          pixels);
      GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      textures.add(textureID);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return textureID;
  }

  private void storeDataInAttributeList(float[] data, int attributeNumber, int dimention) {
    int vboID = GL15.glGenBuffers();
    VBOs.add(vboID);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboID);
    FloatBuffer buffer = storeDataInFloatBuffer(data);
    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
    GL20.glVertexAttribPointer(attributeNumber, dimention, GL11.GL_FLOAT, false, 0, 0);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
  }

  private void bindIndicesBuffer(int[] indices) {
    int vboID = GL15.glGenBuffers();
    VBOs.add(vboID);
    GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, vboID);
    IntBuffer buffer = storeDataInIntBuffer(indices);
    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
  }

  IntBuffer storeDataInIntBuffer(int[] data) {
    IntBuffer buffer = BufferUtils.createIntBuffer(data.length);
    buffer.put(data);
    buffer.flip();

    return buffer;
  }

  private FloatBuffer storeDataInFloatBuffer(float[] data) {
    FloatBuffer buffer = BufferUtils.createFloatBuffer(data.length);
    buffer.put(data);
    buffer.flip();

    return buffer;
  }

  public void cleanUp() {
    VAOs.forEach(GL30::glDeleteVertexArrays);
    VBOs.forEach(GL15::glDeleteBuffers);
    textures.forEach(GL11::glDeleteTextures);
  }
}