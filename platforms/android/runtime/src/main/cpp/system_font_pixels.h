#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace podjs_fonts {
// Java Bitmap.getPixels publishes unpremultiplied ARGB integers. Preserve
// straight color/alpha and the float32 metric bit patterns at the C ABI.
inline bool decodeArgb(const uint32_t* cell,size_t words,float* advance,float* xoff,float* yoff,uint8_t* rgba,size_t length) {
  if(!cell||!rgba||length%4||words!=length/4+3)return false;
  std::memcpy(advance,cell,4);std::memcpy(xoff,cell+1,4);std::memcpy(yoff,cell+2,4);
  for(size_t i=0;i<length/4;i++){uint32_t p=cell[i+3];rgba[i*4]=uint8_t(p>>16);rgba[i*4+1]=uint8_t(p>>8);rgba[i*4+2]=uint8_t(p);rgba[i*4+3]=uint8_t(p>>24);}
  return true;
}
}
