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

  private static final int WIDTH = 1280;
  private static final int HEIGHT = 720;
  private static boolean fullscreen = true;
  private static DisplayMode windowedMode = new DisplayMode(WIDTH, HEIGHT);
  private static DisplayMode desktopMode;

  public void createDisplay() {
    ContextAttribs attribs = new org.lwjgl.opengl.ContextAttribs(4, 5);
    attribs.withForwardCompatible(true);
    attribs.withProfileCore(true);

    try {
      desktopMode = Display.getDesktopDisplayMode();
      Display.setDisplayMode(windowedMode);
      Display.create(new PixelFormat());
      Display.setTitle("Voxel Engine - dev build");
      Display.setFullscreen(fullscreen);
      Display.setVSyncEnabled(false);
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
      boolean pressed = Keyboard.getEventKeyState();
      int key = Keyboard.getEventKey();
      if (pressed) {

        if (key == Keyboard.KEY_ESCAPE) {
          closeDisplay();
        }

        if (key == Keyboard.KEY_E && Mouse.isGrabbed()) {
          Mouse.setGrabbed(false);
        } else if (key == Keyboard.KEY_E && !Mouse.isGrabbed()) {
          Mouse.setGrabbed(true);
        }

        if (key == Keyboard.KEY_F11) {
          toggleFullscreen();
        }
        // Handle F3 combos: plain F3 toggles debug HUD; F3+D cycles debug modes; F3+B
        // toggles player bounds
        if (key == Keyboard.KEY_F3) {
          f3Held = true;
          f3ComboConsumed = false;
        } else if (f3Held) {
          if (!f3ComboConsumed && key == Keyboard.KEY_D) { // F3+D cycle debug modes
            ProjectV.GameLoop.debugRenderMode = (ProjectV.GameLoop.debugRenderMode + 1) % 7; // allow 0..6 (AO vis)
            f3ComboConsumed = true;
          } else if (!f3ComboConsumed && key == Keyboard.KEY_B) { // F3+B toggle player bounds
            ProjectV.GameLoop.showPlayerBounds = !ProjectV.GameLoop.showPlayerBounds;
            f3ComboConsumed = true;
          } else if (!f3ComboConsumed && key == Keyboard.KEY_N) { // F3+N reserved (noclip etc.)
            f3ComboConsumed = true;
          }
        }
      } else { // key release
        int released = key;
        if (released == Keyboard.KEY_F3) {
          if (!f3ComboConsumed) { // plain F3 press -> toggle HUD visibility
            ProjectV.GameLoop.showDebugHUD = !ProjectV.GameLoop.showDebugHUD;
          }
          f3Held = false;
          f3ComboConsumed = false;
        }
      }
    }
  }

  // F3 state tracking
  private boolean f3Held = false;
  private boolean f3ComboConsumed = false;

  private void toggleFullscreen() {
    try {
      fullscreen = !fullscreen;
      if (fullscreen) {
        if (desktopMode == null)
          desktopMode = Display.getDesktopDisplayMode();
        Display.setDisplayMode(desktopMode);
        Display.setFullscreen(true);
      } else {
        Display.setFullscreen(false);
        Display.setDisplayMode(windowedMode);
      }
      GL11.glViewport(0, 0, Display.getWidth(), Display.getHeight());
    } catch (LWJGLException e) {
      e.printStackTrace();
    }
  }

  public static void closeDisplay() {
    GameLoop.loader.cleanUp();
    GameLoop.shader.cleanUp();
    Display.destroy();
    System.exit(0);
  }
}
