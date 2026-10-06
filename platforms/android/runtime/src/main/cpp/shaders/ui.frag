#version 450
layout(set=0,binding=0) uniform sampler2D image;
struct RoundedClip { vec4 rect; vec4 radii; };
layout(std430,set=0,binding=1) readonly buffer RoundedClips {
    RoundedClip clips[];
};
layout(push_constant) uniform Primitive {
    ivec4 bounds; ivec4 xy01; ivec4 xy2Mode; uvec4 colors;
    vec4 uv01; vec4 uv2Extra; ivec4 texInfo; vec4 screen;
} p;
layout(location=0) out vec4 outColor;
uvec4 rgba(uint c) { return uvec4(c,c>>8,c>>16,c>>24)&255u; }
int edge(ivec2 a, ivec2 b, ivec2 q) { ivec2 d=b-a,v=q-a; return d.x*v.y-d.y*v.x; }
uvec4 fetch(ivec2 at) {
    ivec2 origin=ivec2(0),dim=textureSize(image,0);
    if(p.texInfo.w==2) {
        uint packedOrigin=floatBitsToUint(p.screen.z),packedSize=floatBitsToUint(p.screen.w);
        origin=ivec2(packedOrigin&65535u,packedOrigin>>16);
        dim=ivec2(packedSize&65535u,packedSize>>16);
    }
    return uvec4(round(texelFetch(image,origin+clamp(at,ivec2(0),dim-1),0)*255.0));
}
uvec4 sampleImage(vec2 uv) {
    ivec2 size=textureSize(image,0);
    if(p.texInfo.w==2) {uint packed=floatBitsToUint(p.screen.w);size=ivec2(packed&65535u,packed>>16);}
    vec2 dim=vec2(size);
    if(p.texInfo.x==0) return fetch(ivec2(uv*dim));
    // Match the software sampler's 8-bit fixed point bilinear interpolation.
    ivec2 fixedAt=ivec2(uv*dim*256.0)-128;
    // Coverage resampling clamps the half-texel fringe before interpolation.
    if(p.texInfo.w!=0) fixedAt=max(fixedAt,ivec2(0));
    ivec2 base=clamp(fixedAt>>8,ivec2(0),size-1); uvec2 frac=uvec2(fixedAt&255);
    uvec4 top=(fetch(base)*(256u-frac.x)+fetch(base+ivec2(1,0))*frac.x)>>8;
    uvec4 bot=(fetch(base+ivec2(0,1))*(256u-frac.x)+fetch(base+ivec2(1,1))*frac.x)>>8;
    return (top*(256u-frac.y)+bot*frac.y)>>8;
}
bool insideRoundedClip(RoundedClip clip, vec2 q) {
    if(any(lessThan(q,clip.rect.xy)) || any(greaterThanEqual(q,clip.rect.zw))) return false;
    vec2 radius=clip.radii.xy;
    if(radius.x<=0.0 || radius.y<=0.0) return true;
    vec2 center=clamp(q,clip.rect.xy+radius,clip.rect.zw-radius);
    vec2 delta=(q-center)/radius;
    return dot(delta,delta)<=1.0;
}
void main() {
    for(int i=0;i<p.texInfo.z;i++) {
        if(!insideRoundedClip(clips[p.texInfo.y+i],gl_FragCoord.xy)) discard;
    }
    int mode=p.xy2Mode.z;
    uvec4 c=rgba(p.colors.x);
    vec2 uv=vec2(0);
    if(mode==1 || mode==5) {
        ivec2 a=2*p.xy01.xy,b=2*p.xy01.zw,d=2*p.xy2Mode.xy;
        ivec2 q=2*ivec2(gl_FragCoord.xy)+1;
        int area=edge(a,b,d);
        if(area==0) discard;
        ivec3 w=ivec3(edge(b,d,q),edge(d,a,q),edge(a,b,q));
        if(area<0) { area=-area; w=-w; }
        if(any(lessThan(w,ivec3(0)))) discard;
        if(mode==1) {
            uvec4 c1=rgba(p.colors.y),c2=rgba(p.colors.z);
            // Keep constant channels exact; float weights avoid overflow for large triangles.
            precise vec4 mixed=(vec4(c)*float(w.x)+vec4(c1)*float(w.y)+vec4(c2)*float(w.z))/float(area);
            c=uvec4(floor(mixed+0.5));
        } else {
            precise vec3 f=vec3(w)*p.uv2Extra.z;
            uv=p.uv01.xy*f.x+p.uv01.zw*f.y+p.uv2Extra.xy*f.z;
        }
    } else if(mode==6) {
        vec2 a=intBitsToFloat(p.xy01.xy), b=intBitsToFloat(p.xy01.zw);
        vec2 d=intBitsToFloat(p.xy2Mode.xy), q=gl_FragCoord.xy;
        vec2 ab=b-a, ad=d-a, aq=q-a;
        float area=ab.x*ad.y-ab.y*ad.x;
        if(area==0.0) discard;
        float wb=(aq.x*ad.y-aq.y*ad.x)/area;
        float wd=(ab.x*aq.y-ab.y*aq.x)/area;
        float wa=1.0-wb-wd;
        if(wb<0.0 || wb>=1.0 || wd<0.0 || wd>=1.0) discard;
        uv=p.uv01.xy*wa+p.uv01.zw*wb+p.uv2Extra.xy*wd;
    } else if(mode==2) {
        bool horizontal=p.xy2Mode.w>=2;
        precise float f=horizontal ? (gl_FragCoord.x-float(p.xy01.x))*p.uv2Extra.z
                           : (gl_FragCoord.y-float(p.xy01.y))*p.uv2Extra.w;
        uvec4 end=rgba(p.colors.y);
        if(p.xy2Mode.w==0 || p.xy2Mode.w==2) { uvec4 t=c; c=end; end=t; }
        precise vec4 mixed=vec4(c)+(vec4(end)-vec4(c))*f;
        c=uvec4(floor(mixed+0.5));
    } else if(mode==3 || mode==4) {
        precise vec2 delta=gl_FragCoord.xy-vec2(p.xy01.xy);
        precise vec2 sampledUv=p.uv01.xy+(p.uv01.zw-p.uv01.xy)*delta*p.uv2Extra.zw;
        uv=sampledUv;
    }
    if(mode==3) c.a=(c.a*sampleImage(uv).a+127u)/255u;
    if(mode==4 || mode==5 || mode==6) c=(c*sampleImage(uv)+127u)/255u;
    if(c.a==0u) discard;
    outColor=vec4(c)/255.0;
}
