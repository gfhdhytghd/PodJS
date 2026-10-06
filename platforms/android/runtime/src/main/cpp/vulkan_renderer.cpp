#include "vulkan_renderer.h"

#include <android/log.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#include "gpu_shaders.h"
#include "vulkan_gpu.h"

namespace {
constexpr VkFormat kRasterFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr char kLogTag[] = "PodJS";

bool logVkFailure(const char* operation, VkResult result) {
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s failed: VkResult=%d",
                      operation, static_cast<int>(result));
  return false;
}

bool logFailure(const char* message) {
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message);
  return false;
}

VkDeviceSize align4(VkDeviceSize value) {
  return (value + 3u) & ~VkDeviceSize{3u};
}
template <class F> struct OnExit {
  F action;
  ~OnExit() { action(); }
};
}  // namespace

#define POD_VK(operation)                                                     \
  do {                                                                        \
    const VkResult podVkResult = (operation);                                  \
    if (podVkResult != VK_SUCCESS) return logVkFailure(#operation, podVkResult); \
  } while (0)

VulkanRenderer::VulkanRenderer(ANativeWindow* window, uint32_t width,
                               uint32_t height, uint32_t rasterWidth,
                               uint32_t rasterHeight)
    : rasterWidth_(rasterWidth), rasterHeight_(rasterHeight) {
  uint32_t loader = 0;
  if (vkEnumerateInstanceVersion(&loader) != VK_SUCCESS ||
      loader < VK_API_VERSION_1_1) {
    logFailure("Vulkan 1.1 unavailable");
    return;
  }

  const char* extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME,
                              VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "PodJS";
  app.apiVersion = VK_API_VERSION_1_1;
  VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  createInfo.pApplicationInfo = &app;
  createInfo.enabledExtensionCount = 2;
  createInfo.ppEnabledExtensionNames = extensions;
  // Log completed phases, preserving the original failure short-circuit order.
  const auto timed = [](const char* name, auto operation) {
    const auto began = std::chrono::steady_clock::now();
    const bool success = operation();
    const double elapsed = std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - began).count();
    __android_log_print(ANDROID_LOG_INFO,"PodJSPerf","vulkanCreate stage=%s elapsedMs=%.3f success=%d",
        name,elapsed,success);
    return success;
  };
  if (!timed("instance", [&] { return vkCreateInstance(&createInfo, nullptr, &instance_) == VK_SUCCESS; }) ||
      !timed("device", [&] { return createDevice(); }) ||
      !timed("uploadResources", [&] { return createUploadResources(); }) ||
      !timed("clipResources", [&] { return createClipResources(); }) ||
      !timed("graphicsResources", [&] { return createGraphicsResources(); })) {
    return;
  }
  timed("surfaceSwapchain", [&] { replaceWindow(window, width, height); return valid_; });
}

uint32_t VulkanRenderer::findMemoryType(
    uint32_t typeBits, VkMemoryPropertyFlags properties) const {
  VkPhysicalDeviceMemoryProperties memory{};
  vkGetPhysicalDeviceMemoryProperties(physical_, &memory);
  for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
    if ((typeBits & (1u << i)) &&
        (memory.memoryTypes[i].propertyFlags & properties) == properties) {
      return i;
    }
  }
  return UINT32_MAX;
}

