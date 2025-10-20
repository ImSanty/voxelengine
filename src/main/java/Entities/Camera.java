package Entities;

import org.lwjgl.util.vector.Vector3f;
import Physics.PlayerPhysics;

/**
 * Camera: holds player view transform and translates input into physics calls.
 * Low-level physics, collision, and diagnostics are delegated to PlayerPhysics.
 */

public class Camera {
  Vector3f position;
  float rotX;
  float rotY;
  float rotZ;
  // Movement config & runtime state
  private final PlayerProperties props = new PlayerProperties();
  private final InputHandler input = new InputHandler();
  private boolean sprinting = false;
  private boolean crouching = false;
  private boolean noclip = false;
  private boolean noclipComboHeld = false; // debounce
  // Physics (shares same position reference)
  private final PlayerPhysics physics;
  // Eye height smoothing
  private float currentEyeOffset = PlayerProperties.EYE_OFFSET;
  private float targetEyeOffset = PlayerProperties.EYE_OFFSET;
  private static final float EYE_LERP_SPEED = 12f; // higher = snappier (units: 1/seconds toward target)

  public Camera(Vector3f position, float rotX, float rotY, float rotZ) {
    this.position = position;
    this.rotX = rotX;
    this.rotY = rotY;
    this.rotZ = rotZ;
    this.physics = new PlayerPhysics(this.position);
  }

  public void move(double deltaSeconds) {
    handleToggle();
    handleScrollSpeed();
    // Sprint / crouch state
    boolean ctrlDown = input.sprintModifierHeld();
    boolean shiftDown = input.crouchHeld();
    sprinting = !noclip && ctrlDown && !shiftDown;
    crouching = !noclip && shiftDown;

    // Eye target update
    targetEyeOffset = PlayerProperties.EYE_OFFSET + (crouching ? PlayerProperties.CROUCH_EYE_DELTA : 0f);
    // Smooth toward target (exponential / frame-rate independent with deltaSeconds)
    float diffEye = targetEyeOffset - currentEyeOffset;
    if (Math.abs(diffEye) > 0.0001f) {
      float stepEye = (float) (EYE_LERP_SPEED * deltaSeconds);
      if (stepEye > 1f)
        stepEye = 1f; // clamp overshoot on very low FPS spikes
      currentEyeOffset += diffEye * stepEye;
    } else {
      currentEyeOffset = targetEyeOffset; // snap when extremely close
    }

    float appliedSpeed = props.computeAppliedSpeed(crouching, sprinting);

    float step = (float) (appliedSpeed * deltaSeconds);
    float forward = 0f, strafe = 0f;
    if (input.forwardHeld())
      forward += step;
    if (input.backHeld())
      forward -= step;
    if (input.leftHeld())
      strafe -= step;
    if (input.rightHeld())
      strafe += step;

    float dxMove = forward * (float) Math.sin(Math.toRadians(-rotY));
    float dzMove = forward * (float) Math.cos(Math.toRadians(-rotY));
    dxMove -= strafe * (float) Math.cos(Math.toRadians(rotY));
    dzMove -= strafe * (float) Math.sin(Math.toRadians(rotY));

    physics.setNoclip(noclip);
    physics.moveHorizontal(-dxMove, -dzMove, crouching);

    if (noclip) {
      if (input.jumpPressed())
        position.y += step;
      if (input.crouchHeld())
        position.y -= step;
    } else {
      if (input.jumpPressed())
        physics.jump();
      physics.applyVertical(deltaSeconds, input.jumpPressed(), crouching, props);
    }

    if (input.isMouseGrabbed()) {
      rotX += (float) -input.getMouseDY() / 20f;
      rotY += (float) input.getMouseDX() / 20f;
    }

    physics.updateHorizontalDiagnostics(rotY);
  }

  private void handleToggle() {
    boolean combo = input.noclipToggleCombo();
    if (combo) {
      if (!noclipComboHeld) {
        noclip = !noclip;
        noclipComboHeld = true;
      }
    } else {
      noclipComboHeld = false;
    }
  }

  private void handleScrollSpeed() {
    int wheel = input.getWheelDelta();
    if (wheel != 0) {
      float steps = wheel / 120f;
      float factor = 1.0f + 0.15f * steps;
      if (factor < 0.1f)
        factor = 0.1f;
      props.adjustSpeedFactor(factor);
    }
  }

  // Eye position (feet stored in position)
  public Vector3f getPosition() { // kept same name for existing code
    return new Vector3f(position.x, position.y + currentEyeOffset, position.z);
  }

  public Vector3f getFeetPosition() {
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

  public float getSpeed() {
    return props.getBaseSpeed();
  }

  public boolean isSprinting() {
    return sprinting;
  }

  public boolean isCrouching() {
    return crouching;
  }

  public boolean isNoclip() {
    return noclip;
  }

  public static float getPlayerHeight() {
    return PlayerProperties.PLAYER_HEIGHT;
  }

  public static float getPlayerRadius() {
    return PlayerProperties.PLAYER_RADIUS;
  }

  // Recent contact points (feet-relative)
  public float[] getRecentContactPoints() {
    return physics.getRecentContactPoints();
  }

  // Sweep debug
  public boolean hasLastSweepHit() {
    return physics.hasLastSweepHit();
  }

  public int getLastSweepBlockX() {
    return physics.getLastSweepBlockX();
  }

  public int getLastSweepBlockY() {
    return physics.getLastSweepBlockY();
  }

  public int getLastSweepBlockZ() {
    return physics.getLastSweepBlockZ();
  }

  // Distance diagnostics

  public float getDistNorth() {
    return physics.getDistNorth();
  }

  public float getDistSouth() {
    return physics.getDistSouth();
  }

  public float getDistEast() {
    return physics.getDistEast();
  }

  public float getDistWest() {
    return physics.getDistWest();
  }

  public float getDistForward() {
    return physics.getDistForward();
  }

  public float getDistLeft() {
    return physics.getDistLeft();
  }

  public float getDistRight() {
    return physics.getDistRight();
  }
}