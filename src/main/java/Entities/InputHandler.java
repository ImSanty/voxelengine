package Entities;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Centralizes all raw keyboard & mouse queries so game logic can be decoupled
 * and later abstracted / remapped.
 */
public class InputHandler {
  public boolean isKeyDown(int key) {
    return Keyboard.isKeyDown(key);
  }

  public int getWheelDelta() {
    return Mouse.getDWheel();
  }

  public boolean isMouseGrabbed() {
    return Mouse.isGrabbed();
  }

  public int getMouseDX() {
    return Mouse.getDX();
  }

  public int getMouseDY() {
    return Mouse.getDY();
  }

  // Convenience semantic helpers
  public boolean sprintModifierHeld() {
    return isKeyDown(Keyboard.KEY_LCONTROL) || isKeyDown(Keyboard.KEY_RCONTROL);
  }

  public boolean crouchHeld() {
    return isKeyDown(Keyboard.KEY_LSHIFT) || isKeyDown(Keyboard.KEY_RSHIFT);
  }

  public boolean jumpPressed() {
    return isKeyDown(Keyboard.KEY_SPACE);
  }

  public boolean forwardHeld() {
    return isKeyDown(Keyboard.KEY_W);
  }

  public boolean backHeld() {
    return isKeyDown(Keyboard.KEY_S);
  }

  public boolean leftHeld() {
    return isKeyDown(Keyboard.KEY_A);
  }

  public boolean rightHeld() {
    return isKeyDown(Keyboard.KEY_D);
  }

  public boolean noclipToggleCombo() {
    return isKeyDown(Keyboard.KEY_F3) && isKeyDown(Keyboard.KEY_N);
  }
}
