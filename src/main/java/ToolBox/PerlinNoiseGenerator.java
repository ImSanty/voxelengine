package ToolBox;

public class PerlinNoiseGenerator {

  // Lower amplitude & octave count to avoid extreme spikes and saturation.
  public static float AMPLITUDE = 512f; // base amplitude per first octave
  public static int OCTAVES = 8; // fewer octaves reduces GC and broad slopes
  public static float ROUGHNESS = 0.5f; // persistence
  // Base spatial scale: larger => smoother terrain globally
  public static float BASE_SCALE = 72f; // sample spacing denominator
  // Precompute total theoretical amplitude for normalization
  public static final float TOTAL_WEIGHT;
  static {
    float w = 0f;
    for (int i = 0; i < OCTAVES; i++) {
      w += Math.pow(ROUGHNESS, i);
    }
    TOTAL_WEIGHT = w;
  }

  private final int seed;
  private int xOffset = 0;
  private int zOffset = 0;

  public PerlinNoiseGenerator() {
    this((int) 0x7f3a21b5);
  }

  public PerlinNoiseGenerator(int seed) {
    this.seed = seed;
  }

  // only works with POSITIVE gridX and gridZ values!
  public PerlinNoiseGenerator(int gridX, int gridZ, int vertexCount, int seed) {
    this.seed = seed;
    xOffset = gridX * (vertexCount - 1);
    zOffset = gridZ * (vertexCount - 1);
  }

  public float generateHeight(int x, int z) {
    return sample(x + xOffset, z + zOffset, seed); // raw (un-normalized) value
  }

  // Instance version that accounts for this generator's offsets
  public float normalizedHeight(int x, int z) {
    float raw = sample(x + xOffset, z + zOffset, seed);
    float max = AMPLITUDE * TOTAL_WEIGHT; // theoretical max amplitude after all octaves
    float n = (raw / max + 1f) * 0.5f; // map [-1,1] -> [0,1]
    if (n < 0f)
      return 0f;
    if (n > 1f)
      return 1f;
    return n;
  }

  private static float getInterpolatedNoise(float x, float z, int seed) {
    int intX = fastFloor(x);
    int intZ = fastFloor(z);
    float fracX = x - intX;
    float fracZ = z - intZ;

    float v1 = getSmoothNoise(intX, intZ, seed);
    float v2 = getSmoothNoise(intX + 1, intZ, seed);
    float v3 = getSmoothNoise(intX, intZ + 1, seed);
    float v4 = getSmoothNoise(intX + 1, intZ + 1, seed);
    float i1 = interpolate(v1, v2, fracX);
    float i2 = interpolate(v3, v4, fracX);
    return interpolate(i1, i2, fracZ);
  }

  // Fast floor (avoids issues with casting negative floats which truncates toward
  // zero)
  private static int fastFloor(float v) {
    int i = (int) v;
    return v < i ? i - 1 : i;
  }

  private static float interpolate(float a, float b, float blend) {
    double theta = blend * Math.PI;
    float f = (float) (1f - Math.cos(theta)) * 0.5f;
    return a * (1f - f) + b * f;
  }

  private static float getSmoothNoise(int x, int z, int seed) {
    float corners = (getNoise(x - 1, z - 1, seed) + getNoise(x + 1, z - 1, seed) + getNoise(x - 1, z + 1, seed)
        + getNoise(x + 1, z + 1, seed)) / 16f;
    float sides = (getNoise(x - 1, z, seed) + getNoise(x + 1, z, seed) + getNoise(x, z - 1, seed)
        + getNoise(x, z + 1, seed)) / 8f;
    float center = getNoise(x, z, seed) / 4f;
    return corners + sides + center;
  }

  private static float getNoise(int x, int z, int seed) {
    int n = x * 49632 + z * 325176 + seed * 57;
    n = (n << 13) ^ n;
    int nn = (n * (n * n * 15731 + 789221) + 1376312589);
    return 1f - ((nn & 0x7fffffff) / 1073741824f); // [-1,1]
  }

  public static float sample(int x, int z, int seed) {
    float total = 0f;
    for (int i = 0; i < OCTAVES; i++) {
      float freq = (float) Math.pow(2, i);
      float amp = (float) Math.pow(ROUGHNESS, i) * AMPLITUDE;
      float sx = (x) / BASE_SCALE * freq;
      float sz = (z) / BASE_SCALE * freq;
      total += getInterpolatedNoise(sx, sz, seed) * amp;
    }
    return total; // range approx [-AMPLITUDE*TOTAL_WEIGHT, +same]
  }

  // Returns normalized height in [0,1]
  public static float normalizedHeight(int x, int z, int seed) {
    float raw = sample(x, z, seed);
    float max = AMPLITUDE * TOTAL_WEIGHT;
    float n = (raw / max + 1f) * 0.5f; // map [-1,1] -> [0,1]
    if (n < 0f)
      return 0f;
    if (n > 1f)
      return 1f;
    return n;
  }
}