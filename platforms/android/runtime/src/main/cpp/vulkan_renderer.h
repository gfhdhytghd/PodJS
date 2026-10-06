#pragma once

#include <android/native_window.h>
#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "gpu_draw_list.h"
#include "renderer_diagnostics.h"

class VulkanRenderer {
 public:
  static constexpr uint32_t kRasterScale = 2;
  static constexpr uint32_t kFramesInFlight = GpuDecodeCache::kFramesInFlight;

  VulkanRenderer(ANativeWindow* window, uint32_t width, uint32_t height,
                 uint32_t rasterWidth, uint32_t rasterHeight);
  ~VulkanRenderer();
  VulkanRenderer(const VulkanRenderer&) = delete;
  VulkanRenderer& operator=(const VulkanRenderer&) = delete;

  bool valid() const { return valid_; }
  bool needsRedraw(uint64_t contentHash) const {
    return lastHash_ == 0 || contentHash != lastHash_;
  }
  size_t rasterBytes() const { return size_t{rasterWidth_} * rasterHeight_ * 4; }
  void replaceWindow(ANativeWindow* window, uint32_t width, uint32_t height);

  // CPU-rendered compatibility path.
  bool submitRgba(const uint8_t* pixels, size_t byteLength, uint64_t contentHash);

  // Canonical path: decode and rasterize the DrawList directly on the GPU.
  bool submitDrawList(PodRuntime* runtime, const PodDrawList& list);

  struct SubmitPerf {
    double decodeMs{};
    double uploadFenceMs{};
    double uploadMs{};
    double frameFenceMs{};
    double acquireMs{};
    double recordMs{};
    double queueSubmitMs{};
    double presentWaitMs{};
    uint64_t uploadBytes{};
    uint32_t uploadCount{};
    uint32_t slotIndex{};
    uint32_t inFlightFrames{};
    bool waitedForSlot{};
  };
  const SubmitPerf& lastSubmitPerf() const { return lastSubmitPerf_; }

  // Private host diagnostics; disabled by default and never exposed to guests.
  using DiagnosticsScope = podjs_gpu::RendererTimingRecorder::Scope;
  bool beginRendererTiming(uint32_t capacity) { return diagnostics_.begin(capacity); }
  std::unique_ptr<podjs_gpu::RendererTimingTrace> endRendererTiming() {
    return diagnostics_.end();
  }
  DiagnosticsScope beginHostFrame(uint64_t hostFrameSequence) {
    return diagnostics_.beginHostFrame(hostFrameSequence, frameSerial_);
  }
  DiagnosticsScope diagnosticScope(podjs_gpu::RendererTimingStage stage,
                                   podjs_gpu::RendererTimingFields fields = {}) {
    diagnostics_.setRendererFrameSerial(frameSerial_);
    return diagnostics_.scope(stage, fields);
  }

  struct MemoryStats {
    uint64_t liveTextures{}, liveTextureBytes{}, liveTextureAllocatedBytes{};
    uint64_t retiredTextures{}, retiredTextureBytes{}, retiredTextureAllocatedBytes{};
    uint64_t stagingCapacityBytes{}, stagingAllocatedBytes{}, clipAllocatedBytes{}, rasterAllocatedBytes{};
    uint64_t slots{}, inFlightSlots{}, slotImageRefs{}, slotSnapshotCapacityBytes{};
    uint64_t cpuImageBuffers{}, cpuImageBytes{}, decodeTextureSlots{}, glyphPages{}, glyphPageBytes{};
  };
  // On demand only; no allocation counters or scans in the render path.
  MemoryStats memoryStats() const;

  size_t glyphPageBytes() const {return decodeCache_.glyphPageBytes();}
  size_t glyphPageCount() const {return decodeCache_.glyphPageCount();}

