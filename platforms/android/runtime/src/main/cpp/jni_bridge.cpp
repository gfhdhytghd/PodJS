#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <algorithm>
#include <bit>
#include <array>
#include <chrono>
#include <memory>
#include <new>
#include <string>
#include <vector>
#include <cstring>
#include <cerrno>
#include <sstream>
#include <fstream>
#include <cstdio>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <linux/fs.h>
#include "frame_perf_window.h"
#include "podjs_runtime.h"
#include "touch_snapshot_queue.h"
#include "render_thread.h"

static double monoMs() { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
struct MappedAsset {
  void* base = MAP_FAILED;
  size_t mappedLength = 0;
  const uint8_t* bytes = nullptr;
  size_t length = 0;
  ~MappedAsset() { if(base != MAP_FAILED) munmap(base,mappedLength); }
};
struct Host {
  JavaVM* vm{};
  jclass fontFallback{};
  jclass benchmarkClass{};
  jmethodID benchmarkDispatch{};
  jmethodID fontRaster{};
  jmethodID fontMetrics{};
  podjs_perf::FramePerfWindow framePerf;
  podjs_gpu::RendererTimingRecorder hostDiagnostics;
  PodRuntime* runtime{};
  bool contentSubmitted{};
  MappedAsset pak;
  std::unique_ptr<RenderThread> renderer;
  TouchSnapshotQueue touches;
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
  uint64_t renderCheckpointId=0,lastSnapshotHash=0;
  double renderCheckpointUntilMs=0,lastSnapshotMonoMs=0;
  int renderCheckpointFrames=0;
  uint64_t lastResourceGeneration = 0;
  ~Host() {
    if((fontFallback||benchmarkClass)&&vm) {
      JNIEnv* env=nullptr;bool attached=vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK;
      if(attached&&vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
      if(fontFallback)env->DeleteGlobalRef(fontFallback);if(benchmarkClass)env->DeleteGlobalRef(benchmarkClass);if(attached)vm->DetachCurrentThread();
    }
  }
};
static uint64_t benchmarkDispatch(uintptr_t data,const uint8_t* line,size_t length) {
  auto* host=reinterpret_cast<Host*>(data);if(!host||!host->benchmarkClass||length>1024)return 0;
  JNIEnv* env=nullptr;if(host->vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK)return 0;
  // The Rust hook passes bounded UTF-8 JSON, never a retained service argument.
  std::string text(reinterpret_cast<const char*>(line),length);jstring value=env->NewStringUTF(text.c_str());
  jlong result=value?env->CallStaticLongMethod(host->benchmarkClass,host->benchmarkDispatch,value):0;
  if(value)env->DeleteLocalRef(value);if(env->ExceptionCheck()){env->ExceptionClear();return 0;}
  return result>0?uint64_t(result):0;
}
static int32_t systemGlyph(uintptr_t userdata,const uint16_t* text,size_t units,float px,uint32_t bold,
    uint32_t w,uint32_t h,uint32_t baseline,uint32_t density,float* advance,float* xoff,float* yoff,uint8_t* rgba,size_t length) {
  auto* host=reinterpret_cast<Host*>(userdata);JNIEnv* env=nullptr;
  bool attached=host->vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK;
  if(attached&&host->vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return 1;
  jstring cluster=env->NewString(reinterpret_cast<const jchar*>(text),jsize(units));
  auto bytes=cluster?static_cast<jbyteArray>(env->CallStaticObjectMethod(host->fontFallback,host->fontRaster,
      cluster,jfloat(px),jint(bold),jint(w),jint(h),jint(baseline),jint(density))):nullptr;
  int32_t result=1;
  if(env->ExceptionCheck())env->ExceptionClear();
  else if(bytes&&size_t(env->GetArrayLength(bytes))==length+12) {
    uint8_t metrics[12];env->GetByteArrayRegion(bytes,0,12,reinterpret_cast<jbyte*>(metrics));
    static_assert(std::endian::native==std::endian::little);
    std::memcpy(advance,metrics,4);std::memcpy(xoff,metrics+4,4);std::memcpy(yoff,metrics+8,4);
    env->GetByteArrayRegion(bytes,12,jsize(length),reinterpret_cast<jbyte*>(rgba));
    if(!env->ExceptionCheck())result=0;else env->ExceptionClear();
  }
  if(bytes)env->DeleteLocalRef(bytes);if(cluster)env->DeleteLocalRef(cluster);if(attached)host->vm->DetachCurrentThread();return result;
}
static int32_t systemFontMetrics(uintptr_t userdata,float px,uint32_t bold,uint32_t density,uint32_t* output) {
  auto* host=reinterpret_cast<Host*>(userdata);JNIEnv* env=nullptr;
  bool attached=host->vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK;
  if(attached&&host->vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return 1;
  auto values=static_cast<jintArray>(env->CallStaticObjectMethod(host->fontFallback,host->fontMetrics,jfloat(px),jint(bold),jint(density)));
  int32_t result=1;
  if(env->ExceptionCheck())env->ExceptionClear();
  else if(values&&env->GetArrayLength(values)==4){jint data[4];env->GetIntArrayRegion(values,0,4,data);for(int i=0;i<4;i++)output[i]=uint32_t(data[i]);result=0;}
  if(values)env->DeleteLocalRef(values);if(attached)host->vm->DetachCurrentThread();return result;
}
static void renderFrame(Host* h, int64_t frameTimeNanos = 0);
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeProbeGlyphCache(JNIEnv* env,jclass,jlong p,jbyteArray input,jint slot) {
  auto* h=reinterpret_cast<Host*>(p);if(!h||!input||slot<0||slot>63)return nullptr;
  jsize n=env->GetArrayLength(input);if(n>1024)return nullptr;std::vector<uint8_t> text(n),result(65537);
  env->GetByteArrayRegion(input,0,n,reinterpret_cast<jbyte*>(text.data()));
  size_t length=pod_runtime_probe_glyph_cache(h->runtime,text.data(),text.size(),uint32_t(slot),result.data(),65536);
  if(!length)return nullptr;result[length]=0;return env->NewStringUTF(reinterpret_cast<const char*>(result.data()));
}
static void fail(JNIEnv* env, const char* message) { jclass c = env->FindClass("java/lang/IllegalStateException"); env->ThrowNew(c, message); }

static void jsStackLog(uintptr_t, const uint8_t* line, size_t length) {
  __android_log_print(ANDROID_LOG_WARN,"PodJSPerf","%.*s",int(std::min(length,size_t(2200))),reinterpret_cast<const char*>(line));
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCreateJsStackProbe(JNIEnv*,jclass,jlong p) {
  auto* h=reinterpret_cast<Host*>(p);
  return h?reinterpret_cast<jlong>(pod_runtime_js_stack_probe(h->runtime,jsStackLog,0)):0;
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeRequestJsStack(JNIEnv*,jclass,jlong p,jint phase,jlong delay) {
  pod_js_stack_probe_request(reinterpret_cast<PodJsStackProbe*>(p),uint32_t(phase),uint32_t(std::clamp<jlong>(delay,0,UINT32_MAX)));
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCancelJsStack(JNIEnv*,jclass,jlong p) {
  pod_js_stack_probe_cancel(reinterpret_cast<PodJsStackProbe*>(p));
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCloseJsStackProbe(JNIEnv*,jclass,jlong p) {
  pod_js_stack_probe_close(reinterpret_cast<PodJsStackProbe*>(p));
}

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
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_podjs_runtime_PodBackgroundRun_nativeWait(
    JNIEnv* env,jclass,jlong handle,jint timeout) {
  const char* request=pod_background_wait(reinterpret_cast<PodBackgroundRun*>(handle),static_cast<uint32_t>(timeout));
  if(!request)return nullptr;
  auto length=static_cast<jsize>(std::strlen(request));auto result=env->NewByteArray(length);
  if(result)env->SetByteArrayRegion(result,0,length,reinterpret_cast<const jbyte*>(request));
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
  const double began = monoMs();
  const char* t = env->GetStringUTFChars(target, nullptr); const char* d = env->GetStringUTFChars(dataDir, nullptr);
  if (std::string(t) != "android-watch" && std::string(t) != "wearos-watch") { fail(env, "PodJS target mismatch"); return 0; }
  ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
const char* capabilities = "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\",\"host.lifecycle\",\"host.theme\",\"display.round\",\"input.back\",\"net.http\",\"data.fs\",\"data.sqlite\",\"text.layout\",\"text.raster\",\"text.environment\",\"input.text\",\"input.text.state\",\"input.time\",\"device.location\",\"device.battery\",\"data.secure\",\"crypto.basic\",\"data.files.chunks\",\"data.uri\",\"media.audio\",\"debug.acoustic\",\"media.tts\",\"media.tts.spectrum\",\"media.video\",\"media.video.sessions\",\"runtime.timer\",\"display.power\",\"system.toast\",\"data.images\",\"system.browser.challenge\",\"system.browser.auth\",\"system.browser.auth.status\",\"net.download\",\"net.rfcomm\",\"net.tcp\",\"net.stream\",\"net.websocket\",\"net.discovery\",\"net.ble.provision\",\"system.browser\",\"system.browser.siteData\",\"system.browser.availability\",\"system.browser.inApp\",\"system.browser.inApp.state\",\"system.browser.inApp.theme\",\"system.share\",\"background.scheduled\",\"notification.local\",\"companion.sync.state\",\"companion.sync.message\",\"companion.sync.file\",\"host.takeover\"]";
  // Positional fields: struct_size, target_id, host_abi, raster_density, ... raster_density must
  // match the CLI build density (packages/cli/src/cli.ts); host ABI lives in the macro, not here.
  constexpr uint32_t kRasterDensity = 2;
  PodRuntimeConfig config{sizeof(config), t, PODJS_RUNTIME_ABI_VERSION, kRasterDensity, (uint32_t)width, (uint32_t)height,
      density, POD_DISPLAY_ROUND, 0, 0, 0, 0, d, capabilities};
  const double runtimeBegan = monoMs();
  auto host = std::make_unique<Host>(); host->runtime = pod_runtime_create(&config);
  const double runtimeCreated = monoMs();
  if (!host->runtime) {
    ANativeWindow_release(window);
    env->ReleaseStringUTFChars(target, t); env->ReleaseStringUTFChars(dataDir, d);
    fail(env, pod_runtime_last_error()); return 0;
  }
  // Android supplies a coalesced host continuation even without visual frames.
  // Both limits apply between FIFO reactions; a single JS job remains atomic.
  pod_runtime_set_job_budget(host->runtime,64,2000);
  env->GetJavaVM(&host->vm);
  auto benchmarkClass=env->FindClass("dev/podjs/runtime/PodBenchmark");
  if(benchmarkClass){host->benchmarkClass=static_cast<jclass>(env->NewGlobalRef(benchmarkClass));host->benchmarkDispatch=env->GetStaticMethodID(benchmarkClass,"dispatch","(Ljava/lang/String;)J");env->DeleteLocalRef(benchmarkClass);}
  if(env->ExceptionCheck()||!host->benchmarkDispatch){if(env->ExceptionCheck())env->ExceptionClear();}
  else pod_runtime_set_benchmark(host->runtime,benchmarkDispatch,reinterpret_cast<uintptr_t>(host.get()));
  auto fontClass=env->FindClass("dev/podjs/runtime/PodSystemFontFallback");
  if(fontClass) {
    host->fontFallback=static_cast<jclass>(env->NewGlobalRef(fontClass));
    host->fontMetrics=env->GetStaticMethodID(fontClass,"metrics","(FII)[I");
    host->fontRaster=env->GetStaticMethodID(fontClass,"raster","(Ljava/lang/String;FIIIII)[B");env->DeleteLocalRef(fontClass);
  }
  if(env->ExceptionCheck()||!host->fontFallback||!host->fontRaster||!host->fontMetrics||
      pod_runtime_set_system_font_metrics(host->runtime,systemFontMetrics,reinterpret_cast<uintptr_t>(host.get()))!=0||
      pod_runtime_set_system_font_fallback(host->runtime,systemGlyph,reinterpret_cast<uintptr_t>(host.get()))!=0) {
    ANativeWindow_release(window);pod_runtime_destroy(host->runtime);
    env->ReleaseStringUTFChars(target,t);env->ReleaseStringUTFChars(dataDir,d);
    if(!env->ExceptionCheck())fail(env,"Cannot initialize Android system font fallback");return 0;
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
  const double rendererBegan = monoMs();
  host->renderer = std::make_unique<RenderThread>(window, width, height, rasterWidth, rasterHeight); ANativeWindow_release(window);
  const double rendererCreated = monoMs();
  // The normal GPU path never consumes the CPU RGBA fallback buffer.
  __android_log_print(ANDROID_LOG_INFO,"PodJSPerf",
      "create totalMs=%.3f windowConfigMs=%.3f runtimeMs=%.3f scrollConfigMs=%.3f vulkanMs=%.3f deferredRgbaBytes=%llu rendererReady=%d",
      rendererCreated-began,runtimeBegan-began,runtimeCreated-runtimeBegan,
      rendererBegan-runtimeCreated,rendererCreated-rendererBegan,
      static_cast<unsigned long long>(host->renderer->rasterBytes()),host->renderer->valid());
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
  Asset manifest=openAsset("pod.manifest.json"); if(!manifest)return;
  const auto* mb=static_cast<const char*>(AAsset_getBuffer(manifest.get()));
  if(!mb){fail(env,"PodJS cannot map asset pod.manifest.json");return;}
  const std::string json(mb,AAsset_getLength64(manifest.get()));
  manifest.reset();
  result=pod_runtime_validate_package(h->runtime,json.c_str());
  const double validated=monoMs();
  const char* evalMode="source";
  Asset bytecode(AAssetManager_open(manager,"main.qbc",AASSET_MODE_BUFFER),AAsset_close);
  double bytecodeAssetMs=0;
  bool evaluateSource=!result&&!bytecode;
  if(!result&&bytecode) {
    const auto* qb=static_cast<const uint8_t*>(AAsset_getBuffer(bytecode.get()));
    if(!qb){fail(env,"PodJS cannot map asset main.qbc");return;}
    bytecodeAssetMs=monoMs()-validated;
    result=pod_runtime_eval_bundle_bytecode(h->runtime,qb,AAsset_getLength64(bytecode.get()));
    if(!result)evalMode="bytecode";
    else if(result==-2){result=0;evaluateSource=true;}
  }
  // An older bundle can omit bytecode metadata or the qbc asset entirely.
  // Only pre-mount state rejection is safe to fall back from; guest failures
  // may already have installed surfaces or run side effects.
  if(evaluateSource) {
    Asset js=openAsset("main.js"); if(!js)return;
    const auto* jb=static_cast<const uint8_t*>(AAsset_getBuffer(js.get()));
    if(!jb){fail(env,"PodJS cannot map asset main.js");return;}
    result=pod_runtime_eval_bundle(h->runtime,jb,AAsset_getLength64(js.get()),"app:///main.js");
  }
  const double evaluated=monoMs();
  double firstFramePrepareMs=0;
  if(!result) {const double frameBegan=monoMs();renderFrame(h);firstFramePrepareMs=monoMs()-frameBegan;}
  PodPakStats stats{};stats.struct_size=sizeof(stats);pod_runtime_pak_stats(h->runtime,&stats);
  const char* pakIntegrity=stats.pak_integrity_mode==2?"entry-sha256":(stats.pak_integrity_mode==1?"legacy-fnv":"none");
  __android_log_print(ANDROID_LOG_INFO,"PodJSPerf","boot mmapMs=%.1f dirParseMs=%.3f pakHashMs=%.1f dirHashMs=%.3f eagerHashMs=%.3f entryHashMs=%.3f pakIntegrity=%s verifiedEntries=%u integrityFailures=%u validateMs=%.1f evalMs=%.1f evalMode=%s firstSlotLoadMs=%.3f loadedSlots=%u firstFramePrepareMs=%.1f",
      mapped-began,stats.directory_parse_micros/1000.0,stats.pak_hash_micros/1000.0,
      stats.directory_hash_micros/1000.0,stats.eager_hash_micros/1000.0,stats.integrity_hash_micros/1000.0,
      pakIntegrity,stats.verified_entries,stats.integrity_failures,validated-loaded,evaluated-validated,
      evalMode,stats.first_font_load_micros/1000.0,stats.loaded_font_slots,firstFramePrepareMs);
  if(!result&&std::strcmp(evalMode,"bytecode")==0) {
    PodEvalStats eval{};eval.struct_size=sizeof(eval);pod_runtime_eval_stats(h->runtime,&eval);
    __android_log_print(ANDROID_LOG_INFO,"PodJSPerf","bytecode bytes=%llu assetMs=%.3f hashMs=%.3f mountMs=%.3f readObjectMs=%.3f instantiateExecuteMs=%.3f drainJobsMs=%.3f finishMs=%.3f",
        static_cast<unsigned long long>(eval.bytecode_bytes),bytecodeAssetMs,eval.hash_micros/1000.0,
        eval.mount_micros/1000.0,eval.read_object_micros/1000.0,eval.execute_micros/1000.0,
        eval.drain_jobs_micros/1000.0,eval.finish_micros/1000.0);
  }
  if(result)fail(env,pod_runtime_last_error());
  else __android_log_print(ANDROID_LOG_INFO,"PodJS","%s",pod_runtime_receipt(h->runtime));
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeRendererReady(JNIEnv*,jclass,jlong p) {
  auto* h=reinterpret_cast<Host*>(p);
  return h && h->renderer && h->renderer->valid() ? JNI_TRUE : JNI_FALSE;
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
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePumpJobs(JNIEnv*,jclass,jlong p) {
  auto* h=reinterpret_cast<Host*>(p);if(!h)return;
  if(pod_runtime_pump_jobs(h->runtime)<0)__android_log_print(ANDROID_LOG_ERROR,"PodJS","Job pump failed: %s",pod_runtime_last_error());
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeNeedsFrame(JNIEnv*,jclass,jlong p) {
  auto* h=reinterpret_cast<Host*>(p);
  return h&&(pod_runtime_needs_frame(h->runtime)||h->touches.needsFrame()||h->renderCheckpointFrames>0)?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeHasPendingJobs(JNIEnv*,jclass,jlong p) {
  auto* h=reinterpret_cast<Host*>(p);return h&&pod_runtime_has_pending_jobs(h->runtime)?JNI_TRUE:JNI_FALSE;
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
  if (h->hostDiagnostics.enabled()) return JNI_FALSE;
  if (!h->renderer->beginRendererTiming(static_cast<uint32_t>(capacity))) return JNI_FALSE;
  if (!h->hostDiagnostics.begin(static_cast<uint32_t>(capacity))) { h->renderer->endRendererTiming(); return JNI_FALSE; }
  // Sequence remains monotonic across diagnostic sessions and queued frames.
  return JNI_TRUE;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeEndRendererTiming(JNIEnv* env,jclass,jlong p) {
  auto* h = reinterpret_cast<Host*>(p);
  if (!h || !h->renderer) return nullptr;
  // Detach first. Every subsequent return, including either allocation failure,
  // leaves native recording disabled and releases the former fixed buffer.
  auto hostTrace = h->hostDiagnostics.end();
  auto trace = podjs_gpu::RendererTimingTrace::merge(std::move(hostTrace),h->renderer->endRendererTiming());
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
static void renderFrame(Host* h, int64_t frameTimeNanos) {
  if (!h) return;
  const uint64_t sequence = ++h->diagnosticFrameSequence;
  auto hostTiming = h->hostDiagnostics.beginHostFrame(sequence,0);
  const double start = monoMs();
  auto completions = h->renderer->poll(sequence);
  const auto& touches = h->touches.nextFrame();
  PodInputFrame in{sizeof(in),0,0,touches.data(),(uint32_t)touches.size(),h->rotary,0}; h->rotary=0;

  auto guestTiming = h->hostDiagnostics.scope(podjs_gpu::RendererTimingStage::Guest);
  const int result = pod_runtime_frame(h->runtime,&in);

  if (result != 0 && !h->frameFailed) __android_log_print(ANDROID_LOG_ERROR,"PodJS","Frame failed: %s",pod_runtime_last_error());
  guestTiming.finish(result==0,result);
  h->frameFailed = result != 0;
  bool queued=false;
  double snapshotMs = 0, submitMs = 0, gpuDecodeMs = 0,
      uploadFenceMs = 0, gpuUploadMs = 0, frameFenceMs = 0, acquireMs = 0,
      gpuRecordMs = 0, queueSubmitMs = 0, presentWaitMs = 0;
  uint64_t gpuUploadBytes = 0;
  uint32_t gpuUploadCount = 0, frameSlot = 0, inFlightFrames = 0;
  bool waitedForSlot = false;
  if (result == 0) {
    PodDrawList list{};
    const double snapshotBegan = monoMs();

    auto snapshotTiming = h->hostDiagnostics.scope(podjs_gpu::RendererTimingStage::Snapshot);
    const int snapshotResult = pod_runtime_snapshot(h->runtime,&list);
    snapshotMs = monoMs()-snapshotBegan;
    h->lastSnapshotHash=list.content_hash;h->lastSnapshotMonoMs=monoMs();

    if (!snapshotResult && (list.changed || h->renderer->needsRedraw(list.content_hash))) {
      const double pinBegan = monoMs();
      RenderThread::Snapshot snapshot(pod_runtime_pin_snapshot(h->runtime));
      snapshotMs += monoMs()-pinBegan;
      const double handoffBegan = monoMs();
      queued=h->renderer->handoff(std::move(snapshot), sequence);
      if(queued && list.word_count>0)h->contentSubmitted=true;
      submitMs = monoMs()-handoffBegan;

    }
    snapshotTiming.finish(snapshotResult==0,snapshotResult);
    if(h->renderCheckpointFrames>0 && monoMs()<=h->renderCheckpointUntilMs){
      --h->renderCheckpointFrames;
      __android_log_print(ANDROID_LOG_INFO,"PodRenderTiming",
          "stage=snapshot checkpoint=%llu frame=%llu hash=%llu monoMs=%.3f changed=%d queued=%d snapshotMs=%.3f guestToSnapshotMs=%.3f",
          (unsigned long long)h->renderCheckpointId,(unsigned long long)sequence,(unsigned long long)list.content_hash,monoMs(),list.changed,queued,snapshotMs,monoMs()-start);
    }
  }
  for (const auto& done : completions.completed) {
    const auto& p = done.perf;
    podjs_perf::FrameSample draw{.gpuDecodeMs=p.decodeMs, .uploadFenceMs=p.uploadFenceMs,
      .textureUploadMs=p.uploadMs, .frameFenceMs=p.frameFenceMs, .acquireMs=p.acquireMs,
      .gpuRecordMs=p.recordMs, .queueSubmitMs=p.queueSubmitMs, .presentWaitMs=p.presentWaitMs,
      .uploadBytes=p.uploadBytes, .uploadCount=p.uploadCount, .inFlightFrames=p.inFlightFrames,
      .waitedForSlot=p.waitedForSlot, .gpuSubmitted=done.gpu, .cpuSubmitted=done.cpu};
    h->framePerf.addDraw(draw);
    gpuDecodeMs=p.decodeMs; uploadFenceMs=p.uploadFenceMs; gpuUploadMs=p.uploadMs;
    frameFenceMs=p.frameFenceMs; acquireMs=p.acquireMs; gpuRecordMs=p.recordMs;
    queueSubmitMs=p.queueSubmitMs; presentWaitMs=p.presentWaitMs; gpuUploadBytes=p.uploadBytes;
    gpuUploadCount=p.uploadCount; frameSlot=p.slotIndex; inFlightFrames=p.inFlightFrames; waitedForSlot=p.waitedForSlot;
  }
  const double now=monoMs();
  PodRuntimePerfStats perf{};
  perf.struct_size=sizeof(perf);
  if(pod_runtime_perf_stats(h->runtime,&perf)==0 &&
      (now-start>=50 || perf.resource_generation!=h->lastResourceGeneration)) {
    __android_log_print(
        ANDROID_LOG_INFO, "PodJSPerf",
        "frame seq=%llu totalMs=%.2f jsTurnMs=%.2f layoutMs=%.2f "
        "drawListMs=%.2f gpuPathMs=%.2f gpuDecodeMs=%.2f uploadFenceMs=%.2f "
        "textureUploadMs=%.2f frameFenceMs=%.2f acquireMs=%.2f "
        "gpuRecordMs=%.2f queueSubmitMs=%.2f presentWaitMs=%.2f gpuUploads=%u "
        "gpuUploadBytes=%llu fontSlot=%d fontLoadMs=%.2f fontAsync=%d "
        "loadedFonts=%u pendingFonts=%u imageLoadMs=%.2f loadedImages=%u "
        "resourceGen=%llu frameSlot=%u inFlight=%u slotReuse=%d "
        "acquireWaitMs=%.2f handoffMs=%.2f queueReplaced=%llu rendererLagFrames=%llu "
        "rendererSeq=%llu hostSeq=%llu",
        static_cast<unsigned long long>(perf.frame_number), now - start,
        perf.guest_micros / 1000.0, perf.surface_tick_micros / 1000.0,
        snapshotMs, submitMs, gpuDecodeMs, uploadFenceMs, gpuUploadMs,
        frameFenceMs, acquireMs, gpuRecordMs, queueSubmitMs, presentWaitMs,
        gpuUploadCount, static_cast<unsigned long long>(gpuUploadBytes),
        perf.last_font_slot, perf.last_font_load_micros / 1000.0,
        perf.last_font_async, perf.loaded_font_slots, perf.pending_font_slots,
        perf.last_image_load_micros / 1000.0, perf.loaded_images,
        static_cast<unsigned long long>(perf.resource_generation), frameSlot,
        inFlightFrames, waitedForSlot, acquireMs,submitMs,(unsigned long long)completions.replaced,
        (unsigned long long)completions.lagFrames,
        (unsigned long long)(completions.completed.empty()?0:completions.completed.back().sequence),
        (unsigned long long)sequence);
    h->lastResourceGeneration=perf.resource_generation;
  }
  podjs_perf::FrameSample sample{.nativeTotalMs = now - start,
                                 .jsTurnMs = perf.guest_micros / 1000.0,
                                 .layoutMs = perf.surface_tick_micros / 1000.0,
                                 .drawListMs = snapshotMs,
                                 .gpuDecodeMs = gpuDecodeMs,
                                 .uploadFenceMs = uploadFenceMs,
                                 .textureUploadMs = gpuUploadMs,
                                 .frameFenceMs = frameFenceMs,
                                 .acquireMs = acquireMs,
                                 .gpuRecordMs = gpuRecordMs,
                                 .queueSubmitMs = queueSubmitMs,
                                 .presentWaitMs = presentWaitMs,
                                 .uploadBytes = gpuUploadBytes,
                                 .uploadCount = gpuUploadCount,
                                 .inFlightFrames = inFlightFrames,
                                 .waitedForSlot = waitedForSlot,
                                 .handoffMs = submitMs,
                                 .queueReplaced = completions.replaced,
                                 .rendererLagFrames = completions.lagFrames};
  podjs_perf::WindowSummary window{};
  if (h->framePerf.add(now,frameTimeNanos,sample,&window)) {
    PodGlyphCacheStats glyph{};
    if(pod_runtime_glyph_cache_stats(h->runtime,&glyph)==0) {
      __android_log_print(ANDROID_LOG_INFO,"PodJSPerf",
          "glyphCache hits=%llu misses=%llu materializeMs=%.3f residentBytes=%llu sourceBytes=%llu seedBytes=%llu cells=%llu pageBytes=%llu pages=%llu",
          (unsigned long long)glyph.hits,(unsigned long long)glyph.misses,glyph.raster_micros/1000.0,
          (unsigned long long)glyph.resident_bytes,(unsigned long long)glyph.source_bytes,(unsigned long long)glyph.seed_bytes,(unsigned long long)glyph.cells,
          (unsigned long long)(h->renderer?h->renderer->glyphPageBytes():0),
          (unsigned long long)(h->renderer?h->renderer->glyphPageCount():0));
    }
    const auto values=[](const podjs_perf::Distribution& value) {
      return std::array<double,3>{value.p50,value.p95,value.max};
    };
    const auto total=values(window.nativeTotal),js=values(window.jsTurn),
        layout=values(window.layout),drawList=values(window.drawList),
        decode=values(window.gpuDecode),uploadFence=values(window.uploadFence),
        upload=values(window.textureUpload),frameFence=values(window.frameFence),
        acquire=values(window.acquire),record=values(window.gpuRecord),
        submit=values(window.queueSubmit),present=values(window.presentWait);
    __android_log_print(
        ANDROID_LOG_INFO, "PodJSPerf",
        "frameWindow windowMs=%.1f targetHz=60 ticks=%u draws=%u gpuDraws=%u "
        "cpuDraws=%u dropped=%u overBudget=%u uploadFrames=%u uploadBytes=%llu "
        "p50/p95/maxMs nativeTotal=%.2f/%.2f/%.2f jsTurn=%.2f/%.2f/%.2f "
        "layout=%.2f/%.2f/%.2f drawList=%.2f/%.2f/%.2f "
        "gpuDecode=%.2f/%.2f/%.2f uploadFence=%.2f/%.2f/%.2f "
        "textureUpload=%.2f/%.2f/%.2f frameFence=%.2f/%.2f/%.2f "
        "acquire=%.2f/%.2f/%.2f gpuRecord=%.2f/%.2f/%.2f "
        "queueSubmit=%.2f/%.2f/%.2f presentWait=%.2f/%.2f/%.2f slots=2 "
        "slotReuses=%u maxInFlight=%u acquireWait=%.2f/%.2f/%.2f "
        "nativeOver33=%u jsOver33=%u cadenceOver33=%u "
        "handoffMs=%.2f/%.2f/%.2f queueReplaced=%llu rendererLagFrames=%llu",
        window.windowMs, window.ticks, window.draws, window.gpuDraws,
        window.cpuDraws, window.dropped, window.overBudget, window.uploadFrames,
        static_cast<unsigned long long>(window.uploadBytes), total[0], total[1],
        total[2], js[0], js[1], js[2], layout[0], layout[1], layout[2],
        drawList[0], drawList[1], drawList[2], decode[0], decode[1], decode[2],
        uploadFence[0], uploadFence[1], uploadFence[2], upload[0], upload[1],
        upload[2], frameFence[0], frameFence[1], frameFence[2], acquire[0],
        acquire[1], acquire[2], record[0], record[1], record[2], submit[0],
        submit[1], submit[2], present[0], present[1], present[2],
        window.slotReuses, window.maxInFlight, acquire[0], acquire[1],
        acquire[2], window.nativeOver33, window.jsOver33,
        window.cadenceOver33,window.handoff.p50,window.handoff.p95,window.handoff.max,
        (unsigned long long)window.queueReplaced,(unsigned long long)window.rendererLagFrames);
  }

  hostTiming.setFlags(queued ? int64_t{1}<<19 : 0);
  hostTiming.finish(result==0,result);
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeHasContent(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);return h&&h->contentSubmitted?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFrame(JNIEnv*,jclass,jlong p,jlong frameTimeNanos) {
  renderFrame(reinterpret_cast<Host*>(p),frameTimeNanos);
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeRenderCheckpoint(JNIEnv*,jclass,jlong p,jlong id) {
  auto*h=reinterpret_cast<Host*>(p);if(!h||id<1)return;
  h->renderCheckpointId=id;h->renderCheckpointFrames=8;h->renderCheckpointUntilMs=monoMs()+2000;
  __android_log_print(ANDROID_LOG_INFO,"PodRenderTiming","stage=receiptSnapshot checkpoint=%llu frame=%llu hash=%llu snapshotMonoMs=%.3f monoMs=%.3f",
      (unsigned long long)id,(unsigned long long)h->diagnosticFrameSequence,(unsigned long long)h->lastSnapshotHash,h->lastSnapshotMonoMs,monoMs());
  h->renderer->traceCheckpoint(id);
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePollEffect(JNIEnv* env,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return nullptr;const char* line=pod_runtime_poll_effect(h->runtime);return line?env->NewStringUTF(line):nullptr;}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePollNet(JNIEnv* env,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return nullptr;const char* line=pod_runtime_poll_net_command(h->runtime);return line?env->NewStringUTF(line):nullptr;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCompleteHttp(JNIEnv* env,jclass,jlong p,jint handle,jint status,jstring url,jstring headers,jbyteArray body){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*u=env->GetStringUTFChars(url,nullptr);const char*hs=env->GetStringUTFChars(headers,nullptr);jsize n=env->GetArrayLength(body);std::vector<uint8_t>b(n);env->GetByteArrayRegion(body,0,n,reinterpret_cast<jbyte*>(b.data()));pod_runtime_complete_http(h->runtime,handle,status,u,hs,b.data(),b.size());env->ReleaseStringUTFChars(url,u);env->ReleaseStringUTFChars(headers,hs);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFailHttp(JNIEnv* env,jclass,jlong p,jint handle,jstring code,jstring message){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*c=env->GetStringUTFChars(code,nullptr);const char*m=env->GetStringUTFChars(message,nullptr);pod_runtime_fail_http(h->runtime,handle,c,m);env->ReleaseStringUTFChars(code,c);env->ReleaseStringUTFChars(message,m);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeLifecycle(JNIEnv*,jclass,jlong p,jint s){auto*h=reinterpret_cast<Host*>(p);if(h){h->renderer->setPaused(s!=0);if(s==2){h->touches.clear();h->rotary=0;h->framePerf.resetCadence();}pod_runtime_set_lifecycle(h->runtime,s);}}
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
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeDestroy(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(h){h->renderer.reset();pod_runtime_destroy(h->runtime);delete h;}}

extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePostEvent(JNIEnv* env,jclass,jlong p,jbyteArray event){
  auto*h=reinterpret_cast<Host*>(p);if(!h)return;
  jsize size=env->GetArrayLength(event);std::vector<char> data(size+1,0);
  env->GetByteArrayRegion(event,0,size,reinterpret_cast<jbyte*>(data.data()));
  pod_runtime_post_event(h->runtime,data.data());
}

struct PakVmaStats {
  bool found{}, exact{};
  uint64_t vmaBytes{}, rssBytes{}, pssBytes{};
};
static PakVmaStats pakVmaStats(const MappedAsset &pak) {
  PakVmaStats result{};
  if (pak.base == MAP_FAILED || !pak.mappedLength) return result;
  std::ifstream smaps("/proc/self/smaps");
  if (!smaps) return result;
  const auto begin = reinterpret_cast<uintptr_t>(pak.base);
  const auto page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
  if (!page || page > (1u << 20)) return result;
  const auto end = begin + ((pak.mappedLength + page - 1) / page) * page;
  bool selected = false;
  std::string line;
  while (std::getline(smaps, line)) {
    unsigned long from{}, to{}, kb{};
    if (std::sscanf(line.c_str(), "%lx-%lx", &from, &to) == 2) {
      selected = from < end && to > begin;
      if (selected) {
        result.found = true;
        result.vmaBytes += to - from;
        result.exact = from == begin && to == end;
      }
    } else if (selected && std::sscanf(line.c_str(), "Rss: %lu kB", &kb) == 1) result.rssBytes += kb * 1024;
    else if (selected && std::sscanf(line.c_str(), "Pss: %lu kB", &kb) == 1) result.pssBytes += kb * 1024;
  }
  // If multiple/merged VMAs overlap, totals belong to the entire intersecting
  // VMAs. Expose that boundary rather than presenting a fabricated pak-only RSS.
  result.exact = result.exact && result.vmaBytes == end - begin;
  return result;
}

// Called on the same main-thread host lane as frames/services. Java checks the
// debug flag before entering JNI; this function never polls a fence or runs GC.
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeMemoryStats(
    JNIEnv* env, jclass, jlong p) {
  auto* h = reinterpret_cast<Host*>(p);
  if (!h || !h->runtime) return nullptr;
  const char* runtime = pod_runtime_memory_stats(h->runtime);
  if (!runtime) return nullptr;
  std::ostringstream out;
  out << "{\"native\":" << runtime << ",\"renderer\":";
  if (!h->renderer) out << "null";
  else {
    const auto s = h->renderer->memoryStats();
    out << "{";
#define MEM_FIELD(name) out << "\"" #name "\":" << s.name << ",";
    MEM_FIELD(liveTextures) MEM_FIELD(liveTextureBytes) MEM_FIELD(liveTextureAllocatedBytes)
    MEM_FIELD(retiredTextures) MEM_FIELD(retiredTextureBytes) MEM_FIELD(retiredTextureAllocatedBytes)
    MEM_FIELD(stagingCapacityBytes) MEM_FIELD(stagingAllocatedBytes) MEM_FIELD(clipAllocatedBytes)
    MEM_FIELD(rasterAllocatedBytes) MEM_FIELD(slots) MEM_FIELD(inFlightSlots) MEM_FIELD(slotImageRefs)
    MEM_FIELD(slotSnapshotCapacityBytes) MEM_FIELD(cpuImageBuffers) MEM_FIELD(cpuImageBytes)
    MEM_FIELD(decodeTextureSlots) MEM_FIELD(glyphPages)
#undef MEM_FIELD
    out << "\"glyphPageBytes\":" << s.glyphPageBytes << "}";
  }
  out << ",\"pakMapping\":{\"bytes\":" << h->pak.length
      << ",\"mappedBytes\":" << h->pak.mappedLength << ",\"mincoreBackingResidentBytes\":";
  const long page = sysconf(_SC_PAGESIZE);
  if (h->pak.base != MAP_FAILED && page > 0) {
    const size_t pages = (h->pak.mappedLength + page - 1) / page;
    std::vector<unsigned char> residency(pages);
    if (mincore(h->pak.base, h->pak.mappedLength, residency.data()) == 0) {
      size_t resident = 0;
      for (auto value : residency) if (value & 1) resident += page;
      out << resident;
    } else out << "null";
  } else out << "null";
  const auto vma = pakVmaStats(h->pak);
  out << ",\"vmaBytes\":";
  if (vma.found) out << vma.vmaBytes; else out << "null";
  out << ",\"vmaRssBytes\":";
  if (vma.found) out << vma.rssBytes; else out << "null";
  out << ",\"vmaPssBytes\":";
  if (vma.found) out << vma.pssBytes; else out << "null";
  out << ",\"exactVma\":" << (vma.exact ? "true" : "false");
  out << "},\"hostBuffers\":{\"framebufferCapacityBytes\":" << h->renderer->fallbackCapacityBytes()
      << ",\"mediaPixelCapacityBytes\":" << h->mediaPixels.capacity()
      << ",\"mediaArgbCapacityBytes\":" << h->mediaArgb.capacity() * sizeof(jint) << "}}";
  return env->NewStringUTF(out.str().c_str());
}
