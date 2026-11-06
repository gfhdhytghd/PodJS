#pragma once
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace podjs_fonts {
// One bounded JNI transfer replaces File.length/lastModified for every glyph.
// This is live metadata, not a trusted persisted catalog. Failed scans fall
// back to Java's ordinary directory scan without changing cache semantics.
inline bool scanGlyphDirectory(const char* path,std::vector<uint8_t>& out) {
  out.clear();DIR* dir=opendir(path);if(!dir)return false;
  auto number=[&](uint64_t value,int bytes){for(int i=bytes-1;i>=0;--i)out.push_back(uint8_t(value>>(i*8)));};
  number(0x50474431,4);number(0,4);uint32_t count=0;bool valid=true;
  while(true) {
    errno=0;auto* entry=readdir(dir);if(!entry){if(errno)valid=false;break;}
    const char* name=entry->d_name;if(!std::strcmp(name,".")||!std::strcmp(name,".."))continue;
    const size_t size=std::strlen(name);
    if(++count>16384||size<1||size>255){valid=false;break;}
    for(size_t i=0;i<size;i++)if(uint8_t(name[i])>=128)valid=false;
    if(!valid)break;
    struct stat info{};uint64_t length=0,modified=0;
    if(fstatat(dirfd(dir),name,&info,0)==0) {
      length=info.st_size<0?0:uint64_t(info.st_size);
      if(info.st_mtim.tv_sec>=0)modified=uint64_t(info.st_mtim.tv_sec)*1000+uint64_t(info.st_mtim.tv_nsec)/1000000;
    }
    number(size,2);out.insert(out.end(),name,name+size);number(length,8);number(modified,8);
  }
  closedir(dir);
  if(!valid){out.clear();return false;}
  for(int i=0;i<4;i++)out[4+i]=uint8_t(count>>((3-i)*8));return true;
}
}
