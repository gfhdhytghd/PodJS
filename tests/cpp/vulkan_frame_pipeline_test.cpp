// Host-only Vulkan model: GPU work stays pending until its own fence is waited.
// It checks live descriptor/image handles and byte-for-byte mapped buffer
// snapshots. This is synchronization regression coverage, not a GPU/WSI
// performance benchmark.
#include "vulkan_renderer.h"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <map>
#include <set>
#include <utility>
#include <vulkan/vulkan_android.h>

namespace mock {
uint64_t counter = 1;
template <class T> T make() { return reinterpret_cast<T>(++counter); }
template <class T> uint64_t id(T handle) {
  return reinterpret_cast<uint64_t>(handle);
}
struct Command {
  std::set<uint64_t> resources;
  std::set<VkBuffer> buffers;
};
struct Fence {
  bool signaled{}, pending{};
  VkCommandBuffer command{};
  std::set<uint64_t> resources;
  std::map<VkDeviceMemory, std::vector<uint8_t>> snapshots;
};
struct Descriptor {
  VkImage image{};
  VkBuffer buffer{};
};
struct Submission {
  VkFence fence;
  VkCommandBuffer command;
  VkSemaphore acquired;
};
std::map<VkFence, Fence> fences;
std::map<VkSemaphore, bool> semaphores;
std::map<VkCommandBuffer, Command> commands;
std::map<VkDeviceMemory, std::vector<uint8_t>> memory;
std::map<VkBuffer, VkDeviceMemory> buffers;
std::map<VkImage, VkDeviceMemory> imageMemory;
std::map<VkImageView, VkImage> views;
std::map<VkFramebuffer, VkImage> framebuffers;
std::map<VkDescriptorSet, Descriptor> descriptors;
std::map<uint64_t, uint64_t> sizes;
std::vector<VkImage> swapImages;
std::map<uint32_t, VkSemaphore> presentSemaphores;
std::vector<VkFence> waits;
std::vector<VkSemaphore> acquired;
std::vector<Submission> submissions;
uint32_t nextImage{}, rebuilds{}, drains{}, capMax{};
bool failBegin{}, failEnd{}, failSubmit{}, failDrain{};
VkResult acquireResult = VK_SUCCESS, presentResult = VK_SUCCESS;
void assertUnused(uint64_t resource) {
  for (auto &[f, state] : fences)
    assert(!state.pending || !state.resources.count(resource));
}
void verifyPending() {
  for (auto &[f, state] : fences)
    if (state.pending)
      for (auto &[allocation, bytes] : state.snapshots)
        assert(memory.at(allocation) == bytes);
}
} // namespace mock
using namespace mock;

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkAcquireNextImageKHR(
    VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout,
    VkSemaphore semaphore, VkFence fence, uint32_t *pImageIndex) {
  verifyPending();
  if (acquireResult != VK_SUCCESS) {
    auto result = std::exchange(acquireResult, VK_SUCCESS);
    return result;
  }
  *pImageIndex = nextImage++ % swapImages.size();
  if (presentSemaphores.count(*pImageIndex)) {
    semaphores.at(presentSemaphores.at(*pImageIndex)) = false;
    presentSemaphores.erase(*pImageIndex);
  }
  assert(!semaphores.at(semaphore));
  semaphores.at(semaphore) = true;
  acquired.push_back(semaphore);
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkAllocateCommandBuffers(
    VkDevice device, const VkCommandBufferAllocateInfo *pAllocateInfo,
    VkCommandBuffer *pCommandBuffers) {
  for (uint32_t i = 0; i < pAllocateInfo->commandBufferCount; ++i) {
    pCommandBuffers[i] = make<VkCommandBuffer>();
    commands[pCommandBuffers[i]] = {};
  }
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkAllocateDescriptorSets(
    VkDevice device, const VkDescriptorSetAllocateInfo *pAllocateInfo,
    VkDescriptorSet *pDescriptorSets) {
  for (uint32_t i = 0; i < pAllocateInfo->descriptorSetCount; ++i)
    pDescriptorSets[i] = make<VkDescriptorSet>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkAllocateMemory(
    VkDevice device, const VkMemoryAllocateInfo *pAllocateInfo,
    const VkAllocationCallbacks *pAllocator, VkDeviceMemory *pMemory) {
  *pMemory = make<VkDeviceMemory>();
  memory[*pMemory].resize(pAllocateInfo->allocationSize);
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkBeginCommandBuffer(
    VkCommandBuffer commandBuffer, const VkCommandBufferBeginInfo *pBeginInfo) {
  if (failBegin) {
    failBegin = false;
    return VK_ERROR_OUT_OF_HOST_MEMORY;
  }
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkBindBufferMemory(VkDevice device, VkBuffer buffer, VkDeviceMemory memory,
                   VkDeviceSize memoryOffset) {
  buffers[buffer] = memory;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkBindImageMemory(VkDevice device, VkImage image, VkDeviceMemory memory,
                  VkDeviceSize memoryOffset) {
  imageMemory[image] = memory;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkCmdBeginRenderPass(
    VkCommandBuffer commandBuffer,
    const VkRenderPassBeginInfo *pRenderPassBegin, VkSubpassContents contents) {
  commands.at(commandBuffer)
      .resources.insert(id(framebuffers.at(pRenderPassBegin->framebuffer)));
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkCmdBindDescriptorSets(
    VkCommandBuffer commandBuffer, VkPipelineBindPoint pipelineBindPoint,
    VkPipelineLayout layout, uint32_t firstSet, uint32_t descriptorSetCount,
    const VkDescriptorSet *pDescriptorSets, uint32_t dynamicOffsetCount,
    const uint32_t *pDynamicOffsets) {
  for (uint32_t i = 0; i < descriptorSetCount; ++i) {
    auto descriptor = descriptors.at(pDescriptorSets[i]);
    commands.at(commandBuffer).resources.insert(id(pDescriptorSets[i]));
    commands.at(commandBuffer).resources.insert(id(descriptor.image));
    commands.at(commandBuffer).resources.insert(id(descriptor.buffer));
    commands.at(commandBuffer).buffers.insert(descriptor.buffer);
  }
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkCmdBindPipeline(VkCommandBuffer commandBuffer,
                  VkPipelineBindPoint pipelineBindPoint, VkPipeline pipeline) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkCmdBlitImage(VkCommandBuffer commandBuffer, VkImage srcImage,
               VkImageLayout srcImageLayout, VkImage dstImage,
               VkImageLayout dstImageLayout, uint32_t regionCount,
               const VkImageBlit *pRegions, VkFilter filter) {
  commands.at(commandBuffer).resources.insert(id(srcImage));
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkCmdCopyBufferToImage(
    VkCommandBuffer commandBuffer, VkBuffer srcBuffer, VkImage dstImage,
    VkImageLayout dstImageLayout, uint32_t regionCount,
    const VkBufferImageCopy *pRegions) {
  commands.at(commandBuffer).resources.insert(id(srcBuffer));
  commands.at(commandBuffer).resources.insert(id(dstImage));
  commands.at(commandBuffer).buffers.insert(srcBuffer);
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkCmdDraw(VkCommandBuffer commandBuffer,
                                                uint32_t vertexCount,
                                                uint32_t instanceCount,
                                                uint32_t firstVertex,
                                                uint32_t firstInstance) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkCmdEndRenderPass(VkCommandBuffer commandBuffer) {}

extern "C" VKAPI_ATTR void VKAPI_CALL vkCmdPipelineBarrier(
    VkCommandBuffer commandBuffer, VkPipelineStageFlags srcStageMask,
    VkPipelineStageFlags dstStageMask, VkDependencyFlags dependencyFlags,
    uint32_t memoryBarrierCount, const VkMemoryBarrier *pMemoryBarriers,
    uint32_t bufferMemoryBarrierCount,
    const VkBufferMemoryBarrier *pBufferMemoryBarriers,
    uint32_t imageMemoryBarrierCount,
    const VkImageMemoryBarrier *pImageMemoryBarriers) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkCmdPushConstants(VkCommandBuffer commandBuffer, VkPipelineLayout layout,
                   VkShaderStageFlags stageFlags, uint32_t offset,
                   uint32_t size, const void *pValues) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkCmdSetScissor(VkCommandBuffer commandBuffer, uint32_t firstScissor,
                uint32_t scissorCount, const VkRect2D *pScissors) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkCmdSetViewport(VkCommandBuffer commandBuffer, uint32_t firstViewport,
                 uint32_t viewportCount, const VkViewport *pViewports) {}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateAndroidSurfaceKHR(
    VkInstance instance, const VkAndroidSurfaceCreateInfoKHR *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkSurfaceKHR *pSurface) {
  *pSurface = make<VkSurfaceKHR>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkCreateBuffer(VkDevice device, const VkBufferCreateInfo *pCreateInfo,
               const VkAllocationCallbacks *pAllocator, VkBuffer *pBuffer) {
  *pBuffer = make<VkBuffer>();
  sizes[id(*pBuffer)] = pCreateInfo->size;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateCommandPool(
    VkDevice device, const VkCommandPoolCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkCommandPool *pCommandPool) {
  *pCommandPool = make<VkCommandPool>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateDescriptorPool(
    VkDevice device, const VkDescriptorPoolCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkDescriptorPool *pDescriptorPool) {
  *pDescriptorPool = make<VkDescriptorPool>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateDescriptorSetLayout(
    VkDevice device, const VkDescriptorSetLayoutCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkDescriptorSetLayout *pSetLayout) {
  *pSetLayout = make<VkDescriptorSetLayout>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateDevice(
    VkPhysicalDevice physicalDevice, const VkDeviceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkDevice *pDevice) {
  *pDevice = make<VkDevice>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkCreateFence(VkDevice device, const VkFenceCreateInfo *pCreateInfo,
              const VkAllocationCallbacks *pAllocator, VkFence *pFence) {
  *pFence = make<VkFence>();
  fences[*pFence].signaled =
      (pCreateInfo->flags & VK_FENCE_CREATE_SIGNALED_BIT);
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateFramebuffer(
    VkDevice device, const VkFramebufferCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkFramebuffer *pFramebuffer) {
  *pFramebuffer = make<VkFramebuffer>();
  framebuffers[*pFramebuffer] = views.at(pCreateInfo->pAttachments[0]);
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateGraphicsPipelines(
    VkDevice device, VkPipelineCache pipelineCache, uint32_t createInfoCount,
    const VkGraphicsPipelineCreateInfo *pCreateInfos,
    const VkAllocationCallbacks *pAllocator, VkPipeline *pPipelines) {
  for (uint32_t i = 0; i < createInfoCount; ++i)
    pPipelines[i] = make<VkPipeline>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkCreateImage(VkDevice device, const VkImageCreateInfo *pCreateInfo,
              const VkAllocationCallbacks *pAllocator, VkImage *pImage) {
  *pImage = make<VkImage>();
  sizes[id(*pImage)] =
      uint64_t(pCreateInfo->extent.width) * pCreateInfo->extent.height * 4;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkCreateImageView(VkDevice device, const VkImageViewCreateInfo *pCreateInfo,
                  const VkAllocationCallbacks *pAllocator, VkImageView *pView) {
  *pView = make<VkImageView>();
  views[*pView] = pCreateInfo->image;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(
    const VkInstanceCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkInstance *pInstance) {
  *pInstance = make<VkInstance>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreatePipelineLayout(
    VkDevice device, const VkPipelineLayoutCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator,
    VkPipelineLayout *pPipelineLayout) {
  *pPipelineLayout = make<VkPipelineLayout>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateRenderPass(
    VkDevice device, const VkRenderPassCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkRenderPass *pRenderPass) {
  *pRenderPass = make<VkRenderPass>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkCreateSampler(VkDevice device, const VkSamplerCreateInfo *pCreateInfo,
                const VkAllocationCallbacks *pAllocator, VkSampler *pSampler) {
  *pSampler = make<VkSampler>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateSemaphore(
    VkDevice device, const VkSemaphoreCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkSemaphore *pSemaphore) {
  *pSemaphore = make<VkSemaphore>();
  semaphores[*pSemaphore] = false;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateShaderModule(
    VkDevice device, const VkShaderModuleCreateInfo *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkShaderModule *pShaderModule) {
  *pShaderModule = make<VkShaderModule>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkCreateSwapchainKHR(
    VkDevice device, const VkSwapchainCreateInfoKHR *pCreateInfo,
    const VkAllocationCallbacks *pAllocator, VkSwapchainKHR *pSwapchain) {
  assert(pCreateInfo->presentMode == VK_PRESENT_MODE_FIFO_KHR);
  assert(pCreateInfo->minImageCount == (capMax == 2 ? 2 : 3));
  ++rebuilds;
  *pSwapchain = make<VkSwapchainKHR>();
  swapImages.clear();
  presentSemaphores.clear();
  for (uint32_t i = 0; i < pCreateInfo->minImageCount; ++i)
    swapImages.push_back(make<VkImage>());
  nextImage = 0;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkDestroyBuffer(
    VkDevice device, VkBuffer buffer, const VkAllocationCallbacks *pAllocator) {
  assertUnused(id(buffer));
  buffers.erase(buffer);
  sizes.erase(id(buffer));
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyCommandPool(VkDevice device, VkCommandPool commandPool,
                     const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyDescriptorPool(VkDevice device, VkDescriptorPool descriptorPool,
                        const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL vkDestroyDescriptorSetLayout(
    VkDevice device, VkDescriptorSetLayout descriptorSetLayout,
    const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyDevice(VkDevice device, const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL vkDestroyFence(
    VkDevice device, VkFence fence, const VkAllocationCallbacks *pAllocator) {
  assert(!fences.at(fence).pending);
  fences.erase(fence);
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyFramebuffer(VkDevice device, VkFramebuffer framebuffer,
                     const VkAllocationCallbacks *pAllocator) {
  assertUnused(id(framebuffers.at(framebuffer)));
  framebuffers.erase(framebuffer);
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkDestroyImage(
    VkDevice device, VkImage image, const VkAllocationCallbacks *pAllocator) {
  assertUnused(id(image));
  imageMemory.erase(image);
  sizes.erase(id(image));
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyImageView(VkDevice device, VkImageView imageView,
                   const VkAllocationCallbacks *pAllocator) {
  assertUnused(id(views.at(imageView)));
  views.erase(imageView);
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkDestroyInstance(
    VkInstance instance, const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyPipeline(VkDevice device, VkPipeline pipeline,
                  const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyPipelineLayout(VkDevice device, VkPipelineLayout pipelineLayout,
                        const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyRenderPass(VkDevice device, VkRenderPass renderPass,
                    const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroySampler(VkDevice device, VkSampler sampler,
                 const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroySemaphore(VkDevice device, VkSemaphore semaphore,
                   const VkAllocationCallbacks *pAllocator) {
  assert(!semaphores.at(semaphore));
  semaphores.erase(semaphore);
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroyShaderModule(VkDevice device, VkShaderModule shaderModule,
                      const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroySurfaceKHR(VkInstance instance, VkSurfaceKHR surface,
                    const VkAllocationCallbacks *pAllocator) {}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkDestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain,
                      const VkAllocationCallbacks *pAllocator) {
  assert(presentSemaphores.empty());
  swapImages.clear();
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkDeviceWaitIdle(VkDevice device) {
  verifyPending();
  for (auto &[f, state] : fences) {
    state.pending = false;
    state.signaled = true;
  }
  for (auto &[s, signaled] : semaphores)
    signaled = false;
  presentSemaphores.clear();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkEndCommandBuffer(VkCommandBuffer commandBuffer) {
  if (failEnd) {
    failEnd = false;
    return VK_ERROR_OUT_OF_HOST_MEMORY;
  }
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceVersion(uint32_t *pApiVersion) {
  *pApiVersion = VK_API_VERSION_1_1;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkEnumeratePhysicalDevices(VkInstance instance, uint32_t *pPhysicalDeviceCount,
                           VkPhysicalDevice *pPhysicalDevices) {
  *pPhysicalDeviceCount = 1;
  if (pPhysicalDevices)
    *pPhysicalDevices = make<VkPhysicalDevice>();
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkFreeDescriptorSets(
    VkDevice device, VkDescriptorPool descriptorPool,
    uint32_t descriptorSetCount, const VkDescriptorSet *pDescriptorSets) {
  for (uint32_t i = 0; i < descriptorSetCount; ++i) {
    assertUnused(id(pDescriptorSets[i]));
    descriptors.erase(pDescriptorSets[i]);
  }
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkFreeMemory(VkDevice device, VkDeviceMemory memory,
             const VkAllocationCallbacks *pAllocator) {
  assertUnused(id(memory));
  mock::memory.erase(memory);
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkGetBufferMemoryRequirements(VkDevice device, VkBuffer buffer,
                              VkMemoryRequirements *pMemoryRequirements) {
  *pMemoryRequirements = {sizes.at(id(buffer)), 4, 1};
}

extern "C" VKAPI_ATTR void VKAPI_CALL
vkGetDeviceQueue(VkDevice device, uint32_t queueFamilyIndex,
                 uint32_t queueIndex, VkQueue *pQueue) {
  *pQueue = make<VkQueue>();
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkGetImageMemoryRequirements(
    VkDevice device, VkImage image, VkMemoryRequirements *pMemoryRequirements) {
  *pMemoryRequirements = {sizes.at(id(image)), 4, 1};
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFormatProperties(
    VkPhysicalDevice physicalDevice, VkFormat format,
    VkFormatProperties *pFormatProperties) {
  *pFormatProperties = {};
  pFormatProperties->optimalTilingFeatures = ~VkFormatFeatureFlags{0};
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceMemoryProperties(
    VkPhysicalDevice physicalDevice,
    VkPhysicalDeviceMemoryProperties *pMemoryProperties) {
  *pMemoryProperties = {};
  pMemoryProperties->memoryTypeCount = 1;
  pMemoryProperties->memoryTypes[0].propertyFlags =
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceProperties(
    VkPhysicalDevice physicalDevice, VkPhysicalDeviceProperties *pProperties) {
  *pProperties = {};
  pProperties->limits.maxPushConstantsSize = 128;
  pProperties->limits.maxStorageBufferRange = 16 * 1024 * 1024;
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceQueueFamilyProperties(
    VkPhysicalDevice physicalDevice, uint32_t *pQueueFamilyPropertyCount,
    VkQueueFamilyProperties *pQueueFamilyProperties) {
  *pQueueFamilyPropertyCount = 1;
  if (pQueueFamilyProperties)
    pQueueFamilyProperties[0].queueFlags = VK_QUEUE_GRAPHICS_BIT;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
    VkSurfaceCapabilitiesKHR *pSurfaceCapabilities) {
  *pSurfaceCapabilities = {};
  pSurfaceCapabilities->minImageCount = 2;
  pSurfaceCapabilities->maxImageCount = capMax;
  pSurfaceCapabilities->currentExtent = {32, 32};
  pSurfaceCapabilities->supportedUsageFlags = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  pSurfaceCapabilities->supportedCompositeAlpha =
      VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceFormatsKHR(
    VkPhysicalDevice physicalDevice, VkSurfaceKHR surface,
    uint32_t *pSurfaceFormatCount, VkSurfaceFormatKHR *pSurfaceFormats) {
  *pSurfaceFormatCount = 1;
  if (pSurfaceFormats)
    *pSurfaceFormats = {VK_FORMAT_R8G8B8A8_UNORM,
                        VK_COLOR_SPACE_SRGB_NONLINEAR_KHR};
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkGetPhysicalDeviceSurfaceSupportKHR(
    VkPhysicalDevice physicalDevice, uint32_t queueFamilyIndex,
    VkSurfaceKHR surface, VkBool32 *pSupported) {
  *pSupported = VK_TRUE;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkGetSwapchainImagesKHR(
    VkDevice device, VkSwapchainKHR swapchain, uint32_t *pSwapchainImageCount,
    VkImage *pSwapchainImages) {
  *pSwapchainImageCount = swapImages.size();
  if (pSwapchainImages)
    std::copy(swapImages.begin(), swapImages.end(), pSwapchainImages);
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkMapMemory(VkDevice device, VkDeviceMemory memory, VkDeviceSize offset,
            VkDeviceSize size, VkMemoryMapFlags flags, void **ppData) {
  *ppData = mock::memory.at(memory).data() + offset;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR *pPresentInfo) {
  assert(pPresentInfo->waitSemaphoreCount == 1);
  auto semaphore = *pPresentInfo->pWaitSemaphores;
  assert(semaphores.at(semaphore));
  presentSemaphores[*pPresentInfo->pImageIndices] = semaphore;
  auto result = presentResult;
  presentResult = VK_SUCCESS;
  return result;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkQueueSubmit(VkQueue queue, uint32_t submitCount, const VkSubmitInfo *pSubmits,
              VkFence fence) {
  verifyPending();
  if (failSubmit) {
    failSubmit = false;
    return VK_ERROR_OUT_OF_HOST_MEMORY;
  }
  assert(submitCount == 1);
  if (failDrain && pSubmits[0].commandBufferCount == 0)
    return VK_ERROR_DEVICE_LOST;
  auto &submit = pSubmits[0];
  for (uint32_t i = 0; i < submit.waitSemaphoreCount; ++i) {
    assert(semaphores.at(submit.pWaitSemaphores[i]));
    semaphores.at(submit.pWaitSemaphores[i]) = false;
  }
  for (uint32_t i = 0; i < submit.signalSemaphoreCount; ++i) {
    assert(!semaphores.at(submit.pSignalSemaphores[i]));
    semaphores.at(submit.pSignalSemaphores[i]) = true;
  }
  auto &state = fences.at(fence);
  assert(!state.signaled && !state.pending);
  state = {};
  state.pending = true;
  if (submit.commandBufferCount) {
    assert(submit.commandBufferCount == 1);
    state.command = *submit.pCommandBuffers;
    state.resources = commands.at(state.command).resources;
    for (auto buffer : commands.at(state.command).buffers) {
      auto allocation = buffers.at(buffer);
      state.resources.insert(id(allocation));
      state.snapshots[allocation] = mock::memory.at(allocation);
    }
    submissions.push_back({fence, state.command, *submit.pWaitSemaphores});
  } else
    ++drains;
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL vkResetCommandBuffer(
    VkCommandBuffer commandBuffer, VkCommandBufferResetFlags flags) {
  for (auto &[f, state] : fences)
    assert(!state.pending || state.command != commandBuffer);
  commands.at(commandBuffer) = {};
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkResetFences(VkDevice device, uint32_t fenceCount, const VkFence *pFences) {
  for (uint32_t i = 0; i < fenceCount; ++i) {
    assert(!fences.at(pFences[i]).pending);
    fences.at(pFences[i]).signaled = false;
  }
  return VK_SUCCESS;
}

extern "C" VKAPI_ATTR void VKAPI_CALL vkUnmapMemory(VkDevice device,
                                                    VkDeviceMemory memory) {}

extern "C" VKAPI_ATTR void VKAPI_CALL vkUpdateDescriptorSets(
    VkDevice device, uint32_t descriptorWriteCount,
    const VkWriteDescriptorSet *pDescriptorWrites, uint32_t descriptorCopyCount,
    const VkCopyDescriptorSet *pDescriptorCopies) {
  for (uint32_t i = 0; i < descriptorWriteCount; ++i) {
    auto &write = pDescriptorWrites[i];
    assertUnused(id(write.dstSet));
    if (write.dstBinding == 0)
      descriptors[write.dstSet].image = views.at(write.pImageInfo->imageView);
    else
      descriptors[write.dstSet].buffer = write.pBufferInfo->buffer;
  }
}

extern "C" VKAPI_ATTR VkResult VKAPI_CALL
vkWaitForFences(VkDevice device, uint32_t fenceCount, const VkFence *pFences,
                VkBool32 waitAll, uint64_t timeout) {
  verifyPending();
  for (uint32_t i = 0; i < fenceCount; ++i) {
    assert(fences.at(pFences[i]).pending || fences.at(pFences[i]).signaled);
    waits.push_back(pFences[i]);
    fences.at(pFences[i]).pending = false;
    fences.at(pFences[i]).signaled = true;
  }
  return VK_SUCCESS;
}

static uint64_t textureRevision = 1;
extern "C" int32_t pod_runtime_texture_for_handle(PodRuntime *, int32_t handle,
                                                  PodTextureView *out) {
  static const uint8_t pixels[] = {0, 1};
  static const uint8_t palette[1024] = {0, 0, 255, 255, 0, 255, 0, 128};
  if (handle < 0x10000)
    return 1;
  *out = {handle, textureRevision, pixels,          2, 2, 1,
          5,      palette,         sizeof(palette), 0};
  return 0;
}
extern "C" int32_t pod_runtime_font(PodRuntime *, uint32_t slot,
                                    PodFontView *out) {
  static const uint8_t pixels[] = {64, 128, 255, 32, 10, 20,
                                   30, 40,  1,   2,  3,  4};
  if (slot != 2)
    return 1;
  *out = {2, 1, 1, 1, 1, 2, 3, pixels, sizeof(pixels)};
  return 0;
}
extern "C" uint64_t pod_runtime_font_epoch(PodRuntime *, uint32_t slot) {
  return slot == 2 ? 77 : 0;
}
extern "C" int32_t pod_runtime_glyph(PodRuntime *runtime, uint32_t slot,
                                     uint32_t glyph, PodGlyphView *out) {
  PodFontView font{};
  if (pod_runtime_font(runtime, slot, &font) || glyph >= font.glyph_count)
    return 1;
  *out = {font.bitmap + glyph * 4, 4};
  return 0;
}
static uint32_t xy(int x, int y) {
  return uint16_t(x) | (uint32_t(uint16_t(y)) << 16);
}
static PodRuntime *runtime = reinterpret_cast<PodRuntime *>(1);
static bool draw(VulkanRenderer &renderer, const std::vector<uint32_t> &words,
                 uint64_t hash) {
  return renderer.submitDrawList(runtime,
                                 {words.data(), words.size(), hash, 0, 1});
}
static const std::vector<uint32_t> image = {
    4, 0x10007, xy(0, 0), xy(16, 16), 0, 0, 0x3f800000, 0x3f800000, 0xffffffff};
static std::vector<uint32_t> clippedImage(int radius) {
  return {11,
          xy(0, 0),
          xy(16, 16),
          std::bit_cast<uint32_t>(float(radius)),
          std::bit_cast<uint32_t>(float(radius)),
          4,
          0x10007,
          xy(0, 0),
          xy(16, 16),
          0,
          0,
          0x3f800000,
          0x3f800000,
          0xffffffff,
          6};
}
static const std::vector<uint32_t> rect = {1, xy(0, 0), xy(10, 10), 0xffffffff};
static std::vector<uint32_t> glyph(uint32_t index) {
  return {3, (1u << 16) | 2, 0xffffffff, xy(0, 0), index};
}

static void twoPendingFramesKeepBuffersAndTexturesAlive() {
  ANativeWindow window;
  {
    VulkanRenderer renderer(&window, 32, 32, 32, 32);
    assert(renderer.valid());
    auto before = submissions.size(), waitStart = waits.size();
    assert(draw(renderer, clippedImage(1), 1));
    auto first = submissions.back();
    assert(draw(renderer, clippedImage(2), 2));
    auto second = submissions.back();
    assert(submissions.size() == before + 2 && waits.size() == waitStart);
    assert(first.fence != second.fence && first.command != second.command &&
           first.acquired != second.acquired);
    verifyPending(); // Frame 1's clip and upload data are unchanged by frame 2.

    VkImage oldTexture{};
    for (const auto &[texture, allocation] : imageMemory)
      if (sizes.at(id(texture)) == 8)
        oldTexture = texture;
    assert(oldTexture);
    ++textureRevision;
    assert(draw(renderer, clippedImage(3), 3));
    assert(imageMemory.count(oldTexture));
    assert(waits.size() == waitStart + 1 && waits.back() == first.fence);
    assert(submissions.back().command == first.command);
    assert(fences.at(second.fence).pending);
    verifyPending(); // Frame 2 still samples the prior texture revision.
    assert(draw(renderer, clippedImage(4), 4));
    assert(waits.back() == second.fence);
    assert(!imageMemory.count(oldTexture));
    assert(submissions.back().command == second.command);
  }
  assert(window.references == 1);
  assert(memory.empty() && buffers.empty() && imageMemory.empty() &&
         descriptors.empty());
}

static void glyphRevisionsCpuFallbackAndSurfaceRecovery() {
  ANativeWindow window;
  {
    VulkanRenderer renderer(&window, 32, 32, 32, 32);
    assert(draw(renderer, glyph(0), 10));
    assert(draw(renderer, glyph(1),
                11)); // Extends a page still sampled by slot 0.
    verifyPending();
    std::vector<uint8_t> pixels(renderer.rasterBytes(), 63);
    assert(renderer.submitRgba(pixels.data(), pixels.size(), 12));
    assert(draw(renderer, glyph(2), 13));
    verifyPending();
    auto rebuildStart = rebuilds;
    renderer.replaceWindow(nullptr, 0, 0);
    assert(!renderer.valid());
    renderer.replaceWindow(&window, 32, 32);
    assert(renderer.valid() && rebuilds == rebuildStart + 1);
    assert(draw(renderer, rect, 14));
    acquireResult = VK_ERROR_OUT_OF_DATE_KHR;
    assert(!draw(renderer, rect, 15) && renderer.valid());
    assert(renderer.submitRgba(pixels.data(), pixels.size(), 15));
    presentResult = VK_ERROR_SURFACE_LOST_KHR;
    assert(!draw(renderer, glyph(0), 16) && renderer.valid());
    assert(
        draw(renderer, glyph(0),
             16)); // Successfully submitted upload remains ready after rebuild.
  }
  assert(window.references == 1);
  assert(memory.empty() && imageMemory.empty() && descriptors.empty());
}

static void errorsAfterAcquireConsumeSemaphoresBeforeFallback() {
  ANativeWindow window;
  VulkanRenderer renderer(&window, 32, 32, 32, 32);
  assert(draw(renderer, image, 20));
  for (int kind = 0; kind < 3; ++kind) {
    auto drainStart = drains;
    if (kind == 0)
      failBegin = true;
    if (kind == 1)
      failEnd = true;
    if (kind == 2)
      failSubmit = true;
    assert(!draw(renderer, glyph(kind), 21 + kind));
    assert(drains == drainStart + 1 && renderer.valid());
    std::vector<uint8_t> pixels(renderer.rasterBytes(), uint8_t(20 + kind));
    assert(renderer.submitRgba(pixels.data(), pixels.size(), 21 + kind));
  }
  assert(draw(renderer, image, 25));
  assert(
      !draw(renderer, image, 25)); // Unchanged frame does not wait or acquire.
}

static void textureBudgetIncludesAllInFlightVersions() {
  ANativeWindow window;
  VulkanRenderer renderer(&window, 32, 32, 32, 32);
  auto images = [](uint32_t first) {
    std::vector<uint32_t> words;
    for (uint32_t i = 0; i < 600; ++i) {
      auto command = image;
      command[1] = 0x10000 + first + i;
      words.insert(words.end(), command.begin(), command.end());
    }
    return words;
  };
  assert(draw(renderer, images(0), 30));
  const auto prior = submissions.back();
  auto waitStart = waits.size(), acquireStart = acquired.size();
  assert(!draw(renderer, images(600), 31));
  assert(fences.at(prior.fence).pending);
  assert(waits.size() == waitStart && acquired.size() == acquireStart);
  verifyPending(); // The cache must fail closed, never evict a sampled
                   // resource.
  std::vector<uint8_t> pixels(renderer.rasterBytes(), 120);
  assert(renderer.submitRgba(pixels.data(), pixels.size(), 31));
  assert(draw(renderer, images(600),
              32)); // Slot 0's old resources can now be evicted.
  assert(waits.back() == prior.fence);
}

static void aFailedDrainDisablesTheRendererWithoutWaitingAnUnsubmittedFence() {
  ANativeWindow window;
  VulkanRenderer renderer(&window, 32, 32, 32, 32);
  failSubmit = failDrain = true;
  assert(!draw(renderer, rect, 40));
  assert(!renderer.valid());
  failDrain = false;
}

int main() {
  twoPendingFramesKeepBuffersAndTexturesAlive();
  glyphRevisionsCpuFallbackAndSurfaceRecovery();
  errorsAfterAcquireConsumeSemaphoresBeforeFallback();
  textureBudgetIncludesAllInFlightVersions();
  aFailedDrainDisablesTheRendererWithoutWaitingAnUnsubmittedFence();
  capMax = 2; // The surface is permitted to clamp minImageCount + 1.
  twoPendingFramesKeepBuffersAndTexturesAlive();
  std::puts("vulkan_frame_pipeline_test: all synchronization scenarios passed "
            "(host mock)");
}
