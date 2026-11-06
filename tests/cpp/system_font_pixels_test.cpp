#include "system_font_pixels.h"
#include <array>
#include <bit>
#include <cassert>
#include <cstdio>
int main() {
  std::array<uint32_t,7> input{std::bit_cast<uint32_t>(12.5f),std::bit_cast<uint32_t>(1.0f),0,0x80402010,0xffffffff,0x00010203,0xff191919};
  std::array<uint8_t,16> output{};float advance=0,xoff=0,yoff=-1;
  assert(podjs_fonts::decodeArgb(input.data(),input.size(),&advance,&xoff,&yoff,output.data(),output.size()));
  assert(advance==12.5f&&xoff==1.0f&&yoff==0);
  const std::array<uint8_t,16> expected{64,32,16,128,255,255,255,255,1,2,3,0,25,25,25,255};assert(output==expected);
  assert(!podjs_fonts::decodeArgb(input.data(),input.size()-1,&advance,&xoff,&yoff,output.data(),output.size()));
  assert(!podjs_fonts::decodeArgb(input.data(),input.size(),&advance,&xoff,&yoff,output.data(),output.size()-1));
  std::puts("system_font_pixels_test: metric bits and straight ARGB/RGBA differential PASS");
}
