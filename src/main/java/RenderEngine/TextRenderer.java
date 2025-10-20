package RenderEngine;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

public class TextRenderer {
  private int textureId = -1;
  private int texWidth = 0;
  private int texHeight = 0;
  private String lastText = "";
  private Font font = new Font("Arial", Font.BOLD, 18);

  public TextRenderer() {
  }

  public void setFont(Font f) {
    this.font = f;
  }

  // Update the texture only when text changes
  public void updateText(String text) {
    if (text == null)
      text = "";
    if (text.equals(lastText))
      return;
    lastText = text;

    // Create a buffered image to draw the text
    BufferedImage img = createTextImage(text, font);

    texWidth = img.getWidth();
    texHeight = img.getHeight();

    // Convert BufferedImage to byte buffer (RGBA)
    int[] pixels = new int[texWidth * texHeight];
    img.getRGB(0, 0, texWidth, texHeight, pixels, 0, texWidth);

    ByteBuffer buffer = BufferUtils.createByteBuffer(texWidth * texHeight * 4);
    for (int y = 0; y < texHeight; y++) {
      for (int x = 0; x < texWidth; x++) {
        int pixel = pixels[y * texWidth + x];
        buffer.put((byte) ((pixel >> 16) & 0xFF)); // R
        buffer.put((byte) ((pixel >> 8) & 0xFF)); // G
        buffer.put((byte) (pixel & 0xFF)); // B
        buffer.put((byte) ((pixel >> 24) & 0xFF)); // A
      }
    }
    buffer.flip();

    if (textureId == -1) {
      textureId = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, texWidth, texHeight, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, buffer);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    } else {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, texWidth, texHeight, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, buffer);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
  }

  private BufferedImage createTextImage(String text, Font font) {
    if (text == null)
      text = "";
    String[] lines = text.split("\\n");
    // Measure
    BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = tmp.createGraphics();
    g2d.setFont(font);
    FontMetrics fm = g2d.getFontMetrics();
    int maxWidth = 0;
    for (String line : lines) {
      int w = fm.stringWidth(line);
      if (w > maxWidth)
        maxWidth = w;
    }
    int lineHeight = fm.getHeight();
    int width = maxWidth + 8; // padding
    int height = lineHeight * lines.length + 4;
    g2d.dispose();

    BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setFont(font);
    g.setColor(new Color(0, 0, 0, 0));
    g.fillRect(0, 0, width, height);
    g.setColor(Color.WHITE);
    int y = fm.getAscent() + 2;
    for (String line : lines) {
      g.drawString(line, 4, y);
      y += lineHeight;
    }
    g.dispose();
    return img;
  }

  public void render(int x, int y) {
    if (textureId == -1)
      return;

    // Save matrices
    GL11.glMatrixMode(GL11.GL_PROJECTION);
    GL11.glPushMatrix();
    GL11.glLoadIdentity();
    GL11.glOrtho(0, Display.getWidth(), Display.getHeight(), 0, -1, 1);

    GL11.glMatrixMode(GL11.GL_MODELVIEW);
    GL11.glPushMatrix();
    GL11.glLoadIdentity();

    // Setup render state
    GL11.glDisable(GL11.GL_DEPTH_TEST);
    // Ensure face culling doesn't hide the screen-space quad
    boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    if (cullEnabled) {
      GL11.glDisable(GL11.GL_CULL_FACE);
    }
    GL11.glEnable(GL11.GL_BLEND);
    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    GL11.glEnable(GL11.GL_TEXTURE_2D);

    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
    GL11.glColor3f(1f, 1f, 1f);

    GL11.glBegin(GL11.GL_QUADS);
    GL11.glTexCoord2f(0, 0);
    GL11.glVertex2f(x, y);

    GL11.glTexCoord2f(1, 0);
    GL11.glVertex2f(x + texWidth, y);

    GL11.glTexCoord2f(1, 1);
    GL11.glVertex2f(x + texWidth, y + texHeight);

    GL11.glTexCoord2f(0, 1);
    GL11.glVertex2f(x, y + texHeight);
    GL11.glEnd();

    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    GL11.glDisable(GL11.GL_TEXTURE_2D);
    GL11.glDisable(GL11.GL_BLEND);
    GL11.glEnable(GL11.GL_DEPTH_TEST);
    // Restore face culling state
    if (cullEnabled) {
      GL11.glEnable(GL11.GL_CULL_FACE);
    }

    // Restore matrices
    GL11.glMatrixMode(GL11.GL_MODELVIEW);
    GL11.glPopMatrix();

    GL11.glMatrixMode(GL11.GL_PROJECTION);
    GL11.glPopMatrix();
  }

  public void cleanUp() {
    if (textureId != -1) {
      GL11.glDeleteTextures(textureId);
      textureId = -1;
    }
  }
}