 private:
  struct TextureResource {
    uint64_t key{};
    uint64_t revision{};
    uint64_t lastUsedFrame{};
    uint32_t slot{};
    uint32_t width{};
    uint32_t height{};
    bool linear{};
    bool coverage{};
    bool ready{};
    VkImage image{};
    VkDeviceMemory memory{};
    VkImageView view{};
    std::array<VkDescriptorSet, kFramesInFlight> descriptors{};
  };
  struct FrameSlot {
    VkCommandBuffer command{};
    VkSemaphore acquired{};
    VkFence fence{};
    bool pending{};
    bool acquiredImage{};
    VkBuffer stagingBuffer{};
    VkDeviceMemory stagingMemory{};
    VkDeviceSize stagingCapacity{};
    void *stagingMapped{};
    VkBuffer clipBuffer{};
    VkDeviceMemory clipMemory{};
    void *clipMapped{};
    VkImage rasterImage{};
    VkDeviceMemory rasterMemory{};
    VkImageView rasterView{};
    VkFramebuffer framebuffer{};
    // Immutable snapshots and Vulkan handles live until this slot's fence.
    std::vector<GpuImage> images;
    std::unordered_set<VkImage> textures;
    std::vector<uint64_t> uploadKeys;
  };
  FrameSlot &frame() { return frames_[frameIndex_]; }
  bool createFrameUploadResources(FrameSlot &slot);
  bool createFrameClipResources(FrameSlot &slot);
  bool ensureStagingCapacity(FrameSlot &slot, VkDeviceSize required);
  bool textureInFlight(const TextureResource &texture) const;
  void retireTexture(TextureResource *texture);
  void collectRetiredTextures();
  void releaseFrame(uint32_t index);
  void abortFrame(uint32_t index);
  bool resetFrameSync();

  struct PendingTextureUpload {
    uint64_t key{};
    VkDeviceSize offset{};
  };

  bool createDevice();
  bool createUploadResources();
  bool createClipResources();
  bool createGraphicsResources();
  bool createSurfaceAndSwapchain();
  bool ensureStagingCapacity(VkDeviceSize required);
  bool waitForFrameFence();
  bool prepareImageUploads(const std::vector<GpuImage>& images,
                           std::vector<PendingTextureUpload>* pendingUploads);
  void recordImageUploads(VkCommandBuffer command,
                          const std::vector<PendingTextureUpload>& uploads);
  bool createTextureResource(const GpuImage& image, uint64_t key,
                             TextureResource* out);
  bool acquireFrame(uint32_t *imageIndex);
  bool submitAndPresent(uint32_t imageIndex, VkPipelineStageFlags waitStage);
  bool appendSwapchainBlit(VkCommandBuffer command, uint32_t imageIndex);
  bool rebuildSurfaceAfterError(const char* operation, VkResult result);
  bool gpuFailureOnce(const std::string& reason);
  uint64_t imageKey(const GpuImage& image) const;
  TextureResource* textureForPrimitive(const GpuPrimitive& primitive);
  uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) const;
  void destroyTexture(TextureResource* texture);
  void destroyGraphicsResources();
  void destroyUploadResources();
  void destroyClipResources();
  void destroySwapchain();
  void destroySurface();

  static constexpr uint32_t kDescriptorCapacity = 4096;
  static constexpr uint32_t kRoundedClipCapacity = 65536;
  static constexpr uint32_t kTextureCacheCapacity = 1024;
  static constexpr size_t kTextureCacheByteCapacity = size_t{32} * 1024 * 1024;
  static constexpr uint64_t kWhiteTextureKey = UINT64_MAX;

  ANativeWindow* window_ = nullptr;
  uint32_t width_ = 0;
  uint32_t height_ = 0;
  uint32_t rasterWidth_ = 0;
  uint32_t rasterHeight_ = 0;
  uint64_t lastHash_ = 0;
  uint64_t frameSerial_ = 0;
  bool valid_ = false;
  bool supportsLinearBlit_ = false;

  VkInstance instance_{};
  VkPhysicalDevice physical_{};
  VkDevice device_{};
  VkQueue queue_{};
  uint32_t queueFamily_ = UINT32_MAX;
  VkSurfaceKHR surface_{};
  VkSwapchainKHR swapchain_{};
  VkFormat format_ = VK_FORMAT_UNDEFINED;
  VkExtent2D extent_{};
  VkCommandPool commandPool_{};
  std::vector<VkImage> images_;
  std::vector<VkSemaphore> renderedPerImage_;
  std::array<FrameSlot, kFramesInFlight> frames_{};
  uint32_t frameIndex_{};
  std::vector<TextureResource> retiredTextures_;
  VkRenderPass renderPass_{};
  VkDescriptorSetLayout descriptorLayout_{};
  VkDescriptorPool descriptorPool_{};
  VkSampler nearestSampler_{};
  VkSampler linearSampler_{};
  VkPipelineLayout pipelineLayout_{};
  VkPipeline pipeline_{};

  GpuDecodeCache decodeCache_;
  std::unordered_map<uint64_t, TextureResource> textureCache_;
  std::unordered_set<std::string> loggedGpuFailures_;
  podjs_gpu::RendererTimingRecorder diagnostics_;
  SubmitPerf lastSubmitPerf_{};
};
