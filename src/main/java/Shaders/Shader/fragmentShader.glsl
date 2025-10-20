#version 330 core
in vec2 pass_textureCoords;
in vec3 pass_normal;
in vec3 pass_worldPos;
in float pass_ao;

uniform sampler2D textureSampler;
uniform int debugMode;// 0=World textured,2=Normals,5=Grid,6=AO visualization,10/11/12 debug overlays
uniform vec3 cameraPos;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
// AO tuning
uniform float aoStrength;// 0=off 1=normal >1 exaggerate
uniform float aoMin;// minimum AO factor clamp
uniform float aoMax;// maximum AO factor clamp

out vec4 fragColor;

void main(){
  if(debugMode==1){// wireframe base color no fog
    vec4 tex=texture(textureSampler,pass_textureCoords);
    fragColor=vec4(tex.rgb,1.);return;
  }
  if(debugMode==2){// normals
    vec3 nVis=normalize(pass_normal)*.5+.5;
    fragColor=vec4(nVis,1.);return;
  }
  if(debugMode==3){// height (use normal.y proxy)
    float h=clamp(normalize(pass_normal).y*.5+.5,0.,1.);
    fragColor=vec4(vec3(h),1.);return;
  }
  if(debugMode==4){// face direction
    vec3 n=normalize(pass_normal);
    vec3 c;
    if(abs(n.x)>.5)c=n.x>0.?vec3(1,0,0):vec3(.6,0,0);
    else if(abs(n.y)>.5)c=n.y>0.?vec3(0,1,0):vec3(0,.6,0);
    else c=n.z>0.?vec3(0,0,1):vec3(0,0,.6);
    fragColor=vec4(c,1.);return;
  }
  if(debugMode==5){// grid + AO
    ivec3 cell=ivec3(floor(pass_worldPos));
    int parity=(abs(cell.x)+abs(cell.z))&1;
    vec3 baseA=vec3(.05,.15,.60);
    vec3 baseB=vec3(.10,.55,.15);
    vec3 c=parity==0?baseA:baseB;
    vec3 f=fract(pass_worldPos);
    float edge=step(f.x,.04)+step(.96,f.x)+step(f.z,.04)+step(.96,f.z)+step(f.y,.04)+step(.96,f.y);
    edge=clamp(edge,0.,1.);
    // Use raw AO, expand its current 0.55..1 range to 0.65..1 for subtle contrast
    float rawAO=clamp(pass_ao,0.,1.);
    float aoScaled=(rawAO-.55)/.45;// 0..1
    aoScaled=clamp(aoScaled,0.,1.);
    float aoFactor=mix(.65,1.,aoScaled);
    c*=aoFactor;
    c=mix(c,vec3(1.),edge);
    fragColor=vec4(c,1.);return;
  }
  if(debugMode==10){fragColor=vec4(1,0,0,1);return;}
  if(debugMode==11){fragColor=vec4(1,1,0,1);return;}
  if(debugMode==12){fragColor=vec4(0,1,1,1);return;}
  
  // AO debug visualization
  if(debugMode==6){
    float aoVis=clamp(pass_ao,0.,1.);
    // invert for readability (dark crevices) -> optional; keep direct for now
    fragColor=vec4(vec3(aoVis),1.);
    return;
  }
  
  // ===== Default world textured path with ambient + directional shading =====
  vec4 tex=texture(textureSampler,pass_textureCoords);
  vec3 n=normalize(pass_normal);
  // Directional component (soft): prefer top lighting, dim bottom
  float shadeY=clamp(n.y*.5+.5,0.,1.);// 0 bottom ..1 top
  float dirShade=mix(.35,1.,shadeY);// 0.35 at bottom, 1.0 at top
  if(abs(n.y)<.5)dirShade*=.85;// side faces slightly darker
  if(n.y<-.5)dirShade*=.75;// bottom faces darker but not crushed
  // Mild horizontal falloff to add depth
  float horizDark=max(0.,-(n.x+n.z)*.2);
  dirShade=clamp(dirShade-horizDark,.2,1.);
  
  // AO factor (already in 0.4..1.0 range from mesh builder). Expand influence.
  float aoFactor=clamp(pass_ao,0.,1.);
  // Softer curve
  float aoEnhanced=pow(aoFactor,1.05);
  aoEnhanced=clamp(mix(1.,aoEnhanced,aoStrength),aoMin,aoMax);
  // Blend directional and AO: start from AO base then add directional light on top
  // so AO never fully vanishes on bright tops.
  float combined=aoEnhanced*(.55+.45*dirShade);// more light preserved
  combined=clamp(combined,.35,1.);
  vec3 litColor=tex.rgb*combined;
  
  // Simple linear fog (matches Java-side parameters)
  float dist=distance(cameraPos,pass_worldPos);
  float fogF=clamp((dist-fogStart)/max(.001,(fogEnd-fogStart)),0.,1.);
  vec3 finalColor=mix(litColor,fogColor,fogF);
  fragColor=vec4(finalColor,tex.a);
}