#pragma once
#include <cstdint>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <zlib.h>
namespace podjs_fonts {
// Existing PG L1 records: big-endian header, CRC32, zlib-compressed little-
// endian ARGB words. Map only immutable compressed bytes; return owned pixels.
inline bool readGlyphRecord(const char* path,size_t expected,std::vector<uint8_t>& raw) {
  raw.clear();if(expected<12||expected>1024*1024||expected%4)return false;
  int fd=open(path,O_RDONLY|O_CLOEXEC);if(fd<0)return false;
  struct stat st{};if(fstat(fd,&st)||st.st_size<17||st.st_size>1024*1024+4096){close(fd);return false;}
  const size_t length=size_t(st.st_size);void* mapping=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(mapping==MAP_FAILED)return false;
  const auto* p=static_cast<const uint8_t*>(mapping);auto integer=[&](size_t offset,size_t count){uint64_t n=0;while(count--)n=(n<<8)|p[offset++];return n;};
  bool valid=integer(0,4)==0x50474c31&&integer(4,4)==expected;
  if(valid){raw.resize(expected);uLongf size=expected;valid=uncompress(raw.data(),&size,p+16,length-16)==Z_OK&&size==expected&&uint64_t(crc32(0,raw.data(),uInt(size)))==integer(8,8);}
  munmap(mapping,length);if(!valid)raw.clear();return valid;
}
}
