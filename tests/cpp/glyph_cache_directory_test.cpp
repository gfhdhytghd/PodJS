#include "glyph_cache_directory.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <map>
#include <fcntl.h>
#include <unistd.h>
int main() {
  char pattern[]="/tmp/podjs-directory-XXXXXX";const char* path=mkdtemp(pattern);assert(path);
  auto root=std::filesystem::path(path);
  std::ofstream(root/"first.glyph")<<"bytes";std::ofstream(root/".font-index")<<"metadata";
  timespec stamp[2]={{1234567,890000000},{1234567,890000000}};assert(utimensat(AT_FDCWD,(root/"first.glyph").c_str(),stamp,0)==0);
  assert(symlink("missing",(root/"broken").c_str())==0);
  std::vector<uint8_t> raw;assert(podjs_fonts::scanGlyphDirectory(path,raw));size_t at=0;
  auto read=[&](int bytes){uint64_t v=0;while(bytes--){assert(at<raw.size());v=(v<<8)|raw[at++];}return v;};
  assert(read(4)==0x50474431);assert(read(4)==3);
  std::map<std::string,std::pair<uint64_t,uint64_t>> rows;
  for(int i=0;i<3;i++){auto length=read(2);std::string name(reinterpret_cast<char*>(raw.data()+at),length);at+=length;auto size=read(8),time=read(8);rows[name]={size,time};}
  assert(at==raw.size());assert(rows["first.glyph"]==std::make_pair(uint64_t(5),uint64_t(1234567890)));
  assert(rows[".font-index"].first==8);assert(rows["broken"]==std::make_pair(uint64_t(0),uint64_t(0)));
  assert(!podjs_fonts::scanGlyphDirectory("/missing-podjs-directory",raw));
  std::ofstream(root/"non-ascii-\xc3\xa9")<<"fallback";assert(!podjs_fonts::scanGlyphDirectory(path,raw));assert(raw.empty());
  std::filesystem::remove_all(root);
}
