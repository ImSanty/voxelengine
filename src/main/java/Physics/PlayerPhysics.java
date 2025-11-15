package Physics;

import org.lwjgl.util.vector.Vector3f;

import Entities.PlayerProperties;
import ProjectV.GameLoop;

/**
 * Player physics: movement integration, collision (cylinder), ground / edge
 * checks,
 * sweep debug info, and distance diagnostics. Camera supplies input; this
 * mutates
 * the shared position vector.
 */
public class PlayerPhysics {
  private final Vector3f position; // shared with Camera
  private float velY = 0f; // vertical velocity
  private boolean onGround = false; // ground / coyote state
  private long lastGroundContactMs = 0L; // last ground timestamp
  private boolean noclip = false; // disable collision

  // Contact points (debug)
  private static final int MAX_CONTACT_POINTS = 32;
  private final float[] contactPoints = new float[MAX_CONTACT_POINTS * 3];
  private int contactCount = 0;

  // Distance diagnostics (cardinal + yaw-relative)
  private float distNorth, distSouth, distEast, distWest;
  private float distForward, distLeft, distRight;

  public PlayerPhysics(Vector3f sharedPosition) {
    this.position = sharedPosition;
  }

  public void setNoclip(boolean noclip) {
    this.noclip = noclip;
  }

  public boolean isOnGround() {
    return onGround;
  }

  public float getVelY() {
    return velY;
  }

  public void setVelY(float v) {
    velY = v;
  }

  public void moveHorizontal(float dx, float dz, boolean crouching) {
    if (noclip) {
      position.x += dx;
      position.z += dz;
      return;
    }
    attemptHorizontalMove(dx, dz, crouching);
  }

  public void applyVertical(double deltaSeconds, boolean jumpPressed, boolean crouching, PlayerProperties props) {
    if (noclip)
      return;
    velY += PlayerProperties.GRAVITY * (float) deltaSeconds;
    float totalDy = velY * (float) deltaSeconds;
    int subSteps = (int) Math.ceil(Math.abs(totalDy) / 0.25f);
    if (subSteps < 1)
      subSteps = 1;
    float dyStep = totalDy / subSteps;
    for (int i = 0; i < subSteps; i++) {
      position.y += dyStep;
      resolveVertical(dyStep);
      if (velY == 0f && dyStep < 0)
        break;
    }
  }

  public void jump() {
    if (!noclip && onGround) {
      velY = PlayerProperties.JUMP_VELOCITY;
      onGround = false;
    }
  }

  // Horizontal movement / collision
  private void attemptHorizontalMove(float dx, float dz, boolean crouching) {
    if (dx == 0f && dz == 0f)
      return;
    // Edge safety while crouching
    if (crouching && onGround && !noclip) {
      float len = (float) Math.sqrt(dx * dx + dz * dz);
      if (len > 1e-6f && hasGroundSupportAt(position.x, position.y, position.z)) {
        float dirX = dx / len;
        float dirZ = dz / len;
        float stepSize = 0.05f;
        int steps = (int) Math.ceil(len / stepSize);
        float allowed = 0f;
        for (int i = 1; i <= steps; i++) {
          float testDist = Math.min(len, i * stepSize);
          float testX = position.x + dirX * testDist;
          float testZ = position.z + dirZ * testDist;
          if (!hasGroundSupportAt(testX, position.y, testZ)) {
            break;
          }
          allowed = testDist;
          if (testDist >= len)
            break;
        }
        if (allowed <= 1e-6f) {
          return;
        }
        dx = dirX * allowed;
        dz = dirZ * allowed;
      }
    }
    if (collidesCylinderAt(position.x, position.y, position.z)) {
      resolveHorizontalPenetration();
    }
    // Axis-separated sweep to avoid pre-collision side push and preserve speed
    moveAxis(dx, 0f);
    moveAxis(0f, dz);
  }

