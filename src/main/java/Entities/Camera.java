package Entities;

import org.lwjgl.util.vector.Vector3f;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class Camera {
  Vector3f position;
  float rotX;
  float rotY;
  float rotZ;
  float speed = 0.6f;

  public Camera(Vector3f position, float rotX, float rotY, float rotZ) {
    this.position = position;
    this.rotX = rotX;
    this.rotY = rotY;
    this.rotZ = rotZ;
  }

  public void move() {
    float forwardMovement = 0;
    float sideMovement = 0;

    // Movement Keys
    if (Keyboard.isKeyDown(Keyboard.KEY_W)) {
      forwardMovement += speed;
    }
    if (Keyboard.isKeyDown(Keyboard.KEY_S)) {
      forwardMovement -= speed;
    }

    if (Keyboard.isKeyDown(Keyboard.KEY_A)) {
      sideMovement -= speed;
    }

    if (Keyboard.isKeyDown(Keyboard.KEY_D)) {
      sideMovement += speed;
    }

    float dx = forwardMovement * (float) Math.sin(Math.toRadians(-rotY));
    float dz = forwardMovement * (float) Math.cos(Math.toRadians(-rotY));

    dx -= sideMovement * (float) Math.cos(Math.toRadians(rotY));
    dz -= sideMovement * (float) Math.sin(Math.toRadians(rotY));

    position.x -= dx;
    position.z -= dz;

    if (Keyboard.isKeyDown(Keyboard.KEY_SPACE)) {
      position.y += speed;
    }
    if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
      position.y -= speed;
    }

    // Mouse Movement
    if (Mouse.isGrabbed()) {
      rotX += (float) -Mouse.getDY() / 20;
      rotY += (float) Mouse.getDX() / 20;
    }
  }

  // Getters
  public Vector3f getPosition() {
    return position;
  }

  public float getRotX() {
    return rotX;
  }

  public float getRotY() {
    return rotY;
  }

  public float getRotZ() {
    return rotZ;
  }
}