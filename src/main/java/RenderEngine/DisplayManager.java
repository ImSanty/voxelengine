package RenderEngine;

import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import ProjectV.GameLoop;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class DisplayManager {

  private static final int WIDTH = 1024;
  private static final int HEIGHT = 600;

  public void createDisplay() {
    ContextAttribs attribs = new org.lwjgl.opengl.ContextAttribs(4, 5);
    attribs.withForwardCompatible(true);
    attribs.withProfileCore(true);

    try {
      Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
      Display.create(new PixelFormat());
      Display.setTitle("Voxel Engine - dev build");
      Display.setFullscreen(true);
      Display.setVSyncEnabled(true);
      GL11.glViewport(0, 0, Display.getWidth(), Display.getHeight());
    } catch (LWJGLException e) {
      e.printStackTrace();
    }
    Mouse.setGrabbed(true);
  }

  public void DisplayUpdate() {
    Display.update();
  }

  public void KeyHandler() {
    while (Keyboard.next()) {
      if (Keyboard.getEventKeyState()) {

        if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
          closeDisplay();
        }

        if (Keyboard.isKeyDown(Keyboard.KEY_E) && Mouse.isGrabbed()) {
          Mouse.setGrabbed(false);
        } else if (Keyboard.isKeyDown(Keyboard.KEY_E) && !Mouse.isGrabbed()) {
          Mouse.setGrabbed(true);
        }
      }
    }
  }

  public static void closeDisplay() {
    GameLoop.loader.cleanUp();
    GameLoop.shader.cleanUp();
    Display.destroy();
    System.exit(0);
  }
}