  // Move along a single axis using a swept test; the other axis is held fixed.
  private void moveAxis(float dx, float dz) {
    if (dx == 0f && dz == 0f)
      return;
    float len = (float) Math.sqrt(dx * dx + dz * dz);
    if (len < 1e-7f) {
      return;
    }
    float dirX = dx / len;
    float dirZ = dz / len;
    // Only sweep along the provided direction for the given distance
    SweepResult sr = sweepFirstHit(position.x, position.z, dirX, dirZ, len);
    if (!sr.hit) {
      position.x += dx;
      position.z += dz;
      return;
    }
    // Move to contact (no early stand-off)
    float travel = Math.max(0f, sr.t * len);
    position.x += dirX * travel;
    position.z += dirZ * travel;
    // Discard any remaining distance on this axis; the other axis movement will be
    // processed separately, which avoids diagonal pre-push and slowdown.
  }

  @SuppressWarnings("unused")
  private static class SweepResult {
    boolean hit;
    float t;
    float nx, nz;
  }

  // Last sweep debug info
  private boolean lastSweepHit = false;
  private int lastSweepBlockX, lastSweepBlockY, lastSweepBlockZ;
  private float lastSweepT, lastSweepNx, lastSweepNz;

  private SweepResult sweepFirstHit(float sx, float sz, float dirX, float dirZ, float distance) {
    SweepResult out = new SweepResult();
    float ex = sx + dirX * distance;
    float ez = sz + dirZ * distance;
    float r = PlayerProperties.PLAYER_RADIUS;
    int minX = (int) Math.floor(Math.min(sx, ex) - r - 1f);
    int maxX = (int) Math.floor(Math.max(sx, ex) + r + 1f);
    int minZ = (int) Math.floor(Math.min(sz, ez) - r - 1f);
    int maxZ = (int) Math.floor(Math.max(sz, ez) + r + 1f);
    int yMin = (int) Math.floor(position.y);
    int yMax = (int) Math.floor(position.y + PlayerProperties.PLAYER_HEIGHT - 0.001f);
    float bestT = Float.POSITIVE_INFINITY;
    float bestNx = 0f, bestNz = 0f;
    float invDx = dirX != 0f ? 1f / dirX : Float.POSITIVE_INFINITY;
    float invDz = dirZ != 0f ? 1f / dirZ : Float.POSITIVE_INFINITY;
    int hitBlockX = 0, hitBlockY = 0, hitBlockZ = 0;
    for (int y = yMin; y <= yMax; y++) {
      for (int x = minX; x <= maxX; x++) {
        for (int z = minZ; z <= maxZ; z++) {
          if (!isSolid(x, y, z))
            continue;
          float minBx = x - r;
          float maxBx = x + 1f + r;
          float minBz = z - r;
          float maxBz = z + 1f + r;
          float tx1, tx2;
          if (dirX == 0f) {
            if (sx <= minBx || sx >= maxBx)
              continue;
            tx1 = Float.NEGATIVE_INFINITY;
            tx2 = Float.POSITIVE_INFINITY;
          } else {
            tx1 = (minBx - sx) * invDx;
            tx2 = (maxBx - sx) * invDx;
          }
          if (tx1 > tx2) {
            float tmp = tx1;
            tx1 = tx2;
            tx2 = tmp;
          }
          float tz1, tz2;
          if (dirZ == 0f) {
            if (sz <= minBz || sz >= maxBz)
              continue;
            tz1 = Float.NEGATIVE_INFINITY;
            tz2 = Float.POSITIVE_INFINITY;
          } else {
            tz1 = (minBz - sz) * invDz;
            tz2 = (maxBz - sz) * invDz;
          }
          if (tz1 > tz2) {
            float tmp = tz1;
            tz1 = tz2;
            tz2 = tmp;
          }
          float tEnter = Math.max(tx1, tz1);
          float tExit = Math.min(tx2, tz2);
          if (tExit < 0f)
            continue;
          if (tEnter > tExit)
            continue;
          if (tEnter < 0f)
            tEnter = 0f;
          if (tEnter > 1f)
            continue;
          if (tEnter < bestT) {
            if (tx1 > tz1) {
              bestNx = (dirX > 0f) ? -1f : 1f;
              bestNz = 0f;
            } else {
              bestNx = 0f;
              bestNz = (dirZ > 0f) ? -1f : 1f;
            }
            bestT = tEnter;
            hitBlockX = x;
            hitBlockY = y;
            hitBlockZ = z;
          }
        }
      }
    }
    if (bestT != Float.POSITIVE_INFINITY) {
      out.hit = true;
      out.t = bestT;
      out.nx = bestNx;
      out.nz = bestNz;
      lastSweepHit = true;
      lastSweepBlockX = hitBlockX;
      lastSweepBlockY = hitBlockY;
      lastSweepBlockZ = hitBlockZ;
      lastSweepT = bestT;
      lastSweepNx = bestNx;
      lastSweepNz = bestNz;
    } else {
      lastSweepHit = false;
    }
    return out;
  }

