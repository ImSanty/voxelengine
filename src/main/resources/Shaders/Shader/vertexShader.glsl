#version 330 core
layout(location=0)in vec3 position;
layout(location=1)in vec2 textureCoords;
layout(location=2)in vec3 normal;
layout(location=3)in float ao;

out vec2 pass_textureCoords;
out vec3 pass_normal;
out vec3 pass_worldPos;// added for fog distance
out float pass_ao;

uniform mat4 transformationMatrix;
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

void main(){
  vec4 worldPos=transformationMatrix*vec4(position,1.);
  gl_Position=projectionMatrix*viewMatrix*worldPos;
  pass_textureCoords=textureCoords;
  pass_normal=normal;// model unscaled
  pass_worldPos=worldPos.xyz;
  pass_ao=ao;
}
