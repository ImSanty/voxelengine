package Entities;

/**
 * Holds player-tunable physical / movement properties and constants.
 * Separated from Camera so logic vs. data are decoupled.
 */
public class PlayerProperties {
  // Mutable base walk speed (adjusted via mouse wheel)
  private float speed = 4.4f; // blocks per second

  // Constants
  public static final float SPRINT_MULT = 1.45f;
  public static final float CROUCH_MULT = 0.30f;
  public static final float CROUCH_EYE_DELTA = -0.30f;
  public static final float MIN_SPEED = 1.0f;
  public static final float MAX_SPEED = 1000.0f;
  public static final float GRAVITY = -34f; // blocks/sec^2
  public static final float JUMP_VELOCITY = 7.1f; // blocks/sec upward initial
  public static final float PLAYER_HEIGHT = 1.80f;
  public static final float PLAYER_RADIUS = 0.21f;
  public static final float EYE_OFFSET = 1.62f;
  public static final long COYOTE_MS = 120L;

  public float getBaseSpeed() {
    return speed;
  }

  public void setBaseSpeed(float s) {
    speed = clamp(s, MIN_SPEED, MAX_SPEED);
  }

  public void adjustSpeedFactor(float factor) {
    speed = clamp(speed * factor, MIN_SPEED, MAX_SPEED);
  }

  public float computeAppliedSpeed(boolean crouching, boolean sprinting) {
    float applied = speed;
    if (crouching)
      applied *= CROUCH_MULT;
    else if (sprinting)
      applied *= SPRINT_MULT;
    return applied;
  }

  private float clamp(float v, float a, float b) {
    return v < a ? a : (v > b ? b : v);
  }
}