  // Vertical movement & ground detection
  private void resolveVertical(float dy) {
    if (dy > 0) {
      if (collidesAt(position.x, position.y + PlayerProperties.PLAYER_HEIGHT - 0.05f, position.z)) {
        position.y = (float) Math.floor(position.y + PlayerProperties.PLAYER_HEIGHT) - PlayerProperties.PLAYER_HEIGHT
            - 0.001f;
        velY = 0f;
      }
    }
    if (dy < 0 && collidesCylinderAt(position.x, position.y - 0.001f, position.z)) {
      int safety = 0;
      while (collidesCylinderAt(position.x, position.y - 0.001f, position.z) && safety++ < 6) {
        position.y = (float) Math.floor(position.y) + 1.001f;
      }
      int micro = 0;
      while (collidesCylinderAt(position.x, position.y - 0.001f, position.z) && micro++ < 10) {
        position.y += 0.01f;
      }
      if (collidesCylinderAt(position.x, position.y - 0.001f, position.z)) {
        velY = 0f;
      }
    }
    boolean grounded = groundCheckRobust();
    long now = System.currentTimeMillis();
    if (grounded) {
      lastGroundContactMs = now;
      if (dy < 0) {
        float foot = (float) Math.floor(position.y);
        if (position.y - foot < 0.05f) {
          position.y = foot + 0.001f;
          velY = 0f;
        }
      }
      onGround = true;
    } else {
      long since = now - lastGroundContactMs;
      onGround = since <= PlayerProperties.COYOTE_MS;
      if (onGround && dy < 0)
        velY = Math.max(velY, -5f);
    }
  }

