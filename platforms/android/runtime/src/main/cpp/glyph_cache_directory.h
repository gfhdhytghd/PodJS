#pragma once
#include <algorithm>
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace podjs_fonts {
// Live metadata and the same (mtime, name) LRU order as Java. No persisted
// catalog is trusted. Foreign names/failed scans fall back to the Java path.
inline bool scanGlyphDirectory(const char* path,std::vector<uint8_t>& out) {
  out.clear();DIR* dir=opendir(path);if(!dir)return false;
  struct Row {std::string name;uint64_t length,modified;bool glyph;};
  std::vector<Row> rows;bool valid=true;
  while(true) {
    errno=0;auto* entry=readdir(dir);if(!entry){if(errno)valid=false;break;}
    const char* name=entry->d_name;if(!std::strcmp(name,".")||!std::strcmp(name,".."))continue;
    const size_t size=std::strlen(name);
    if(rows.size()>=16384||size<1||size>255){valid=false;break;}
    for(size_t i=0;i<size;i++)if(uint8_t(name[i])>=128)valid=false;
    if(!valid)break;
    struct stat info{};uint64_t length=0,modified=0;
    if(fstatat(dirfd(dir),name,&info,0)==0) {
      length=info.st_size<0?0:uint64_t(info.st_size);
#if defined(__APPLE__)
      const auto time=info.st_mtimespec;
#else
      const auto time=info.st_mtim;
#endif
      if(time.tv_sec>=0)modified=uint64_t(time.tv_sec)*1000+uint64_t(time.tv_nsec)/1000000;
    }
    bool glyph=size==70&&std::strcmp(name+64,".glyph")==0&&length<=1024*1024+20;
    if(glyph)for(size_t i=0;i<64;i++)if(!((name[i]>='0'&&name[i]<='9')||(name[i]>='a'&&name[i]<='f'))){glyph=false;break;}
    rows.push_back({name,length,modified,glyph});
  }
  closedir(dir);if(!valid)return false;
  std::sort(rows.begin(),rows.end(),[](const Row& a,const Row& b){return a.modified!=b.modified?a.modified<b.modified:a.name<b.name;});
  auto number=[&](uint64_t value,int bytes){for(int i=bytes-1;i>=0;--i)out.push_back(uint8_t(value>>(i*8)));};
  number(0x50474432,4);number(rows.size(),4);
  for(const auto& row:rows){number(row.name.size(),2);out.insert(out.end(),row.name.begin(),row.name.end());number(row.length,8);number(row.modified,8);number(row.glyph?1:0,1);}
  return true;
}
}
