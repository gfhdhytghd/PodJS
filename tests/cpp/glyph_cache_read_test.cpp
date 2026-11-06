#include "glyph_cache_read.h"
#include <cassert>
#include <filesystem>
#include <fstream>
int main(){
  char pattern[]="/tmp/podjs-glyph-read-XXXXXX";auto* dir=mkdtemp(pattern);assert(dir);auto path=std::filesystem::path(dir)/"cell.glyph";
  // Independently specified little-endian float32 metrics and ARGB words.
  const std::vector<uint8_t> raw={0,0,0x48,0x41,0,0,0x80,0x3f,0,0,0,0x40,0x9f,0x87,0x15,0x7f,0x55,0xaa,0,0xff,0,0,0,0};
  std::vector<uint8_t> packed;auto number=[&](uint64_t v,int count){for(int i=count-1;i>=0;i--)packed.push_back(uint8_t(v>>(i*8)));};
  number(0x50474c31,4);number(raw.size(),4);number(crc32(0,raw.data(),raw.size()),8);
  std::vector<uint8_t> compressed(compressBound(raw.size()));uLongf size=compressed.size();assert(compress2(compressed.data(),&size,raw.data(),raw.size(),Z_BEST_SPEED)==Z_OK);packed.insert(packed.end(),compressed.begin(),compressed.begin()+size);
  auto write=[&](){std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<char*>(packed.data()),packed.size());};write();
  std::vector<uint8_t> decoded;assert(podjs_fonts::readGlyphRecord(path.c_str(),raw.size(),decoded));assert(decoded==raw);
  assert(!podjs_fonts::readGlyphRecord(path.c_str(),raw.size()+4,decoded));assert(decoded.empty());
  packed[15]^=1;write();assert(!podjs_fonts::readGlyphRecord(path.c_str(),raw.size(),decoded));assert(decoded.empty());
  packed.resize(4);write();assert(!podjs_fonts::readGlyphRecord(path.c_str(),raw.size(),decoded));
  assert(!podjs_fonts::readGlyphRecord(path.c_str(),1024*1024+4,decoded));assert(!podjs_fonts::readGlyphRecord("/missing-glyph-record",raw.size(),decoded));
  std::filesystem::remove_all(dir);
}
