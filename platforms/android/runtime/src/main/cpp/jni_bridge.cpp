#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <new>
#include <string>
#include <vector>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <linux/fs.h>
#include "podjs_runtime.h"
#include "touch_snapshot_queue.h"
#include "vulkan_renderer.h"

static double monoMs() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
struct MappedAsset {
  void* base = MAP_FAILED;
  size_t mappedLength = 0;
  const uint8_t* bytes = nullptr;
  size_t length = 0;
  ~MappedAsset() { if(base != MAP_FAILED) munmap(base,mappedLength); }
};
struct Host {
  double reportAt = 0, jsMs = 0, rasterMs = 0, presentMs = 0, maxMs = 0;
  unsigned ticks = 0, draws = 0, gpuDraws = 0, cpuDraws = 0;
  PodRuntime* runtime{};
  MappedAsset pak;
  std::unique_ptr<VulkanRenderer> renderer;
  TouchSnapshotQueue touches;
  std::vector<uint8_t> framebuffer;
  std::vector<uint8_t> mediaPixels;
  std::vector<jint> mediaArgb;
  uint32_t width{};
  uint32_t height{};
  uint32_t logicalWidth{};
  uint32_t logicalHeight{};
  int32_t rotary{};
  // Updated by the framework navigation bridge. Root back must be allowed to
  // reach Activity.onBackPressed so the app can return to the desktop.
  bool canGoBack = false;
  bool navigationKnown = false;
  bool frameFailed = false;
  uint64_t diagnosticFrameSequence = 0;
};
static void renderFrame(Host* h);
static void fail(JNIEnv* env, const char* message) { jclass c = env->FindClass("java/lang/IllegalStateException"); env->ThrowNew(c, message); }

extern "C" JNIEXPORT jint JNICALL Java_dev_podjs_runtime_PodSyncGuestFiles_publishNoReplace(
    JNIEnv* env,jclass,jint from,jbyteArray source,jint to,jbyteArray target) {
  auto component=[env](jbyteArray bytes,std::string& result) {
    if(!bytes)return false;
    jsize size=env->GetArrayLength(bytes); if(size<1||size>4096)return false;
    result.resize(size);env->GetByteArrayRegion(bytes,0,size,reinterpret_cast<jbyte*>(result.data()));
    return !env->ExceptionCheck() && result!="." && result!=".." && result.find('/')==std::string::npos && result.find('\0')==std::string::npos;
  };
  std::string a,b;if(from<0||to<0||!component(source,a)||!component(target,b))return EINVAL;
#ifdef __NR_renameat2
  return syscall(__NR_renameat2,from,a.c_str(),to,b.c_str(),RENAME_NOREPLACE)==0?0:errno;
#else
  return ENOSYS;
#endif
}

extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeAccessibilityEnabled(JNIEnv*,jclass,jlong p,jboolean enabled) {
  auto* h=reinterpret_cast<Host*>(p); if(h)pod_runtime_set_accessibility_enabled(h->runtime,enabled?1:0);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeAccessibilitySnapshot(JNIEnv* env,jclass,jlong p,jlongArray metadata) {
  auto* h=reinterpret_cast<Host*>(p); if(!h||!metadata||env->GetArrayLength(metadata)!=3)return nullptr;
  PodAccessibilitySnapshot snapshot{};
  if(pod_runtime_accessibility_snapshot(h->runtime,&snapshot)||!snapshot.changed)return nullptr;
  if(snapshot.byte_length>static_cast<size_t>(INT32_MAX))return nullptr;
  jlong values[3]; static_assert(sizeof(values[0])==sizeof(snapshot.content_hash));
  std::memcpy(&values[0],&snapshot.content_hash,sizeof(values[0]));
  values[1]=h->logicalWidth;values[2]=h->logicalHeight;
  env->SetLongArrayRegion(metadata,0,3,values);
  auto bytes=env->NewByteArray(static_cast<jsize>(snapshot.byte_length));
  if(bytes)env->SetByteArrayRegion(bytes,0,static_cast<jsize>(snapshot.byte_length),reinterpret_cast<const jbyte*>(snapshot.json));
  return bytes;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeAccessibilityAction(JNIEnv*,jclass,jlong p,jint id,jlong hash,jint action) {
  auto* h=reinterpret_cast<Host*>(p);
  return h&&pod_runtime_accessibility_action(h->runtime,id,static_cast<uint64_t>(hash),action)==0;
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativeOpen(
    JNIEnv* env, jclass, jbyteArray config) {
  if (!config) { fail(env, "Missing background config"); return 0; }
  jsize length = env->GetArrayLength(config);
  if (length <= 0 || length > 2 * 1024 * 1024) { fail(env, "Invalid background config size"); return 0; }
  std::vector<uint8_t> bytes(length);
  env->GetByteArrayRegion(config, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  if (env->ExceptionCheck()) return 0;
  auto* run = pod_background_open(bytes.data(), bytes.size());
  if (!run) fail(env, pod_runtime_last_error());
  return reinterpret_cast<jlong>(run);
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativeExecute(
    JNIEnv* env, jclass, jlong handle) {
  const char* result = pod_background_execute(reinterpret_cast<PodBackgroundRun*>(handle));
  if (!result) { fail(env, "Background execution unavailable"); return nullptr; }
  // Structured run results contain only fixed ASCII status/code keys and numbers.
  return env->NewStringUTF(result);
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativeCancel(
    JNIEnv*, jclass, jlong handle) { pod_background_cancel(reinterpret_cast<PodBackgroundRun*>(handle)); }
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativePoll(
    JNIEnv* env, jclass, jlong handle) {
  const char* request=pod_background_poll(reinterpret_cast<PodBackgroundRun*>(handle));
  if (!request) return nullptr;
  auto length=static_cast<jsize>(std::strlen(request));
  jbyteArray result=env->NewByteArray(length);
  if (result) env->SetByteArrayRegion(result,0,length,reinterpret_cast<const jbyte*>(request));
  return result;
}
extern "C" JNIEXPORT jint JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativeReply(
    JNIEnv* env, jclass, jlong handle, jbyteArray response) {
  if (!handle || !response) return -1;
  jsize length=env->GetArrayLength(response);
  if (length<=0 || length>1024*1024) return -1;
  std::vector<uint8_t> bytes(length);
  env->GetByteArrayRegion(response,0,length,reinterpret_cast<jbyte*>(bytes.data()));
  if (env->ExceptionCheck()) return -1;
  return pod_background_reply(reinterpret_cast<PodBackgroundRun*>(handle),bytes.data(),bytes.size());
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativeClose(
    JNIEnv*, jclass, jlong handle) { pod_background_close(reinterpret_cast<PodBackgroundRun*>(handle)); }

extern "C" JNIEXPORT jlong JNICALL Java_dev_podjs_runtime_PodSyncSession_nativeOpen(
    JNIEnv* env, jclass, jbyteArray config) {
  if (!config) { fail(env, "Missing session config"); return 0; }
  jsize length = env->GetArrayLength(config);
  if (length <= 0 || length > 8192) { fail(env, "Invalid session config size"); return 0; }
  std::vector<uint8_t> bytes(length);
  env->GetByteArrayRegion(config, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  if (env->ExceptionCheck()) return 0;
  auto* session = pod_sync_session_open(bytes.data(), bytes.size());
  // Volatile writes keep the temporary key-bearing buffer clear before release.
  volatile uint8_t* clear = bytes.data();
  for (size_t i = 0; i < bytes.size(); ++i) clear[i] = 0;
  if (!session) fail(env, pod_runtime_last_error());
  return reinterpret_cast<jlong>(session);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_podjs_runtime_PodSyncSession_nativeCommand(
    JNIEnv* env, jclass, jlong handle, jbyteArray command) {
  if (!handle || !command) { fail(env, "Invalid session command"); return nullptr; }
  jsize length = env->GetArrayLength(command);
  if (length <= 0 || length > 2 * 1024 * 1024) { fail(env, "Invalid session command size"); return nullptr; }
  std::vector<uint8_t> bytes(length);
  env->GetByteArrayRegion(command, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  const char* reply = pod_sync_session_command(reinterpret_cast<PodSyncSession*>(handle), bytes.data(), bytes.size());
  if (!reply) { fail(env, "Missing session response"); return nullptr; }
  auto count = static_cast<jsize>(std::strlen(reply));
  jbyteArray result = env->NewByteArray(count);
  if (result) env->SetByteArrayRegion(result, 0, count, reinterpret_cast<const jbyte*>(reply));
  return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodSyncSession_nativeClose(
    JNIEnv*, jclass, jlong handle) {
  pod_sync_session_close(reinterpret_cast<PodSyncSession*>(handle));
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_podjs_runtime_PodSyncFileReceiver_nativeOpen(
    JNIEnv* env, jclass, jstring root) {
  if (!root) { fail(env, "Missing receiver directory"); return 0; }
  const char* path = env->GetStringUTFChars(root, nullptr);
  if (!path) return 0;
  auto* receiver = pod_sync_files_open(path);
  env->ReleaseStringUTFChars(root, path);
  if (!receiver) fail(env, pod_runtime_last_error());
  return reinterpret_cast<jlong>(receiver);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_podjs_runtime_PodSyncFileReceiver_nativeCommand(
    JNIEnv* env, jclass, jlong handle, jbyteArray command) {
  if (!handle || !command) { fail(env, "Invalid receiver command"); return nullptr; }
  jsize length = env->GetArrayLength(command);
  if (length <= 0 || length > 96 * 1024) { fail(env, "Invalid command size"); return nullptr; }
  std::vector<uint8_t> bytes(length);
  env->GetByteArrayRegion(command, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  if (env->ExceptionCheck()) return nullptr;
  const char* reply = pod_sync_files_command(reinterpret_cast<PodSyncFiles*>(handle), bytes.data(), bytes.size());
  if (!reply) { fail(env, "Missing receiver response"); return nullptr; }
  auto count = static_cast<jsize>(std::strlen(reply));
  jbyteArray result = env->NewByteArray(count);
  if (result) env->SetByteArrayRegion(result, 0, count, reinterpret_cast<const jbyte*>(reply));
  return result;
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodSyncFileReceiver_nativeClose(
    JNIEnv*, jclass, jlong handle) {
  pod_sync_files_close(reinterpret_cast<PodSyncFiles*>(handle));
}

extern "C" JNIEXPORT jlong JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCreate(
    JNIEnv* env, jclass, jobject surface, jstring target, jint width, jint height, jfloat density, jint touchSlop, jint minFling, jint maxFling, jstring dataDir) {
  const char* t = env->GetStringUTFChars(target, nullptr); const char* d = env->GetStringUTFChars(dataDir, nullptr);
  if (std::string(t) != "android-watch" && std::string(t) != "wearos-watch") { fail(env, "PodJS target mismatch"); return 0; }
  ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
const char* capabilities = "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\",\"host.lifecycle\",\"host.theme\",\"display.round\",\"input.back\",\"net.http\",\"data.fs\",\"data.sqlite\",\"text.layout\",\"text.raster\",\"text.environment\",\"input.text\",\"input.time\",\"device.location\",\"data.secure\",\"crypto.basic\",\"data.files.chunks\",\"data.uri\",\"media.audio\",\"media.tts\",\"media.tts.spectrum\",\"media.video\",\"media.video.sessions\",\"runtime.timer\",\"display.power\",\"system.toast\",\"data.images\",\"system.browser.auth\",\"system.browser.auth.status\",\"net.download\",\"net.rfcomm\",\"net.tcp\",\"net.stream\",\"net.websocket\",\"net.discovery\",\"system.browser\",\"system.browser.siteData\",\"system.browser.availability\",\"system.browser.inApp\",\"system.share\",\"background.scheduled\",\"notification.local\",\"companion.sync.state\",\"companion.sync.message\",\"companion.sync.file\",\"host.takeover\"]";
  PodRuntimeConfig config{sizeof(config), t, PODJS_RUNTIME_ABI_VERSION, 2, (uint32_t)width, (uint32_t)height,
      density, POD_DISPLAY_ROUND, 0, 0, 0, 0, d, capabilities};
  auto host = std::make_unique<Host>(); host->runtime = pod_runtime_create(&config);
  if (!host->runtime) {
    ANativeWindow_release(window);
    env->ReleaseStringUTFChars(target, t); env->ReleaseStringUTFChars(dataDir, d);
    fail(env, pod_runtime_last_error()); return 0;
  }
  if (pod_runtime_set_scroll_configuration(host->runtime, touchSlop, minFling, maxFling) != 0) {
    ANativeWindow_release(window);
    pod_runtime_destroy(host->runtime); host->runtime = nullptr;
    env->ReleaseStringUTFChars(target, t); env->ReleaseStringUTFChars(dataDir, d);
    fail(env, "Invalid Android scroll configuration"); return 0;
  }
  host->width = static_cast<uint32_t>(width); host->height = static_cast<uint32_t>(height);
  host->logicalWidth = pod_runtime_logical_width(host->runtime);
  host->logicalHeight = pod_runtime_logical_height(host->runtime);
  const uint32_t rasterWidth = host->logicalWidth * VulkanRenderer::kRasterScale;
  const uint32_t rasterHeight = host->logicalHeight * VulkanRenderer::kRasterScale;
  host->renderer = std::make_unique<VulkanRenderer>(window, width, height, rasterWidth, rasterHeight); ANativeWindow_release(window);
  host->framebuffer.resize(host->renderer->rasterBytes());
  env->ReleaseStringUTFChars(target, t); env->ReleaseStringUTFChars(dataDir, d);
  if (!host->renderer->valid()) {
    pod_runtime_destroy(host->runtime); host->runtime=nullptr;
    fail(env, "PodJS Vulkan renderer initialization failed"); return 0;
  }
  return reinterpret_cast<jlong>(host.release());
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeBoot(JNIEnv* env, jclass, jlong p, jbyteArray pak, jbyteArray js, jbyteArray manifest) {
  auto* h = reinterpret_cast<Host*>(p); if (!h) return;
  const double bootStart=monoMs();
  jsize pn=env->GetArrayLength(pak), jn=env->GetArrayLength(js), mn=env->GetArrayLength(manifest); std::vector<uint8_t> pb(pn), jb(jn), mb(mn+1);
  env->GetByteArrayRegion(pak,0,pn,reinterpret_cast<jbyte*>(pb.data())); env->GetByteArrayRegion(js,0,jn,reinterpret_cast<jbyte*>(jb.data()));
  env->GetByteArrayRegion(manifest,0,mn,reinterpret_cast<jbyte*>(mb.data()));
  const double copied=monoMs();
  int result=pod_runtime_load_pak(h->runtime,pb.data(),pb.size()); const double loaded=monoMs();
  if(!result) result=pod_runtime_validate_package(h->runtime,reinterpret_cast<char*>(mb.data()));
  const double validated=monoMs();
  if(!result) result=pod_runtime_eval_bundle(h->runtime,jb.data(),jb.size(),"app:///main.js");
  const double evaluated=monoMs();
  __android_log_print(ANDROID_LOG_INFO,"PodJSPerf","boot copyMs=%.1f pakMs=%.1f validateMs=%.1f evalMs=%.1f",copied-bootStart,loaded-copied,validated-loaded,evaluated-validated);
  if(result) fail(env,pod_runtime_last_error());
  else __android_log_print(ANDROID_LOG_INFO,"PodJS","%s",pod_runtime_receipt(h->runtime));
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeBootAssets(JNIEnv* env,jclass,jlong p,jobject assets) {
  auto* h=reinterpret_cast<Host*>(p); if(!h)return;
  const double began=monoMs();
  auto* manager=AAssetManager_fromJava(env,assets);
  if(!manager){if(!env->ExceptionCheck())fail(env,"PodJS AssetManager is unavailable");return;}
  using Asset=std::unique_ptr<AAsset,decltype(&AAsset_close)>;
  auto openAsset=[&](const char* name) {
    errno=0;
    Asset asset(AAssetManager_open(manager,name,AASSET_MODE_BUFFER),AAsset_close);
    const int error=errno;
    if(!asset) {
      const std::string message=std::string("PodJS cannot open asset ")+name+
          (error?std::string(": ")+std::strerror(error):": missing or unreadable");
      fail(env,message.c_str());
    }
    return asset;
  };
  Asset pak=openAsset("main.pak"); if(!pak)return;
  off64_t assetOffset=0,assetLength=0;
  const int fd=AAsset_openFileDescriptor64(pak.get(),&assetOffset,&assetLength);
  if(fd<0||assetOffset<0||assetLength<=0) {
    if(fd>=0)close(fd);
    fail(env,"PodJS main.pak must be stored (uncompressed) for mmap startup");return;
  }
  const long pageSize=sysconf(_SC_PAGESIZE);
  if(pageSize<=0||static_cast<uint64_t>(assetLength)>SIZE_MAX) {
    close(fd);fail(env,"PodJS main.pak mapping size is invalid");return;
  }
  const off64_t alignedOffset=assetOffset-(assetOffset%pageSize);
  const size_t delta=static_cast<size_t>(assetOffset-alignedOffset);
  if(static_cast<size_t>(assetLength)>SIZE_MAX-delta) {
    close(fd);fail(env,"PodJS main.pak mapping overflows address space");return;
  }
  const size_t mappedLength=delta+static_cast<size_t>(assetLength);
  void* mapping=mmap(nullptr,mappedLength,PROT_READ,MAP_PRIVATE,fd,alignedOffset);
  const int mapError=errno;
  close(fd);
  if(mapping==MAP_FAILED) {
    const std::string message=std::string("PodJS cannot mmap stored main.pak: ")+std::strerror(mapError);
    fail(env,message.c_str());return;
  }
  h->pak.base=mapping;h->pak.mappedLength=mappedLength;
  h->pak.bytes=static_cast<const uint8_t*>(mapping)+delta;
  h->pak.length=static_cast<size_t>(assetLength);
  pak.reset();
  const double mapped=monoMs();
  int result=pod_runtime_load_pak_mapped(h->runtime,h->pak.bytes,h->pak.length);
  const double loaded=monoMs();
  if(result){fail(env,pod_runtime_last_error());return;}
  Asset js=openAsset("main.js"); if(!js)return;
  Asset manifest=openAsset("pod.manifest.json"); if(!manifest)return;
  const auto* jb=static_cast<const uint8_t*>(AAsset_getBuffer(js.get()));
  if(!jb){fail(env,"PodJS cannot map asset main.js");return;}
  const auto* mb=static_cast<const char*>(AAsset_getBuffer(manifest.get()));
  if(!mb){fail(env,"PodJS cannot map asset pod.manifest.json");return;}
  const size_t jn=AAsset_getLength64(js.get());
  const std::string json(mb,AAsset_getLength64(manifest.get()));
  manifest.reset();
  result=pod_runtime_validate_package(h->runtime,json.c_str());
  const double validated=monoMs();
  if(!result) result=pod_runtime_eval_bundle(h->runtime,jb,jn,"app:///main.js");
  const double evaluated=monoMs();
  double firstFramePrepareMs=0;
  if(!result) {const double frameBegan=monoMs();renderFrame(h);firstFramePrepareMs=monoMs()-frameBegan;}
  PodPakStats stats{};stats.struct_size=sizeof(stats);pod_runtime_pak_stats(h->runtime,&stats);
  __android_log_print(ANDROID_LOG_INFO,"PodJSPerf","boot mmapMs=%.1f dirParseMs=%.3f pakHashMs=%.1f validateMs=%.1f evalMs=%.1f firstSlotLoadMs=%.3f loadedSlots=%u firstFramePrepareMs=%.1f",
      mapped-began,stats.directory_parse_micros/1000.0,stats.pak_hash_micros/1000.0,validated-loaded,evaluated-validated,
      stats.first_font_load_micros/1000.0,stats.loaded_font_slots,firstFramePrepareMs);
  if(result)fail(env,pod_runtime_last_error());
  else __android_log_print(ANDROID_LOG_INFO,"PodJS","%s",pod_runtime_receipt(h->runtime));
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeResize(JNIEnv* env,jclass,jlong p,jobject s,jint w,jint ht){auto*h=reinterpret_cast<Host*>(p);if(!h)return;h->width=static_cast<uint32_t>(w);h->height=static_cast<uint32_t>(ht);auto*n=ANativeWindow_fromSurface(env,s);h->renderer->replaceWindow(n,w,ht);ANativeWindow_release(n);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeDetachSurface(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(h)h->renderer->replaceWindow(nullptr,0,0);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeInput(JNIEnv* env,jclass,jlong p,jintArray ids,jfloatArray xy,jint count,jint rotary){auto*h=reinterpret_cast<Host*>(p);if(!h)return;std::vector<jint> id(count);std::vector<jfloat> pos(count*2);if(count){env->GetIntArrayRegion(ids,0,count,id.data());env->GetFloatArrayRegion(xy,0,count*2,pos.data());}std::vector<PodTouch> next(count);for(int i=0;i<count;i++)next[i]={(uint32_t)id[i],pos[i*2]*h->logicalWidth/std::max(1u,h->width),pos[i*2+1]*h->logicalHeight/std::max(1u,h->height)};h->touches.submit(std::move(next));h->rotary+=rotary;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePumpServices(JNIEnv*,jclass,jlong p) {
  auto* h = reinterpret_cast<Host*>(p); if (!h) return;
  if (pod_runtime_pump_services(h->runtime) < 0) __android_log_print(ANDROID_LOG_ERROR,"PodJS","Service pump failed: %s",pod_runtime_last_error());
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePumpServiceResults(JNIEnv*,jclass,jlong p) {
  auto* h = reinterpret_cast<Host*>(p); if (!h) return;
  if (pod_runtime_pump_service_results(h->runtime) < 0) __android_log_print(ANDROID_LOG_ERROR,"PodJS","Service result pump failed: %s",pod_runtime_last_error());
}
extern "C" JNIEXPORT jint JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeMediaTexture(JNIEnv* env,jclass,jlong p,jint handle,jintArray pixels,jint width,jint height,jboolean linear) {
  auto* h=reinterpret_cast<Host*>(p); if(!h||!pixels||width<1||height<1||width>1024||height>1024)return -1;
  const size_t count=static_cast<size_t>(width)*height;
  if(env->GetArrayLength(pixels)!=static_cast<jsize>(count))return -1;
  h->mediaArgb.resize(count);h->mediaPixels.resize(count*4);
  env->GetIntArrayRegion(pixels,0,static_cast<jsize>(count),h->mediaArgb.data());if(env->ExceptionCheck())return -1;
  for(size_t i=0;i<count;i++){const uint32_t v=static_cast<uint32_t>(h->mediaArgb[i]);h->mediaPixels[i*4]=v>>16;h->mediaPixels[i*4+1]=v>>8;h->mediaPixels[i*4+2]=v;h->mediaPixels[i*4+3]=v>>24;}
  if(handle<0)return pod_runtime_upload_rgba_sampled(h->runtime,h->mediaPixels.data(),h->mediaPixels.size(),width,height,linear?POD_TEXTURE_SAMPLING_LINEAR:POD_TEXTURE_SAMPLING_NEAREST);
  return pod_runtime_update_rgba(h->runtime,handle,h->mediaPixels.data(),h->mediaPixels.size())==0?handle:-1;
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFreeMediaTexture(JNIEnv*,jclass,jlong p,jint handle) {
  auto* h=reinterpret_cast<Host*>(p);if(h)pod_runtime_free_texture(h->runtime,handle);
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeBeginRendererTiming(JNIEnv*,jclass,jlong p,jint capacity) {
  auto* h = reinterpret_cast<Host*>(p);
  if (!h || !h->renderer || capacity < 1 || capacity > 4096) return JNI_FALSE;
  if (!h->renderer->beginRendererTiming(static_cast<uint32_t>(capacity))) return JNI_FALSE;
  h->diagnosticFrameSequence = 0;
  return JNI_TRUE;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeEndRendererTiming(JNIEnv* env,jclass,jlong p) {
  auto* h = reinterpret_cast<Host*>(p);
  if (!h || !h->renderer) return nullptr;
  // Detach first. Every subsequent return, including either allocation failure,
  // leaves native recording disabled and releases the former fixed buffer.
  auto trace = h->renderer->endRendererTiming();
  if (!trace) return nullptr;
  const size_t count = static_cast<size_t>(trace->header[3]);
  constexpr size_t headerSize = 7, fieldCount = 15;
  if (trace->header[1] != fieldCount || count > 4096) {
    fail(env,"Invalid renderer timing snapshot"); return nullptr;
  }
  const size_t length = headerSize + count * fieldCount;
  std::unique_ptr<jlong[]> values(new(std::nothrow) jlong[length]);
  if (!values) { fail(env,"Unable to allocate renderer timing snapshot"); return nullptr; }
  for (size_t i=0;i<headerSize;++i) values[i]=static_cast<jlong>(trace->header[i]);
  for (size_t i=0;i<count*fieldCount;++i) values[headerSize+i]=static_cast<jlong>(trace->data()[i]);
  jlongArray result=env->NewLongArray(static_cast<jsize>(length));
  if (!result) return nullptr;
  env->SetLongArrayRegion(result,0,static_cast<jsize>(length),values.get());
  return env->ExceptionCheck()?nullptr:result;
}
static void renderFrame(Host* h) {
  if (!h) return;
  auto hostTiming = h->renderer->beginHostFrame(++h->diagnosticFrameSequence);
  const double start = monoMs();
  const auto& touches = h->touches.nextFrame();
  PodInputFrame in{sizeof(in),0,0,touches.data(),(uint32_t)touches.size(),h->rotary,0}; h->rotary=0;
  auto guestTiming = h->renderer->diagnosticScope(podjs_gpu::RendererTimingStage::Guest);
  const int result = pod_runtime_frame(h->runtime,&in);
  guestTiming.finish(result==0,result);
  if (result != 0 && !h->frameFailed) __android_log_print(ANDROID_LOG_ERROR,"PodJS","Frame failed: %s",pod_runtime_last_error());
  h->frameFailed = result != 0;
  const double afterJs = monoMs();
  h->jsMs += afterJs-start; h->ticks++;
  int64_t submittedFlags = 0;
  if (result == 0) {
    PodDrawList list{};
    auto snapshotTiming = h->renderer->diagnosticScope(podjs_gpu::RendererTimingStage::Snapshot);
    const int snapshotResult = pod_runtime_snapshot(h->runtime,&list);
    snapshotTiming.finish(snapshotResult==0,snapshotResult);
    if (!snapshotResult && (list.changed || h->renderer->needsRedraw(list.content_hash))) {
      const double beforeRaster = monoMs();
      bool submitted = h->renderer->submitDrawList(h->runtime, list);
      if (submitted) {
        submittedFlags |= int64_t{1} << 16;
        h->gpuDraws++;
        h->presentMs += monoMs()-beforeRaster;
      } else if (h->renderer->valid()) {
        const bool rasterized = pod_runtime_render_rgba_transparent_incremental(h->runtime,VulkanRenderer::kRasterScale,h->framebuffer.data(),h->framebuffer.size())==0;
        const double afterRaster = monoMs();
        submitted = rasterized && h->renderer->submitRgba(h->framebuffer.data(),h->framebuffer.size(),list.content_hash);
        h->rasterMs += afterRaster-beforeRaster;
        h->presentMs += monoMs()-afterRaster;
        h->cpuDraws += submitted;
        if (submitted) submittedFlags |= int64_t{1} << 17;
      }
      h->draws += submitted;
    }
  }
  const double now=monoMs(); h->maxMs=std::max(h->maxMs,now-start);
  if (now-h->reportAt >= 1000) {
    if(h->reportAt != 0) __android_log_print(ANDROID_LOG_INFO,"PodJSPerf","windowMs=%.1f ticks=%u draws=%u jsMs=%.2f rasterMs=%.2f presentMs=%.2f maxMs=%.2f gpuDraws=%u cpuDraws=%u",now-h->reportAt,h->ticks,h->draws,h->jsMs/h->ticks,h->draws?h->rasterMs/h->draws:0,h->draws?h->presentMs/h->draws:0,h->maxMs,h->gpuDraws,h->cpuDraws);
    h->reportAt=now; h->jsMs=h->rasterMs=h->presentMs=h->maxMs=0; h->ticks=h->draws=h->gpuDraws=h->cpuDraws=0;
  }
  hostTiming.setFlags(submittedFlags);
  hostTiming.finish(result==0,result);
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFrame(JNIEnv*,jclass,jlong p) {
  renderFrame(reinterpret_cast<Host*>(p));
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePollEffect(JNIEnv* env,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return nullptr;const char* line=pod_runtime_poll_effect(h->runtime);return line?env->NewStringUTF(line):nullptr;}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePollNet(JNIEnv* env,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return nullptr;const char* line=pod_runtime_poll_net_command(h->runtime);return line?env->NewStringUTF(line):nullptr;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCompleteHttp(JNIEnv* env,jclass,jlong p,jint handle,jint status,jstring url,jstring headers,jbyteArray body){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*u=env->GetStringUTFChars(url,nullptr);const char*hs=env->GetStringUTFChars(headers,nullptr);jsize n=env->GetArrayLength(body);std::vector<uint8_t>b(n);env->GetByteArrayRegion(body,0,n,reinterpret_cast<jbyte*>(b.data()));pod_runtime_complete_http(h->runtime,handle,status,u,hs,b.data(),b.size());env->ReleaseStringUTFChars(url,u);env->ReleaseStringUTFChars(headers,hs);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFailHttp(JNIEnv* env,jclass,jlong p,jint handle,jstring code,jstring message){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*c=env->GetStringUTFChars(code,nullptr);const char*m=env->GetStringUTFChars(message,nullptr);pod_runtime_fail_http(h->runtime,handle,c,m);env->ReleaseStringUTFChars(code,c);env->ReleaseStringUTFChars(message,m);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeLifecycle(JNIEnv*,jclass,jlong p,jint s){auto*h=reinterpret_cast<Host*>(p);if(h){if(s==2){h->touches.clear();h->rotary=0;}pod_runtime_set_lifecycle(h->runtime,s);}}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeTheme(JNIEnv* env,jclass,jlong p,jstring theme){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*t=env->GetStringUTFChars(theme,nullptr);pod_runtime_set_theme(h->runtime,t);env->ReleaseStringUTFChars(theme,t);}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeBack(JNIEnv*,jclass,jlong p){
  auto*h=reinterpret_cast<Host*>(p);
  // Keep the pre-navigation-bridge behavior until the app has published its
  // depth. Once known, false means root and lets Activity exit to desktop.
  if(!h || (h->navigationKnown && !h->canGoBack)) return JNI_FALSE;
  return pod_runtime_post_event(h->runtime,"{\"t\":\"back\"}")==0 ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeNavigationState(JNIEnv*,jclass,jlong p,jboolean canGoBack){
  auto*h=reinterpret_cast<Host*>(p); if(h){ h->canGoBack = canGoBack == JNI_TRUE; h->navigationKnown = true; }
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCanGoBack(JNIEnv*,jclass,jlong p){
  auto*h=reinterpret_cast<Host*>(p); return h && (!h->navigationKnown || h->canGoBack) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeDestroy(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(h){pod_runtime_destroy(h->runtime);delete h;}}

extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePostEvent(JNIEnv* env,jclass,jlong p,jbyteArray event){
  auto*h=reinterpret_cast<Host*>(p);if(!h)return;
  jsize size=env->GetArrayLength(event);std::vector<char> data(size+1,0);
  env->GetByteArrayRegion(event,0,size,reinterpret_cast<jbyte*>(data.data()));
  pod_runtime_post_event(h->runtime,data.data());
}
