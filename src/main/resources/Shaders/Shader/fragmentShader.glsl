#version 330 core
in vec2 pass_textureCoords;
in vec3 pass_normal;
in vec3 pass_worldPos;
in float pass_ao;

uniform sampler2D textureSampler;
uniform int debugMode;// 0=World textured,2=Normals,5=Grid,6=AO visualization,10=PlayerBounds,11=Contacts,12=ColliderCircle
uniform vec3 cameraPos;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform float aoStrength;
uniform float aoMin;
uniform float aoMax;

out vec4 fragColor;

void main(){
  if(debugMode==1){vec4 tex=texture(textureSampler,pass_textureCoords);fragColor=vec4(tex.rgb,1);return;}
  if(debugMode==2){vec3 nVis=normalize(pass_normal)*.5+.5;fragColor=vec4(nVis,1);return;}
  if(debugMode==3){float h=clamp(normalize(pass_normal).y*.5+.5,0.,1.);fragColor=vec4(vec3(h),1);return;}
  if(debugMode==4){vec3 n=normalize(pass_normal);vec3 c;if(abs(n.x)>.5)c=n.x>0.?vec3(1,0,0):vec3(.6,0,0);else if(abs(n.y)>.5)c=n.y>0.?vec3(0,1,0):vec3(0,.6,0);else c=n.z>0.?vec3(0,0,1):vec3(0,0,.6);fragColor=vec4(c,1);return;}
  if(debugMode==5){ivec3 cell=ivec3(floor(pass_worldPos));int parity=(abs(cell.x)+abs(cell.z))&1;vec3 baseA=vec3(.05,.15,.60);vec3 baseB=vec3(.10,.55,.15);vec3 c=parity==0?baseA:baseB;vec3 f=fract(pass_worldPos);float edge=step(f.x,.04)+step(.96,f.x)+step(f.z,.04)+step(.96,f.z)+step(f.y,.04)+step(.96,f.y);edge=clamp(edge,0.,1.);float rawAO=clamp(pass_ao,0.,1.);float aoScaled=(rawAO-.55)/.45;aoScaled=clamp(aoScaled,0.,1.);float aoFactor=mix(.65,1.,aoScaled);c*=aoFactor;c=mix(c,vec3(1.),edge);fragColor=vec4(c,1);return;}
  if(debugMode==6){float aoVis=clamp(pass_ao,0.,1.);fragColor=vec4(vec3(aoVis),1);return;}
  if(debugMode==10){fragColor=vec4(1,0,0,1);return;}
  if(debugMode==11){fragColor=vec4(1,1,0,1);return;}
  if(debugMode==12){fragColor=vec4(0,1,1,1);return;}
  vec4 tex=texture(textureSampler,pass_textureCoords);
  vec3 n=normalize(pass_normal);
  float shadeY=clamp(n.y*.5+.5,0.,1.);
  float dirShade=mix(.35,1.,shadeY);
  if(abs(n.y)<.5)dirShade*=.85;
  if(n.y<-.5)dirShade*=.75;
  float horizDark=max(0.,-(n.x+n.z)*.2);
  dirShade=clamp(dirShade-horizDark,.2,1.);
  float aoFactor=clamp(pass_ao,0.,1.);
  float aoEnhanced=pow(aoFactor,1.05);
  aoEnhanced=clamp(mix(1.,aoEnhanced,aoStrength),aoMin,aoMax);
  float combined=aoEnhanced*(.55+.45*dirShade);
  combined=clamp(combined,.35,1.);
  vec3 litColor=tex.rgb*combined;
  float dist=distance(cameraPos,pass_worldPos);
  float fogF=clamp((dist-fogStart)/max(.001,(fogEnd-fogStart)),0.,1.);
  vec3 finalColor=mix(litColor,fogColor,fogF);
  fragColor=vec4(finalColor,tex.a);
}