bool VulkanRenderer::createDevice() {
  uint32_t count = 0;
  POD_VK(vkEnumeratePhysicalDevices(instance_, &count, nullptr));
  if (count == 0) return logFailure("No Vulkan physical device available");
  std::vector<VkPhysicalDevice> devices(count);
  POD_VK(vkEnumeratePhysicalDevices(instance_, &count, devices.data()));

  for (VkPhysicalDevice candidate : devices) {
    VkPhysicalDeviceProperties deviceProperties{};
    vkGetPhysicalDeviceProperties(candidate, &deviceProperties);
    if (deviceProperties.limits.maxPushConstantsSize <
        sizeof(podjs_gpu::PrimitivePush)) {
      continue;
    }
    VkFormatProperties rasterProperties{};
    vkGetPhysicalDeviceFormatProperties(candidate, kRasterFormat,
                                        &rasterProperties);
    const VkFormatFeatureFlags required =
        VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
        VK_FORMAT_FEATURE_BLIT_SRC_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((rasterProperties.optimalTilingFeatures & required) != required) {
      continue;
    }

    uint32_t queueCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueCount, nullptr);
    std::vector<VkQueueFamilyProperties> queueProperties(queueCount);
    vkGetPhysicalDeviceQueueFamilyProperties(candidate, &queueCount,
                                             queueProperties.data());
    for (uint32_t i = 0; i < queueCount; ++i) {
      if (queueProperties[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
        physical_ = candidate;
        queueFamily_ = i;
        break;
      }
    }
    if (physical_) break;
  }
  if (!physical_) {
    return logFailure(
        "No Vulkan graphics device supports RGBA8 color rendering and linear blit");
  }

  float priority = 1.0f;
  VkDeviceQueueCreateInfo queueCreate{
      VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  queueCreate.queueFamilyIndex = queueFamily_;
  queueCreate.queueCount = 1;
  queueCreate.pQueuePriorities = &priority;
  const char* extension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
  VkDeviceCreateInfo deviceCreate{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  deviceCreate.queueCreateInfoCount = 1;
  deviceCreate.pQueueCreateInfos = &queueCreate;
  deviceCreate.enabledExtensionCount = 1;
  deviceCreate.ppEnabledExtensionNames = &extension;
  POD_VK(vkCreateDevice(physical_, &deviceCreate, nullptr, &device_));
  vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);

  VkCommandPoolCreateInfo pool{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pool.queueFamilyIndex = queueFamily_;
  pool.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT |
               VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
  POD_VK(vkCreateCommandPool(device_, &pool, nullptr, &commandPool_));

  for (auto &slot : frames_) {
    VkCommandBufferAllocateInfo allocate{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate.commandPool = commandPool_;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    POD_VK(vkAllocateCommandBuffers(device_, &allocate, &slot.command));
  }
  return true; // Slot sync objects are initialized by replaceWindow.
}

// Called only before use or after device idle during a surface rebuild.
bool VulkanRenderer::resetFrameSync() {
  for (uint32_t i = 0; i < kFramesInFlight; ++i) {
    auto &slot = frames_[i];
    releaseFrame(i);
    if (slot.acquired)
      vkDestroySemaphore(device_, slot.acquired, nullptr);
    if (slot.fence)
      vkDestroyFence(device_, slot.fence, nullptr);
    slot.acquired = VK_NULL_HANDLE;
    slot.fence = VK_NULL_HANDLE;
    VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    POD_VK(vkCreateSemaphore(device_, &semaphore, nullptr, &slot.acquired));
    VkFenceCreateInfo fence{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    POD_VK(vkCreateFence(device_, &fence, nullptr, &slot.fence));
    slot.pending = slot.acquiredImage = false;
  }
  collectRetiredTextures();
  return true;
}

bool VulkanRenderer::gpuFailureOnce(const std::string& reason) {
  if (loggedGpuFailures_.insert(reason).second) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Vulkan DrawList fallback: %s", reason.c_str());
  }
  return false;
}

bool VulkanRenderer::ensureStagingCapacity(VkDeviceSize required) {
  return ensureStagingCapacity(frame(), required);
}

bool VulkanRenderer::ensureStagingCapacity(FrameSlot &slot,
                                           VkDeviceSize required) {
  required = std::max<VkDeviceSize>(required, 4);
  if (slot.stagingBuffer && slot.stagingCapacity >= required)
    return true;

  if (slot.stagingMapped) {
    vkUnmapMemory(device_, slot.stagingMemory);
    slot.stagingMapped = nullptr;
  }
  if (slot.stagingBuffer)
    vkDestroyBuffer(device_, slot.stagingBuffer, nullptr);
  if (slot.stagingMemory)
    vkFreeMemory(device_, slot.stagingMemory, nullptr);
  slot.stagingBuffer = VK_NULL_HANDLE;
  slot.stagingMemory = VK_NULL_HANDLE;
  slot.stagingCapacity = 0;

  VkBufferCreateInfo buffer{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  buffer.size = required;
  buffer.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
  buffer.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  POD_VK(vkCreateBuffer(device_, &buffer, nullptr, &slot.stagingBuffer));

  VkMemoryRequirements requirements{};
  vkGetBufferMemoryRequirements(device_, slot.stagingBuffer, &requirements);
  const uint32_t memoryType =
      findMemoryType(requirements.memoryTypeBits,
                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                         VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  if (memoryType == UINT32_MAX) {
    return logFailure("No host-coherent Vulkan staging memory type");
  }
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.allocationSize = requirements.size;
  allocation.memoryTypeIndex = memoryType;
  POD_VK(vkAllocateMemory(device_, &allocation, nullptr, &slot.stagingMemory));
  POD_VK(
      vkBindBufferMemory(device_, slot.stagingBuffer, slot.stagingMemory, 0));
  POD_VK(vkMapMemory(device_, slot.stagingMemory, 0, required, 0,
                     &slot.stagingMapped));
  slot.stagingCapacity = required;
  return true;
}

bool VulkanRenderer::createUploadResources() {
  for (auto &slot : frames_)
    if (!createFrameUploadResources(slot))
      return false;
  return true;
}

bool VulkanRenderer::createFrameUploadResources(FrameSlot &slot) {
  if (!ensureStagingCapacity(slot, rasterBytes()))
    return false;

  VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  image.imageType = VK_IMAGE_TYPE_2D;
  image.format = kRasterFormat;
  image.extent = {rasterWidth_, rasterHeight_, 1};
  image.mipLevels = 1;
  image.arrayLayers = 1;
  image.samples = VK_SAMPLE_COUNT_1_BIT;
  image.tiling = VK_IMAGE_TILING_OPTIMAL;
  image.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  POD_VK(vkCreateImage(device_, &image, nullptr, &slot.rasterImage));

  VkMemoryRequirements requirements{};
  vkGetImageMemoryRequirements(device_, slot.rasterImage, &requirements);
  const uint32_t memoryType =
      findMemoryType(requirements.memoryTypeBits,
                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (memoryType == UINT32_MAX) {
    return logFailure("No device-local memory for Vulkan raster target");
  }
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.allocationSize = requirements.size;
  allocation.memoryTypeIndex = memoryType;
  POD_VK(vkAllocateMemory(device_, &allocation, nullptr, &slot.rasterMemory));
  POD_VK(vkBindImageMemory(device_, slot.rasterImage, slot.rasterMemory, 0));

  VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  view.image = slot.rasterImage;
  view.viewType = VK_IMAGE_VIEW_TYPE_2D;
  view.format = kRasterFormat;
  view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  POD_VK(vkCreateImageView(device_, &view, nullptr, &slot.rasterView));
  return true;
}

bool VulkanRenderer::createClipResources() {
  for (auto &slot : frames_)
    if (!createFrameClipResources(slot))
      return false;
  return true;
}

bool VulkanRenderer::createFrameClipResources(FrameSlot &slot) {
  const VkDeviceSize bytes = VkDeviceSize{kRoundedClipCapacity} *
                             sizeof(podjs_gpu::RoundedClipData);
  VkPhysicalDeviceProperties properties{};
  vkGetPhysicalDeviceProperties(physical_, &properties);
  if (properties.limits.maxStorageBufferRange < bytes) {
    return logFailure("Vulkan storage buffer range is too small for rounded clips");
  }
  VkBufferCreateInfo buffer{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  buffer.size = bytes;
  buffer.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  buffer.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  POD_VK(vkCreateBuffer(device_, &buffer, nullptr, &slot.clipBuffer));
  VkMemoryRequirements requirements{};
  vkGetBufferMemoryRequirements(device_, slot.clipBuffer, &requirements);
  const uint32_t memoryType = findMemoryType(
      requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  if (memoryType == UINT32_MAX) {
    return logFailure("No host-coherent Vulkan rounded clip memory type");
  }
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.allocationSize = requirements.size;
  allocation.memoryTypeIndex = memoryType;
  POD_VK(vkAllocateMemory(device_, &allocation, nullptr, &slot.clipMemory));
  POD_VK(vkBindBufferMemory(device_, slot.clipBuffer, slot.clipMemory, 0));
  POD_VK(vkMapMemory(device_, slot.clipMemory, 0, bytes, 0, &slot.clipMapped));
  return true;
}

bool VulkanRenderer::createGraphicsResources() {
  VkDescriptorSetLayoutBinding bindings[2]{};
  bindings[0].binding = 0;
  bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  bindings[0].descriptorCount = 1;
  bindings[0].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
  bindings[1].binding = 1;
  bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
  bindings[1].descriptorCount = 1;
  bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
  VkDescriptorSetLayoutCreateInfo descriptorLayout{
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  descriptorLayout.bindingCount = 2;
  descriptorLayout.pBindings = bindings;
  POD_VK(vkCreateDescriptorSetLayout(device_, &descriptorLayout, nullptr,
                                     &descriptorLayout_));

  VkDescriptorPoolSize poolSizes[2]{};
  poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  poolSizes[0].descriptorCount = kDescriptorCapacity;
  poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
  poolSizes[1].descriptorCount = kDescriptorCapacity;
  VkDescriptorPoolCreateInfo descriptorPool{
      VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  descriptorPool.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
  descriptorPool.maxSets = kDescriptorCapacity;
  descriptorPool.poolSizeCount = 2;
  descriptorPool.pPoolSizes = poolSizes;
  POD_VK(vkCreateDescriptorPool(device_, &descriptorPool, nullptr,
                                &descriptorPool_));

  VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  sampler.magFilter = VK_FILTER_NEAREST;
  sampler.minFilter = VK_FILTER_NEAREST;
  sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
  sampler.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  sampler.maxLod = 0.0f;
  POD_VK(vkCreateSampler(device_, &sampler, nullptr, &nearestSampler_));
  sampler.magFilter = VK_FILTER_LINEAR;
  sampler.minFilter = VK_FILTER_LINEAR;
  POD_VK(vkCreateSampler(device_, &sampler, nullptr, &linearSampler_));

  VkAttachmentDescription attachment{};
  attachment.format = kRasterFormat;
  attachment.samples = VK_SAMPLE_COUNT_1_BIT;
  attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
  attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
  attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
  attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
  attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  attachment.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  VkAttachmentReference colorReference{0,
                                       VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
  VkSubpassDescription subpass{};
  subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
  subpass.colorAttachmentCount = 1;
  subpass.pColorAttachments = &colorReference;
  VkSubpassDependency dependencies[2]{};
  dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
  dependencies[0].dstSubpass = 0;
  dependencies[0].srcStageMask = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
  dependencies[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
  dependencies[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
  dependencies[1].srcSubpass = 0;
  dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
  dependencies[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
  dependencies[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
  dependencies[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
  dependencies[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  VkRenderPassCreateInfo renderPass{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
  renderPass.attachmentCount = 1;
  renderPass.pAttachments = &attachment;
  renderPass.subpassCount = 1;
  renderPass.pSubpasses = &subpass;
  renderPass.dependencyCount = 2;
  renderPass.pDependencies = dependencies;
  POD_VK(vkCreateRenderPass(device_, &renderPass, nullptr, &renderPass_));

  for (auto &slot : frames_) {
    VkFramebufferCreateInfo framebuffer{
        VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    framebuffer.renderPass = renderPass_;
    framebuffer.attachmentCount = 1;
    framebuffer.pAttachments = &slot.rasterView;
    framebuffer.width = rasterWidth_;
    framebuffer.height = rasterHeight_;
    framebuffer.layers = 1;
    POD_VK(
        vkCreateFramebuffer(device_, &framebuffer, nullptr, &slot.framebuffer));
  }

  VkPushConstantRange pushRange{};
  pushRange.stageFlags =
      VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
  pushRange.offset = 0;
  pushRange.size = sizeof(podjs_gpu::PrimitivePush);
  VkPipelineLayoutCreateInfo pipelineLayout{
      VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pipelineLayout.setLayoutCount = 1;
  pipelineLayout.pSetLayouts = &descriptorLayout_;
  pipelineLayout.pushConstantRangeCount = 1;
  pipelineLayout.pPushConstantRanges = &pushRange;
  POD_VK(vkCreatePipelineLayout(device_, &pipelineLayout, nullptr,
                                &pipelineLayout_));

  VkShaderModule vertexModule = VK_NULL_HANDLE;
  VkShaderModule fragmentModule = VK_NULL_HANDLE;
  VkShaderModuleCreateInfo shader{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
  shader.codeSize = sizeof(podjs_gpu::vertex_spv);
  shader.pCode = podjs_gpu::vertex_spv;
  VkResult result =
      vkCreateShaderModule(device_, &shader, nullptr, &vertexModule);
  if (result == VK_SUCCESS) {
    shader.codeSize = sizeof(podjs_gpu::fragment_spv);
    shader.pCode = podjs_gpu::fragment_spv;
    result = vkCreateShaderModule(device_, &shader, nullptr, &fragmentModule);
  }
  if (result != VK_SUCCESS) {
    if (vertexModule) vkDestroyShaderModule(device_, vertexModule, nullptr);
    return logVkFailure("vkCreateShaderModule", result);
  }

  VkPipelineShaderStageCreateInfo stages[2]{};
  stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
  stages[0].module = vertexModule;
  stages[0].pName = "main";
  stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
  stages[1].module = fragmentModule;
  stages[1].pName = "main";
  VkPipelineVertexInputStateCreateInfo vertexInput{
      VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  VkPipelineInputAssemblyStateCreateInfo inputAssembly{
      VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
  inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
  VkPipelineViewportStateCreateInfo viewportState{
      VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
  viewportState.viewportCount = 1;
  viewportState.scissorCount = 1;
  VkPipelineRasterizationStateCreateInfo rasterization{
      VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  rasterization.polygonMode = VK_POLYGON_MODE_FILL;
  rasterization.cullMode = VK_CULL_MODE_NONE;
  rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
  rasterization.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo multisample{
      VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
  multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  VkPipelineColorBlendAttachmentState blend{};
  blend.blendEnable = VK_TRUE;
  blend.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
  blend.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
  blend.colorBlendOp = VK_BLEND_OP_ADD;
  // The shader emits straight-alpha colors. RGB blending produces a
  // premultiplied framebuffer for Android composition; alpha tracks the
  // accumulated src-over coverage over the transparent clear color.
  blend.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
  blend.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
  blend.alphaBlendOp = VK_BLEND_OP_ADD;
  blend.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
  VkPipelineColorBlendStateCreateInfo blendState{
      VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
  blendState.attachmentCount = 1;
  blendState.pAttachments = &blend;
  const VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT,
                                          VK_DYNAMIC_STATE_SCISSOR};
  VkPipelineDynamicStateCreateInfo dynamic{
      VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
  dynamic.dynamicStateCount = 2;
  dynamic.pDynamicStates = dynamicStates;
  VkGraphicsPipelineCreateInfo pipeline{
      VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  pipeline.stageCount = 2;
  pipeline.pStages = stages;
  pipeline.pVertexInputState = &vertexInput;
  pipeline.pInputAssemblyState = &inputAssembly;
  pipeline.pViewportState = &viewportState;
  pipeline.pRasterizationState = &rasterization;
  pipeline.pMultisampleState = &multisample;
  pipeline.pColorBlendState = &blendState;
  pipeline.pDynamicState = &dynamic;
  pipeline.layout = pipelineLayout_;
  pipeline.renderPass = renderPass_;
  pipeline.subpass = 0;
  result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipeline,
                                     nullptr, &pipeline_);
  vkDestroyShaderModule(device_, fragmentModule, nullptr);
  vkDestroyShaderModule(device_, vertexModule, nullptr);
  return result == VK_SUCCESS ||
         logVkFailure("vkCreateGraphicsPipelines", result);
}

void VulkanRenderer::replaceWindow(ANativeWindow* window, uint32_t width,
                                   uint32_t height) {
  if (device_) {
    const VkResult idle = vkDeviceWaitIdle(device_);
    if (idle != VK_SUCCESS) {
      valid_ = false;
      lastHash_ = 0;
      logVkFailure("vkDeviceWaitIdle(surface rebuild)", idle);
      return;
    }
  }
  destroySurface();
  window_ = window;
  width_ = width;
  height_ = height;
  if (window_) ANativeWindow_acquire(window_);
  valid_ = resetFrameSync() && createSurfaceAndSwapchain();
  lastHash_ = 0;
}

bool VulkanRenderer::createSurfaceAndSwapchain() {
  if (!window_ || !instance_ || !device_) return false;
  VkAndroidSurfaceCreateInfoKHR surfaceCreate{
      VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
  surfaceCreate.window = window_;
  POD_VK(
      vkCreateAndroidSurfaceKHR(instance_, &surfaceCreate, nullptr, &surface_));

  VkBool32 present = VK_FALSE;
  POD_VK(vkGetPhysicalDeviceSurfaceSupportKHR(physical_, queueFamily_, surface_,
                                             &present));
  if (!present) {
    return logFailure("Selected Vulkan graphics queue cannot present");
  }
  VkSurfaceCapabilitiesKHR capabilities{};
  POD_VK(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_, surface_,
                                                   &capabilities));
  uint32_t formatCount = 0;
  POD_VK(vkGetPhysicalDeviceSurfaceFormatsKHR(physical_, surface_, &formatCount,
                                             nullptr));
  if (formatCount == 0) return logFailure("Vulkan surface has no formats");
  std::vector<VkSurfaceFormatKHR> formats(formatCount);
  POD_VK(vkGetPhysicalDeviceSurfaceFormatsKHR(
      physical_, surface_, &formatCount, formats.data()));
  VkSurfaceFormatKHR picked{};
  bool foundAlphaFormat = false;
  for (const auto& candidate : formats) {
    if (candidate.format == VK_FORMAT_R8G8B8A8_UNORM ||
        candidate.format == VK_FORMAT_B8G8R8A8_UNORM) {
      picked = candidate;
      foundAlphaFormat = true;
      break;
    }
  }
  if (!foundAlphaFormat) {
    return logFailure("Vulkan surface has no RGBA8 alpha-capable format");
  }
  VkFormatProperties destinationProperties{};
  vkGetPhysicalDeviceFormatProperties(physical_, picked.format,
                                      &destinationProperties);
  supportsLinearBlit_ =
      (destinationProperties.optimalTilingFeatures &
       VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;
  if (!supportsLinearBlit_) {
    return logFailure("Vulkan swapchain format does not support blit destination");
  }
  if (!(capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_DST_BIT)) {
    return logFailure("Vulkan surface does not support transfer destination");
  }

  format_ = picked.format;
  extent_ = capabilities.currentExtent.width == UINT32_MAX
                ? VkExtent2D{std::clamp(width_, capabilities.minImageExtent.width,
                                       capabilities.maxImageExtent.width),
                             std::clamp(height_,
                                       capabilities.minImageExtent.height,
                                       capabilities.maxImageExtent.height)}
                : capabilities.currentExtent;
  uint32_t imageCount = capabilities.minImageCount + 1;
  if (capabilities.maxImageCount) {
    imageCount = std::min(imageCount, capabilities.maxImageCount);
  }
  // TextureView.setOpaque(false) asks Android to composite this layer. The
  // render target stores premultiplied RGB, so never advertise postmultiplied
  // or opaque content. Android WSI commonly exposes INHERIT for this native
  // window path when PRE_MULTIPLIED is not selectable directly.
  VkCompositeAlphaFlagBitsKHR composite =
      VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
  if (!(capabilities.supportedCompositeAlpha & composite)) {
    if (capabilities.supportedCompositeAlpha &
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) {
      composite = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    } else {
      return logFailure(
          "Vulkan surface cannot present premultiplied-alpha UI content");
    }
  }
  __android_log_print(
      ANDROID_LOG_INFO, kLogTag,
      "Vulkan translucent swapchain format=%d compositeAlpha=0x%x supported=0x%x",
      static_cast<int>(picked.format), static_cast<unsigned>(composite),
      static_cast<unsigned>(capabilities.supportedCompositeAlpha));
  VkSwapchainCreateInfoKHR swapchain{
      VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
  swapchain.surface = surface_;
  swapchain.minImageCount = imageCount;
  swapchain.imageFormat = picked.format;
  swapchain.imageColorSpace = picked.colorSpace;
  swapchain.imageExtent = extent_;
  swapchain.imageArrayLayers = 1;
  swapchain.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  swapchain.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
  swapchain.preTransform = capabilities.currentTransform;
  swapchain.compositeAlpha = composite;
  swapchain.presentMode = VK_PRESENT_MODE_FIFO_KHR;
  swapchain.clipped = VK_TRUE;
  POD_VK(vkCreateSwapchainKHR(device_, &swapchain, nullptr, &swapchain_));
  POD_VK(vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr));
  images_.resize(imageCount);
  POD_VK(vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount,
                                 images_.data()));
  renderedPerImage_.resize(imageCount, VK_NULL_HANDLE);
  VkSemaphoreCreateInfo semaphore{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  for (VkSemaphore& rendered : renderedPerImage_) {
    POD_VK(vkCreateSemaphore(device_, &semaphore, nullptr, &rendered));
  }
  __android_log_print(ANDROID_LOG_INFO, "PodJSPerf",
                      "framePipeline slots=%u presentMode=FIFO minImages=%u "
                      "requestedImages=%u actualImages=%u maxImages=%u",
                      kFramesInFlight, capabilities.minImageCount,
                      swapchain.minImageCount, imageCount,
                      capabilities.maxImageCount);
  return true;
}

uint64_t VulkanRenderer::imageKey(const GpuImage& image) const {
  // Zero is a valid stable key for font slot 0, glyph 0. The decoder assigns
  // every image a stable key, so do not reinterpret it as "unset" here.
  return image.stableKey;
}

bool VulkanRenderer::createTextureResource(const GpuImage& source, uint64_t key,
                                           TextureResource* out) {
  if (!out || source.width == 0 || source.height == 0) {
    return logFailure("Invalid Vulkan texture dimensions");
  }
  TextureResource texture{};
  texture.key = key;
  texture.revision = source.revision;
  texture.lastUsedFrame = frameSerial_;
  texture.slot = source.slot;
  texture.width = source.width;
  texture.height = source.height;
  texture.linear = source.linear;
  texture.coverage = source.coverage;
  texture.ready = false;

  VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  image.imageType = VK_IMAGE_TYPE_2D;
  image.format = kRasterFormat;
  image.extent = {source.width, source.height, 1};
  image.mipLevels = 1;
  image.arrayLayers = 1;
  image.samples = VK_SAMPLE_COUNT_1_BIT;
  image.tiling = VK_IMAGE_TILING_OPTIMAL;
  image.usage =
      VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
  image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  image.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  VkResult result = vkCreateImage(device_, &image, nullptr, &texture.image);
  if (result != VK_SUCCESS) return logVkFailure("vkCreateImage(texture)", result);

  VkMemoryRequirements requirements{};
  vkGetImageMemoryRequirements(device_, texture.image, &requirements);
  const uint32_t memoryType =
      findMemoryType(requirements.memoryTypeBits,
                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (memoryType == UINT32_MAX) {
    destroyTexture(&texture);
    return logFailure("No device-local memory for Vulkan texture");
  }
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.allocationSize = requirements.size;
  allocation.memoryTypeIndex = memoryType;
  result = vkAllocateMemory(device_, &allocation, nullptr, &texture.memory);
  if (result == VK_SUCCESS) {
    result = vkBindImageMemory(device_, texture.image, texture.memory, 0);
  }
  VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  view.image = texture.image;
  view.viewType = VK_IMAGE_VIEW_TYPE_2D;
  view.format = kRasterFormat;
  view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  if (result == VK_SUCCESS) {
    result = vkCreateImageView(device_, &view, nullptr, &texture.view);
  }
  if (result == VK_SUCCESS) {
    VkDescriptorSetAllocateInfo descriptor{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    descriptor.descriptorPool = descriptorPool_;
    std::array<VkDescriptorSetLayout, kFramesInFlight> layouts;
    layouts.fill(descriptorLayout_);
    descriptor.descriptorSetCount = kFramesInFlight;
    descriptor.pSetLayouts = layouts.data();
    result = vkAllocateDescriptorSets(device_, &descriptor,
                                      texture.descriptors.data());
  }
  if (result != VK_SUCCESS) {
    destroyTexture(&texture);
    if (result == VK_ERROR_OUT_OF_POOL_MEMORY ||
        result == VK_ERROR_FRAGMENTED_POOL) {
      return logFailure(
          "Vulkan texture descriptor capacity exhausted (4096 entries)");
    }
    return logVkFailure("create Vulkan texture resource", result);
  }

  VkDescriptorImageInfo imageInfo{};
  imageInfo.sampler = source.linear ? linearSampler_ : nearestSampler_;
  imageInfo.imageView = texture.view;
  imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  for (uint32_t i = 0; i < kFramesInFlight; ++i) {
    VkDescriptorBufferInfo bufferInfo{};
    bufferInfo.buffer = frames_[i].clipBuffer;
    bufferInfo.range =
        VkDeviceSize{kRoundedClipCapacity} * sizeof(podjs_gpu::RoundedClipData);
    VkWriteDescriptorSet writes[2]{};
    writes[0] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    writes[0].dstSet = texture.descriptors[i];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &imageInfo;
    writes[1] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    writes[1].dstSet = texture.descriptors[i];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &bufferInfo;
    vkUpdateDescriptorSets(device_, 2, writes, 0, nullptr);
  }
  *out = texture;
  return true;
}

bool VulkanRenderer::prepareImageUploads(
    const std::vector<GpuImage>& frameImages,
    std::vector<PendingTextureUpload>* pendingUploads) {
  if (!pendingUploads) return false;
  const auto uploadBegan = std::chrono::steady_clock::now();
  pendingUploads->clear();
  lastSubmitPerf_.uploadBytes = 0;
  lastSubmitPerf_.uploadCount = 0;

  GpuImage white{};
  white.width = 1;
  white.height = 1;
  white.revision = 1;
  white.stableKey = kWhiteTextureKey;
  white.rgba = std::make_shared<const std::vector<uint8_t>>(
      std::vector<uint8_t>{255, 255, 255, 255});
  std::vector<const GpuImage*> requested;
  requested.reserve(frameImages.size() + 1);
  requested.push_back(&white);
  for (const auto& image : frameImages) requested.push_back(&image);

  struct Pending {
    const GpuImage* source{};
    uint64_t key{};
    VkDeviceSize offset{};
  };
  std::vector<Pending> pending;
  std::unordered_set<uint64_t> pendingKeys;
  std::unordered_set<uint64_t> requestedKeys;
  VkDeviceSize stagingBytes = 0;
  size_t pendingTextureBytes = 0;
  for (const GpuImage* source : requested) {
    const uint64_t key =
        source == &white ? kWhiteTextureKey : imageKey(*source);
    if (source->width == 0 || source->height == 0 ||
        source->width > std::numeric_limits<size_t>::max() / source->height ||
        size_t{source->width} * source->height >
            std::numeric_limits<size_t>::max() / 4) {
      return logFailure("Decoded GPU image has invalid dimensions");
    }
    const size_t expected = size_t{source->width} * source->height * 4;
    if (source->width == 0 || source->height == 0 ||
        !source->rgba || source->rgba->size() != expected) {
      return logFailure("Decoded GPU image has invalid RGBA byte length");
    }
    requestedKeys.insert(key);
    auto existing = textureCache_.find(key);
    if (existing != textureCache_.end() &&
        existing->second.revision == source->revision &&
        existing->second.width == source->width &&
        existing->second.height == source->height &&
        existing->second.linear == source->linear &&
        existing->second.ready) {
      existing->second.lastUsedFrame = frameSerial_;
      continue;
    }
    if (pendingKeys.find(key) != pendingKeys.end()) continue;
    if (existing != textureCache_.end()) {
      retireTexture(&existing->second);
      textureCache_.erase(existing);
    }
    stagingBytes = align4(stagingBytes);
    if (expected > kTextureCacheByteCapacity ||
        pendingTextureBytes > kTextureCacheByteCapacity - expected ||
        stagingBytes > std::numeric_limits<VkDeviceSize>::max() - expected) {
      return gpuFailureOnce(
          "current frame texture uploads exceed the 32 MiB cache budget");
    }
    pending.push_back({source, key, stagingBytes});
    pendingKeys.insert(key);
    stagingBytes += expected;
    pendingTextureBytes += expected;
  }
  if (pending.empty()) {
    lastSubmitPerf_.uploadMs = 0;
    return true;
  }
  lastSubmitPerf_.uploadBytes = pendingTextureBytes;
  lastSubmitPerf_.uploadCount = static_cast<uint32_t>(pending.size());

  collectRetiredTextures();
  size_t cachedTextureBytes = 0;
  for (const auto &texture : retiredTextures_)
    cachedTextureBytes += size_t{texture.width} * texture.height * 4;
  for (const auto& entry : textureCache_) {
    cachedTextureBytes +=
        size_t{entry.second.width} * entry.second.height * 4;
  }
  while (textureCache_.size() + retiredTextures_.size() + pending.size() >
             kTextureCacheCapacity ||
         cachedTextureBytes + pendingTextureBytes > kTextureCacheByteCapacity) {
    auto victim = textureCache_.end();
    for (auto candidate = textureCache_.begin();
         candidate != textureCache_.end(); ++candidate) {
      if (requestedKeys.find(candidate->first) != requestedKeys.end() ||
          textureInFlight(candidate->second))
        continue;
      if (victim == textureCache_.end() ||
          candidate->second.lastUsedFrame < victim->second.lastUsedFrame) {
        victim = candidate;
      }
    }
    if (victim == textureCache_.end()) {
      return gpuFailureOnce(
          "current frame exceeds texture cache budget (1024 entries / 32 MiB)");
    }
    cachedTextureBytes -=
        size_t{victim->second.width} * victim->second.height * 4;
    destroyTexture(&victim->second);
    textureCache_.erase(victim);
  }
  if ((textureCache_.size() + retiredTextures_.size() + pending.size()) *
          kFramesInFlight >
      kDescriptorCapacity) {
    return gpuFailureOnce(
        "texture cache reached descriptor capacity (4096 entries)");
  }
  if (!ensureStagingCapacity(stagingBytes)) return false;

  for (const Pending& upload : pending) {
    std::memcpy(static_cast<uint8_t *>(frame().stagingMapped) + upload.offset,
                upload.source->rgba->data(), upload.source->rgba->size());
    TextureResource texture{};
    if (!createTextureResource(*upload.source, upload.key, &texture)) {
      return false;
    }
    textureCache_.emplace(upload.key, std::move(texture));
    pendingUploads->push_back({upload.key, upload.offset});
  }
  lastSubmitPerf_.uploadMs = std::chrono::duration<double, std::milli>(
      std::chrono::steady_clock::now() - uploadBegan).count();
  return true;
}

void VulkanRenderer::recordImageUploads(
    VkCommandBuffer command,
    const std::vector<PendingTextureUpload>& uploads) {
  for (const PendingTextureUpload& upload : uploads) {
    TextureResource& texture = textureCache_.at(upload.key);
    VkImageMemoryBarrier toCopy{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    toCopy.srcAccessMask = 0;
    toCopy.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toCopy.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toCopy.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toCopy.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toCopy.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toCopy.image = texture.image;
    toCopy.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                         nullptr, 1, &toCopy);
    VkBufferImageCopy copy{};
    copy.bufferOffset = upload.offset;
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {texture.width, texture.height, 1};
    vkCmdCopyBufferToImage(command, frame().stagingBuffer, texture.image,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    VkImageMemoryBarrier toShader = toCopy;
    toShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toShader.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toShader.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr,
                         0, nullptr, 1, &toShader);
  }
}

VulkanRenderer::TextureResource* VulkanRenderer::textureForPrimitive(
    const GpuPrimitive& primitive) {
  if (primitive.type == GpuPrimitiveType::Rect ||
      primitive.type == GpuPrimitiveType::GradientRect ||
      primitive.type == GpuPrimitiveType::Tri) {
    auto white = textureCache_.find(kWhiteTextureKey);
    return white == textureCache_.end() ? nullptr : &white->second;
  }
  auto exact = textureCache_.find(primitive.textureKey);
  return exact == textureCache_.end() ? nullptr : &exact->second;
}

bool VulkanRenderer::textureInFlight(const TextureResource &texture) const {
  for (const auto &slot : frames_)
    if (slot.pending && slot.textures.count(texture.image))
      return true;
  return false;
}

void VulkanRenderer::retireTexture(TextureResource *texture) {
  if (textureInFlight(*texture)) {
    retiredTextures_.push_back(*texture);
    *texture = {};
  } else {
    destroyTexture(texture);
  }
}

void VulkanRenderer::collectRetiredTextures() {
  for (auto it = retiredTextures_.begin(); it != retiredTextures_.end();) {
    if (textureInFlight(*it)) {
      ++it;
      continue;
    }
    destroyTexture(&*it);
    it = retiredTextures_.erase(it);
  }
}

void VulkanRenderer::releaseFrame(uint32_t index) {
  auto &slot = frames_[index];
  slot.pending = slot.acquiredImage = false;
  slot.images.clear();
  slot.textures.clear();
  slot.uploadKeys.clear();
  decodeCache_.releaseFrame(index);
}

bool VulkanRenderer::waitForFrameFence() {
  const auto began = std::chrono::steady_clock::now();
  auto timing = diagnosticScope(podjs_gpu::RendererTimingStage::FrameFenceWait,
                                {.flags = frameIndex_});
  lastSubmitPerf_.slotIndex = frameIndex_;
  lastSubmitPerf_.waitedForSlot = frame().pending;
  const VkResult result =
      frame().pending
          ? vkWaitForFences(device_, 1, &frame().fence, VK_TRUE, UINT64_MAX)
          : VK_SUCCESS;
  lastSubmitPerf_.frameFenceMs = std::chrono::duration<double, std::milli>(
                                     std::chrono::steady_clock::now() - began)
                                     .count();
  timing.finish(result == VK_SUCCESS, result);
  if (result != VK_SUCCESS) {
    valid_ = false;
    return logVkFailure("vkWaitForFences(slot)", result);
  }
  releaseFrame(frameIndex_);
  collectRetiredTextures();
  return true;
}

bool VulkanRenderer::acquireFrame(uint32_t *imageIndex) {
  if (!valid_ || !imageIndex) return false;
  const auto began = std::chrono::steady_clock::now();
  auto timing = diagnosticScope(podjs_gpu::RendererTimingStage::AcquireImage,
                                {.flags = frameIndex_});
  const VkResult result =
      vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, frame().acquired,
                            VK_NULL_HANDLE, imageIndex);
  lastSubmitPerf_.acquireMs = std::chrono::duration<double, std::milli>(
                                  std::chrono::steady_clock::now() - began)
                                  .count();
  timing.finish(result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR, result);
  if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_ERROR_SURFACE_LOST_KHR)
    return rebuildSurfaceAfterError("vkAcquireNextImageKHR", result);
  if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR)
    return logVkFailure("vkAcquireNextImageKHR", result);
  frame().acquiredImage = true;
  return true;
}

// A failed recording after acquire must consume the acquire semaphore and
// release the unpresented image via swapchain recreation before CPU fallback.
void VulkanRenderer::abortFrame(uint32_t index) {
  auto &slot = frames_[index];
  if (slot.pending)
    return; // Successful submits retain all resources.
  if (slot.acquiredImage) {
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo drain{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    drain.waitSemaphoreCount = 1;
    drain.pWaitSemaphores = &slot.acquired;
    drain.pWaitDstStageMask = &stage;
    VkResult result = vkResetFences(device_, 1, &slot.fence);
    if (result == VK_SUCCESS)
      result = vkQueueSubmit(queue_, 1, &drain, slot.fence);
    if (result == VK_SUCCESS)
      result = vkWaitForFences(device_, 1, &slot.fence, VK_TRUE, UINT64_MAX);
    if (result != VK_SUCCESS) {
      valid_ = false;
      logVkFailure("drain aborted Vulkan acquire", result);
    } else {
      rebuildSurfaceAfterError("aborted frame", VK_ERROR_OUT_OF_DATE_KHR);
    }
  }
  releaseFrame(index);
  collectRetiredTextures();
}

bool VulkanRenderer::appendSwapchainBlit(VkCommandBuffer command,
                                         uint32_t imageIndex) {
  if (!supportsLinearBlit_) return false;
  VkImageMemoryBarrier swapToBlit{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  swapToBlit.srcAccessMask = 0;
  swapToBlit.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  swapToBlit.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  swapToBlit.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  swapToBlit.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  swapToBlit.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  swapToBlit.image = images_[imageIndex];
  swapToBlit.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &swapToBlit);

  VkImageBlit blit{};
  blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  blit.srcOffsets[1] = {static_cast<int32_t>(rasterWidth_),
                        static_cast<int32_t>(rasterHeight_), 1};
  blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  blit.dstOffsets[1] = {static_cast<int32_t>(extent_.width),
                        static_cast<int32_t>(extent_.height), 1};
  vkCmdBlitImage(command, frame().rasterImage,
                 VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, images_[imageIndex],
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                 VK_FILTER_LINEAR);

  VkImageMemoryBarrier swapToPresent = swapToBlit;
  swapToPresent.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  swapToPresent.dstAccessMask = 0;
  swapToPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  swapToPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &swapToPresent);
  return true;
}

bool VulkanRenderer::submitAndPresent(uint32_t imageIndex,
                                      VkPipelineStageFlags waitStage) {
  if (imageIndex >= renderedPerImage_.size() ||
      !renderedPerImage_[imageIndex]) {
    return logFailure("Missing render-finished semaphore for swapchain image");
  }
  const uint32_t slotIndex = frameIndex_;
  auto &slot = frame();
  VkSemaphore rendered = renderedPerImage_[imageIndex];
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.waitSemaphoreCount = 1;
  submit.pWaitSemaphores = &slot.acquired;
  submit.pWaitDstStageMask = &waitStage;
  submit.commandBufferCount = 1;
  submit.pCommandBuffers = &slot.command;
  submit.signalSemaphoreCount = 1;
  submit.pSignalSemaphores = &rendered;
  POD_VK(vkResetFences(device_, 1, &slot.fence));
  auto submitTiming = diagnosticScope(
      podjs_gpu::RendererTimingStage::FrameSubmit, {.flags = slotIndex});
  const auto submitBegan = std::chrono::steady_clock::now();
  VkResult result = vkQueueSubmit(queue_, 1, &submit, slot.fence);
  const auto submitDone = std::chrono::steady_clock::now();
  submitTiming.finish(result == VK_SUCCESS, result);
  lastSubmitPerf_.queueSubmitMs =
      std::chrono::duration<double, std::milli>(submitDone - submitBegan).count();
  if (result != VK_SUCCESS) {
    return logVkFailure("vkQueueSubmit(frame)", result);
  }
  slot.pending = true;
  slot.acquiredImage =
      false; // Enqueued wait consumes it before the slot fence.
  decodeCache_.pinFrame(slotIndex, slot.images);
  for (uint64_t key : slot.uploadKeys)
    textureCache_.at(key).ready = true;
  frameIndex_ = (frameIndex_ + 1) % kFramesInFlight;
  for (const auto &pending : frames_)
    if (pending.pending)
      ++lastSubmitPerf_.inFlightFrames;
  VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
  present.waitSemaphoreCount = 1;
  present.pWaitSemaphores = &rendered;
  present.swapchainCount = 1;
  present.pSwapchains = &swapchain_;
  present.pImageIndices = &imageIndex;
  auto presentTiming =
      diagnosticScope(podjs_gpu::RendererTimingStage::Present,
                      {.count = imageIndex, .flags = slotIndex});
  const auto presentBegan = std::chrono::steady_clock::now();
  result = vkQueuePresentKHR(queue_, &present);
  presentTiming.finish(result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR,
                       result);
  lastSubmitPerf_.presentWaitMs = std::chrono::duration<double, std::milli>(
                                      std::chrono::steady_clock::now() -
                                      presentBegan)
                                      .count();
  if (result == VK_ERROR_OUT_OF_DATE_KHR ||
      result == VK_ERROR_SURFACE_LOST_KHR) {
    return rebuildSurfaceAfterError("vkQueuePresentKHR", result);
  }
  if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR)
    return rebuildSurfaceAfterError("vkQueuePresentKHR", result);
  return true;
}

bool VulkanRenderer::rebuildSurfaceAfterError(const char* operation,
                                              VkResult result) {
  __android_log_print(ANDROID_LOG_WARN, kLogTag,
                      "%s returned VkResult=%d; rebuilding surface", operation,
                      static_cast<int>(result));
  ANativeWindow* retainedWindow = window_;
  if (!retainedWindow) {
    valid_ = false;
    lastHash_ = 0;
    return false;
  }
  ANativeWindow_acquire(retainedWindow);
  const uint32_t retainedWidth = width_;
  const uint32_t retainedHeight = height_;
  replaceWindow(retainedWindow, retainedWidth, retainedHeight);
  ANativeWindow_release(retainedWindow);
  return false;
}

bool VulkanRenderer::submitRgba(const uint8_t* pixels, size_t byteLength,
                                uint64_t contentHash) {
  if (!valid_ || contentHash == lastHash_) return false;
  if (!pixels || byteLength != rasterBytes()) {
    return logFailure("Invalid CPU raster frame submitted to Vulkan");
  }
  lastSubmitPerf_ = {};
  if (!waitForFrameFence())
    return false;
  const uint32_t slotIndex = frameIndex_;
  OnExit cleanup{[&] { abortFrame(slotIndex); }};
  const auto uploadBegan = std::chrono::steady_clock::now();
  if (!ensureStagingCapacity(byteLength))
    return false;
  std::memcpy(frame().stagingMapped, pixels, byteLength);
  lastSubmitPerf_.uploadMs = std::chrono::duration<double, std::milli>(
                                 std::chrono::steady_clock::now() - uploadBegan)
                                 .count();
  lastSubmitPerf_.uploadBytes = byteLength;
  lastSubmitPerf_.uploadCount = 1;

  uint32_t imageIndex = 0;
  if (!acquireFrame(&imageIndex)) return false;
  const auto recordBegan = std::chrono::steady_clock::now();
  VkCommandBuffer command = frame().command;
  POD_VK(vkResetCommandBuffer(command, 0));
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  POD_VK(vkBeginCommandBuffer(command, &begin));
  VkImageMemoryBarrier rasterToCopy{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  rasterToCopy.srcAccessMask = 0;
  rasterToCopy.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  rasterToCopy.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  rasterToCopy.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  rasterToCopy.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  rasterToCopy.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  rasterToCopy.image = frame().rasterImage;
  rasterToCopy.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &rasterToCopy);
  VkBufferImageCopy copy{};
  copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  copy.imageExtent = {rasterWidth_, rasterHeight_, 1};
  vkCmdCopyBufferToImage(command, frame().stagingBuffer, frame().rasterImage,
                         VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
  VkImageMemoryBarrier rasterToBlit = rasterToCopy;
  rasterToBlit.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  rasterToBlit.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  rasterToBlit.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  rasterToBlit.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                       nullptr, 1, &rasterToBlit);
  if (!appendSwapchainBlit(command, imageIndex)) return false;
  POD_VK(vkEndCommandBuffer(command));
  lastSubmitPerf_.recordMs = std::chrono::duration<double, std::milli>(
                                 std::chrono::steady_clock::now() - recordBegan)
                                 .count();
  if (!submitAndPresent(imageIndex, VK_PIPELINE_STAGE_TRANSFER_BIT)) return false;
  lastHash_ = contentHash;
  return true;
}

bool VulkanRenderer::submitDrawList(PodRuntime* runtime,
                                    const PodDrawList& list) {
  if (!valid_ || list.content_hash == lastHash_) return false;
  lastSubmitPerf_ = {};
  if (!waitForFrameFence())
    return false;
  const uint32_t slotIndex = frameIndex_;
  OnExit cleanup{[&] { abortFrame(slotIndex); }};
  const auto submitBegan = std::chrono::steady_clock::now();
  auto decodeTiming = diagnosticScope(podjs_gpu::RendererTimingStage::Decode,
                                      {.flags = slotIndex});
  GpuFrame decodedFrame;
  std::string decodeError;
  if (!decodeGpuDrawList(runtime, list, kRasterScale, &decodedFrame,
                         &decodeCache_, &decodeError)) {
    return gpuFailureOnce("decode failed: " + decodeError);
  }
  decodeTiming.finish(true, 0);
  const auto decoded = std::chrono::steady_clock::now();
  lastSubmitPerf_.decodeMs =
      std::chrono::duration<double, std::milli>(decoded - submitBegan).count();
  if (decodedFrame.roundedClips.size() > kRoundedClipCapacity) {
    return gpuFailureOnce("rounded clip table exceeds Vulkan buffer capacity");
  }
  for (const auto &primitive : decodedFrame.primitives) {
    if (primitive.type != GpuPrimitiveType::Tri &&
        primitive.type != GpuPrimitiveType::TexTri) {
      continue;
    }
    for (const auto& vertex : primitive.vertex) {
      if (vertex.x < -8191 || vertex.x > 8191 || vertex.y < -8191 ||
          vertex.y > 8191) {
        return gpuFailureOnce(
            "triangle coordinate exceeds safe 32-bit shader edge range");
      }
    }
  }
  ++frameSerial_;
  std::vector<PendingTextureUpload> pendingUploads;
  if (!prepareImageUploads(decodedFrame.images, &pendingUploads))
    return false;
  for (const auto &primitive : decodedFrame.primitives) {
    if (!textureForPrimitive(primitive)) {
      return gpuFailureOnce(
          "primitive texture missing for type " +
          std::to_string(static_cast<unsigned>(primitive.type)) + " slot " +
          std::to_string(primitive.textureSlot) + " key " +
          std::to_string(primitive.textureKey));
    }
  }

  frame().images = decodedFrame.images;
  for (const auto &primitive : decodedFrame.primitives)
    frame().textures.insert(textureForPrimitive(primitive)->image);
  for (const auto &upload : pendingUploads) {
    frame().textures.insert(textureCache_.at(upload.key).image);
    frame().uploadKeys.push_back(upload.key);
  }
  auto *clipData =
      static_cast<podjs_gpu::RoundedClipData *>(frame().clipMapped);
  for (size_t i = 0; i < decodedFrame.roundedClips.size(); ++i) {
    clipData[i] = podjs_gpu::makeRoundedClipData(decodedFrame.roundedClips[i]);
  }
  uint32_t imageIndex = 0;
  if (!acquireFrame(&imageIndex))
    return false;
  const auto recordBegan = std::chrono::steady_clock::now();
  VkCommandBuffer command = frame().command;
  POD_VK(vkResetCommandBuffer(command, 0));
  VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  POD_VK(vkBeginCommandBuffer(command, &begin));
  recordImageUploads(command, pendingUploads);

  VkClearValue clear{};
  clear.color.float32[0] = 0.0f;
  clear.color.float32[1] = 0.0f;
  clear.color.float32[2] = 0.0f;
  clear.color.float32[3] = 0.0f;
  VkRenderPassBeginInfo render{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
  render.renderPass = renderPass_;
  render.framebuffer = frame().framebuffer;
  render.renderArea.extent = {rasterWidth_, rasterHeight_};
  render.clearValueCount = 1;
  render.pClearValues = &clear;
  vkCmdBeginRenderPass(command, &render, VK_SUBPASS_CONTENTS_INLINE);
  vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
  VkViewport viewport{};
  viewport.width = static_cast<float>(rasterWidth_);
  viewport.height = static_cast<float>(rasterHeight_);
  viewport.minDepth = 0.0f;
  viewport.maxDepth = 1.0f;
  vkCmdSetViewport(command, 0, 1, &viewport);

  for (const auto &primitive : decodedFrame.primitives) {
    const int32_t x0 = std::clamp(primitive.clip.x0, 0,
                                  static_cast<int32_t>(rasterWidth_));
    const int32_t y0 = std::clamp(primitive.clip.y0, 0,
                                  static_cast<int32_t>(rasterHeight_));
    const int32_t x1 = std::clamp(primitive.clip.x1, 0,
                                  static_cast<int32_t>(rasterWidth_));
    const int32_t y1 = std::clamp(primitive.clip.y1, 0,
                                  static_cast<int32_t>(rasterHeight_));
    if (x1 <= x0 || y1 <= y0) continue;
    VkRect2D scissor{{x0, y0},
                     {static_cast<uint32_t>(x1 - x0),
                      static_cast<uint32_t>(y1 - y0)}};
    vkCmdSetScissor(command, 0, 1, &scissor);

    TextureResource* texture = textureForPrimitive(primitive);
    texture->lastUsedFrame = frameSerial_;
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_GRAPHICS,
                            pipelineLayout_, 0, 1,
                            &texture->descriptors[slotIndex], 0, nullptr);
    const podjs_gpu::PrimitivePush push = podjs_gpu::makePrimitivePush(
        primitive, rasterWidth_, rasterHeight_, texture->linear);
    vkCmdPushConstants(command, pipelineLayout_,
                       VK_SHADER_STAGE_VERTEX_BIT |
                           VK_SHADER_STAGE_FRAGMENT_BIT,
                       0, sizeof(push), &push);
    vkCmdDraw(command, 6, 1, 0, 0);
  }
  vkCmdEndRenderPass(command);
  if (!appendSwapchainBlit(command, imageIndex)) return false;
  POD_VK(vkEndCommandBuffer(command));
  lastSubmitPerf_.recordMs = std::chrono::duration<double, std::milli>(
                                 std::chrono::steady_clock::now() - recordBegan)
                                 .count();
  if (!submitAndPresent(imageIndex, VK_PIPELINE_STAGE_TRANSFER_BIT)) {
    return false;
  }
  lastHash_ = list.content_hash;
  return true;
}

void VulkanRenderer::destroyTexture(TextureResource* texture) {
  if (!device_ || !texture) return;
  for (VkDescriptorSet descriptor : texture->descriptors)
    if (descriptor && descriptorPool_)
      vkFreeDescriptorSets(device_, descriptorPool_, 1, &descriptor);
  if (texture->view) vkDestroyImageView(device_, texture->view, nullptr);
  if (texture->image) vkDestroyImage(device_, texture->image, nullptr);
  if (texture->memory) vkFreeMemory(device_, texture->memory, nullptr);
  *texture = {};
}

void VulkanRenderer::destroySwapchain() {
  if (!device_) return;
  images_.clear();
  for (VkSemaphore rendered : renderedPerImage_) {
    if (rendered) vkDestroySemaphore(device_, rendered, nullptr);
  }
  renderedPerImage_.clear();
  if (swapchain_) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
  swapchain_ = VK_NULL_HANDLE;
}

void VulkanRenderer::destroySurface() {
  destroySwapchain();
  if (surface_) vkDestroySurfaceKHR(instance_, surface_, nullptr);
  surface_ = VK_NULL_HANDLE;
  if (window_) ANativeWindow_release(window_);
  window_ = nullptr;
}

void VulkanRenderer::destroyGraphicsResources() {
  if (!device_) return;
  for (auto& entry : textureCache_) destroyTexture(&entry.second);
  textureCache_.clear();
  if (pipeline_) vkDestroyPipeline(device_, pipeline_, nullptr);
  if (pipelineLayout_)
    vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
  for (auto &slot : frames_) {
    if (slot.framebuffer)
      vkDestroyFramebuffer(device_, slot.framebuffer, nullptr);
    slot.framebuffer = VK_NULL_HANDLE;
  }
  if (renderPass_) vkDestroyRenderPass(device_, renderPass_, nullptr);
  if (linearSampler_) vkDestroySampler(device_, linearSampler_, nullptr);
  if (nearestSampler_) vkDestroySampler(device_, nearestSampler_, nullptr);
  if (descriptorPool_)
    vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
  if (descriptorLayout_)
    vkDestroyDescriptorSetLayout(device_, descriptorLayout_, nullptr);
  pipeline_ = VK_NULL_HANDLE;
  pipelineLayout_ = VK_NULL_HANDLE;
  renderPass_ = VK_NULL_HANDLE;
  linearSampler_ = VK_NULL_HANDLE;
  nearestSampler_ = VK_NULL_HANDLE;
  descriptorPool_ = VK_NULL_HANDLE;
  descriptorLayout_ = VK_NULL_HANDLE;
}

void VulkanRenderer::destroyUploadResources() {
  if (!device_) return;
  for (auto &slot : frames_) {
    if (slot.stagingMapped) {
      vkUnmapMemory(device_, slot.stagingMemory);
      slot.stagingMapped = nullptr;
    }
    if (slot.rasterView)
      vkDestroyImageView(device_, slot.rasterView, nullptr);
    if (slot.rasterImage)
      vkDestroyImage(device_, slot.rasterImage, nullptr);
    if (slot.rasterMemory)
      vkFreeMemory(device_, slot.rasterMemory, nullptr);
    if (slot.stagingBuffer)
      vkDestroyBuffer(device_, slot.stagingBuffer, nullptr);
    if (slot.stagingMemory)
      vkFreeMemory(device_, slot.stagingMemory, nullptr);
    slot.rasterView = VK_NULL_HANDLE;
    slot.rasterImage = VK_NULL_HANDLE;
    slot.rasterMemory = VK_NULL_HANDLE;
    slot.stagingBuffer = VK_NULL_HANDLE;
    slot.stagingMemory = VK_NULL_HANDLE;
    slot.stagingCapacity = 0;
  }
}

void VulkanRenderer::destroyClipResources() {
  if (!device_) return;
  for (auto &slot : frames_) {
    if (slot.clipMapped)
      vkUnmapMemory(device_, slot.clipMemory);
    if (slot.clipBuffer)
      vkDestroyBuffer(device_, slot.clipBuffer, nullptr);
    if (slot.clipMemory)
      vkFreeMemory(device_, slot.clipMemory, nullptr);
    slot.clipMapped = nullptr;
    slot.clipBuffer = VK_NULL_HANDLE;
    slot.clipMemory = VK_NULL_HANDLE;
  }
}

VulkanRenderer::~VulkanRenderer() {
  if (device_) vkDeviceWaitIdle(device_);
  for (uint32_t i = 0; i < kFramesInFlight; ++i)
    releaseFrame(i);
  collectRetiredTextures();
  destroySurface();
  destroyGraphicsResources();
  destroyClipResources();
  destroyUploadResources();
  if (device_) {
    for (auto &slot : frames_) {
      if (slot.fence)
        vkDestroyFence(device_, slot.fence, nullptr);
      if (slot.acquired)
        vkDestroySemaphore(device_, slot.acquired, nullptr);
    }
    if (commandPool_) vkDestroyCommandPool(device_, commandPool_, nullptr);
    vkDestroyDevice(device_, nullptr);
  }
  if (instance_) vkDestroyInstance(instance_, nullptr);
}