  // Collision helpers
  private boolean collidesCylinderAt(float cx, float baseY, float cz) {
    contactCount = 0;
    float headY = baseY + PlayerProperties.PLAYER_HEIGHT;
    int yMin = (int) Math.floor(baseY);
    int yMax = (int) Math.floor(headY - 0.001f);
    if (yMax < yMin)
      yMax = yMin;
    int xMin = (int) Math.floor(cx - PlayerProperties.PLAYER_RADIUS);
    int xMax = (int) Math.floor(cx + PlayerProperties.PLAYER_RADIUS);
    int zMin = (int) Math.floor(cz - PlayerProperties.PLAYER_RADIUS);
    int zMax = (int) Math.floor(cz + PlayerProperties.PLAYER_RADIUS);
    float r2 = PlayerProperties.PLAYER_RADIUS * PlayerProperties.PLAYER_RADIUS;
    for (int y = yMin; y <= yMax; y++) {
      for (int x = xMin; x <= xMax; x++) {
        for (int z = zMin; z <= zMax; z++) {
          if (!isSolid(x, y, z))
            continue;
          float closestX = clamp(cx, x, x + 1f);
          float closestZ = clamp(cz, z, z + 1f);
          float dx = cx - closestX;
          float dz = cz - closestZ;
          if (dx * dx + dz * dz <= r2) {
            if (contactCount < MAX_CONTACT_POINTS) {
              int base = contactCount * 3;
              contactPoints[base] = closestX - cx;
              contactPoints[base + 1] = 0f;
              contactPoints[base + 2] = closestZ - cz;
              contactCount++;
            }
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean groundCheckRobust() {
    float probeY = position.y - 0.05f;
    int blockY = (int) Math.floor(probeY - 0.001f);
    if (blockY < 0)
      return false;
    int samples = 12;
    float r = PlayerProperties.PLAYER_RADIUS * 0.85f;
    for (int i = 0; i < samples; i++) {
      double ang = (Math.PI * 2.0 * i) / samples;
      float sx = position.x + (float) Math.cos(ang) * r;
      float sz = position.z + (float) Math.sin(ang) * r;
      int bx = (int) Math.floor(sx);
      int bz = (int) Math.floor(sz);
      if (isSolid(bx, blockY, bz))
        return true;
    }
    if (isSolid((int) Math.floor(position.x), blockY, (int) Math.floor(position.z)))
      return true;
    return false;
  }

  private boolean hasGroundSupportAt(float cx, float y, float cz) {
    float probeY = y - 0.05f;
    int blockY = (int) Math.floor(probeY - 0.001f);
    if (blockY < 0)
      return false;
    int samples = 12;
    float r = PlayerProperties.PLAYER_RADIUS * 0.85f;
    for (int i = 0; i < samples; i++) {
      double ang = (Math.PI * 2.0 * i) / samples;
      float sx = cx + (float) Math.cos(ang) * r;
      float sz = cz + (float) Math.sin(ang) * r;
      int bx = (int) Math.floor(sx);
      int bz = (int) Math.floor(sz);
      if (isSolid(bx, blockY, bz))
        return true;
    }
    if (isSolid((int) Math.floor(cx), blockY, (int) Math.floor(cz)))
      return true;
    return false;
  }

  private void resolveHorizontalPenetration() {
    final float r = PlayerProperties.PLAYER_RADIUS;
    if (collidesCylinderAt(position.x, position.y, position.z)) {
      final float[][] DIRS = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 0.7071f, 0.7071f }, { 0.7071f, -0.7071f },
          { -0.7071f, 0.7071f }, { -0.7071f, -0.7071f } };
      float step = 0.002f;
      for (int iter = 0; iter < 8; iter++) {
        for (float[] d : DIRS) {
          float nx = d[0];
          float nz = d[1];
          float testX = position.x + nx * step;
          float testZ = position.z + nz * step;
          if (!collidesCylinderAt(testX, position.y, testZ)) {
            position.x = testX;
            position.z = testZ;
            return;
          }
        }
        step *= 1.8f;
        if (step > r * 0.6f)
          break;
      }
    }
    int iterations = 0;
    while (iterations++ < 4) {
      float cx = position.x;
      float cz = position.z;
      float bestPushX = 0f;
      float bestPushZ = 0f;
      float bestPen = 0f;
      boolean found = false;
      float headY = position.y + PlayerProperties.PLAYER_HEIGHT;
      int yMin = (int) Math.floor(position.y);
      int yMax = (int) Math.floor(headY - 0.001f);
      int xMin = (int) Math.floor(cx - r - 1f);
      int xMax = (int) Math.floor(cx + r + 1f);
      int zMin = (int) Math.floor(cz - r - 1f);
      int zMax = (int) Math.floor(cz + r + 1f);
      for (int y = yMin; y <= yMax; y++) {
        for (int x = xMin; x <= xMax; x++) {
          for (int z = zMin; z <= zMax; z++) {
            if (!isSolid(x, y, z))
              continue;
            float closestX = clamp(cx, x, x + 1f);
            float closestZ = clamp(cz, z, z + 1f);
            float dx = cx - closestX;
            float dz = cz - closestZ;
            float d2 = dx * dx + dz * dz;
            float r2 = r * r;
            if (d2 <= r2 + 1e-6f) {
              float dist = (float) Math.sqrt(Math.max(1e-12f, Math.min(d2, r2)));
              float pen = r - dist;
              boolean cornerLike = Math.abs(dx) > 1e-4f && Math.abs(dz) > 1e-4f;
              if (pen < 0.0005f && cornerLike)
                pen = 0.0025f;
              else if (pen <= 0f)
                continue;
              float nx, nz;
              if (dist < 1e-5f) {
                if (Math.abs(dx) > Math.abs(dz)) {
                  nx = (dx >= 0f) ? 1f : -1f;
                  nz = 0f;
                } else {
                  nx = 0f;
                  nz = (dz >= 0f) ? 1f : -1f;
                }
              } else {
                nx = dx / dist;
                nz = dz / dist;
              }
              if (pen > bestPen) {
                bestPen = pen;
                bestPushX = nx * (pen + 0.001f);
                bestPushZ = nz * (pen + 0.001f);
                found = true;
              }
            }
          }
        }
      }
      if (!found)
        break;
      position.x += bestPushX;
      position.z += bestPushZ;
    }
  }

  private boolean collidesAt(float wx, float wy, float wz) {
    int ix = (int) Math.floor(wx);
    int iy = (int) Math.floor(wy);
    int iz = (int) Math.floor(wz);
    return isSolid(ix, iy, iz);
  }

  private boolean isSolid(int wx, int wy, int wz) {
    if (wy < 0 || wy >= GameLoop.CHUNK_HEIGHT)
      return false;
    int chunkX = (int) Math.floor(wx / 16.0) * 16;
    int chunkZ = (int) Math.floor(wz / 16.0) * 16;
    String key = chunkX + "|0|" + chunkZ;
    Chunks.Chunk chunk = GameLoop.worldChunks.get(key);
    if (chunk == null)
      return false;
    int localX = wx - chunkX;
    int localZ = wz - chunkZ;
    return chunk.hasLocal(localX, wy, localZ);
  }

  private float clamp(float v, float a, float b) {
    return v < a ? a : (v > b ? b : v);
  }

  // Distance diagnostics update
  public void updateHorizontalDiagnostics(float yawDegrees) {
    distEast = sampleFreeDistanceDir(1, 0);
    distWest = sampleFreeDistanceDir(-1, 0);
    distSouth = sampleFreeDistanceDir(0, 1);
    distNorth = sampleFreeDistanceDir(0, -1);
    float yawRad = (float) Math.toRadians(yawDegrees);
    float dirX = (float) Math.sin(yawRad);
    float dirZ = -(float) Math.cos(yawRad);
    float lenInv = 1f / (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
    dirX *= lenInv;
    dirZ *= lenInv;
    distForward = sampleFreeDistanceDir(dirX, dirZ);
    float leftX = -dirZ;
    float leftZ = dirX;
    distLeft = sampleFreeDistanceDir(leftX, leftZ);
    distRight = sampleFreeDistanceDir(-leftX, -leftZ);
  }

  private float sampleFreeDistanceDir(float dirX, float dirZ) {
    float maxCheck = 2.0f;
    float len = (float) Math.sqrt(dirX * dirX + dirZ * dirZ);
    if (len < 1e-6f)
      return 0f;
    dirX /= len;
    dirZ /= len;
    float step = 0.05f;
    float traveled = 0f;
    float lastFree = 0f;
    boolean hit = false;
    while (traveled <= maxCheck) {
      float cx = position.x + dirX * traveled;
      float cz = position.z + dirZ * traveled;
      if (collidesCylinderAt(cx, position.y, cz)) {
        hit = true;
        break;
      }
      lastFree = traveled;
      traveled += step;
    }
    if (!hit)
      return maxCheck - PlayerProperties.PLAYER_RADIUS;
    float low = lastFree;
    float high = traveled;
    for (int i = 0; i < 8; i++) {
      float mid = 0.5f * (low + high);
      float cx = position.x + dirX * mid;
      float cz = position.z + dirZ * mid;
      if (collidesCylinderAt(cx, position.y, cz))
        high = mid;
      else
        low = mid;
    }
    float contactCenterDistance = low;
    return Math.max(0f, contactCenterDistance - PlayerProperties.PLAYER_RADIUS);
  }

  // Accessors (HUD / debug)
  public float getDistNorth() {
    return distNorth;
  }

  public float getDistSouth() {
    return distSouth;
  }

  public float getDistEast() {
    return distEast;
  }

  public float getDistWest() {
    return distWest;
  }

  public float getDistForward() {
    return distForward;
  }

  public float getDistLeft() {
    return distLeft;
  }

  public float getDistRight() {
    return distRight;
  }

  public float[] getRecentContactPoints() {
    if (contactCount == 0)
      return null;
    float[] out = new float[contactCount * 3];
    System.arraycopy(contactPoints, 0, out, 0, contactCount * 3);
    return out;
  }

  public boolean hasLastSweepHit() {
    return lastSweepHit;
  }

  public int getLastSweepBlockX() {
    return lastSweepBlockX;
  }

  public int getLastSweepBlockY() {
    return lastSweepBlockY;
  }

  public int getLastSweepBlockZ() {
    return lastSweepBlockZ;
  }

  public float getLastSweepT() {
    return lastSweepT;
  }

  public float getLastSweepNx() {
    return lastSweepNx;
  }

  public float getLastSweepNz() {
    return lastSweepNz;
  }
}
