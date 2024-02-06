in vec2 pass_textureCoords;

uniform sampler2D textureSampler;

void main(void){
  gl_FragColor=texture2D(textureSampler,pass_textureCoords);
}