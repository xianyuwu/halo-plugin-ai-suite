<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";

const visible = ref(false);
const src = ref("");
const alt = ref("");
const scale = ref(1);
const translateX = ref(0);
const translateY = ref(0);
const dragging = ref(false);
let dragStartX = 0;
let dragStartY = 0;
let dragOriginX = 0;
let dragOriginY = 0;

const imageStyle = computed(() => ({
  transform: `translate(${translateX.value}px, ${translateY.value}px) scale(${scale.value})`,
}));

function isImageLink(target: EventTarget | null): target is HTMLAnchorElement {
  if (!(target instanceof Element)) return false;
  const anchor = target.closest(".vp-doc a");
  if (!(anchor instanceof HTMLAnchorElement)) return false;
  const image = anchor.querySelector(":scope > img");
  if (!(image instanceof HTMLImageElement)) return false;
  return /\.(svg|png|jpe?g|webp|gif)(?:$|[?#])/i.test(anchor.href)
    || /\.(svg|png|jpe?g|webp|gif)(?:$|[?#])/i.test(image.currentSrc || image.src);
}

function resetView() {
  scale.value = 1;
  translateX.value = 0;
  translateY.value = 0;
}

function openFrom(anchor: HTMLAnchorElement) {
  const image = anchor.querySelector(":scope > img") as HTMLImageElement | null;
  src.value = image?.currentSrc || image?.src || anchor.href;
  alt.value = image?.alt || "文档图片预览";
  resetView();
  visible.value = true;
  document.documentElement.classList.add("docs-lightbox-open");
  nextTick(() => {
    document.querySelector<HTMLButtonElement>(".docs-lightbox__close")?.focus();
  });
}

function close() {
  visible.value = false;
  dragging.value = false;
  document.documentElement.classList.remove("docs-lightbox-open");
}

function onDocumentClick(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return;
  }
  const anchor = isImageLink(event.target) ? event.target.closest(".vp-doc a") as HTMLAnchorElement : null;
  if (!anchor) return;
  event.preventDefault();
  openFrom(anchor);
}

function onKeydown(event: KeyboardEvent) {
  if (!visible.value) return;
  if (event.key === "Escape") close();
  if (event.key === "0") resetView();
  if (event.key === "+" || event.key === "=") zoom(0.2);
  if (event.key === "-") zoom(-0.2);
}

function zoom(delta: number) {
  scale.value = Math.min(3, Math.max(0.6, Number((scale.value + delta).toFixed(2))));
  if (scale.value === 1) {
    translateX.value = 0;
    translateY.value = 0;
  }
}

function onWheel(event: WheelEvent) {
  event.preventDefault();
  zoom(event.deltaY > 0 ? -0.12 : 0.12);
}

function startDrag(event: PointerEvent) {
  if (scale.value <= 1) return;
  dragging.value = true;
  dragStartX = event.clientX;
  dragStartY = event.clientY;
  dragOriginX = translateX.value;
  dragOriginY = translateY.value;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function onDrag(event: PointerEvent) {
  if (!dragging.value) return;
  translateX.value = dragOriginX + event.clientX - dragStartX;
  translateY.value = dragOriginY + event.clientY - dragStartY;
}

function stopDrag() {
  dragging.value = false;
}

onMounted(() => {
  document.addEventListener("click", onDocumentClick);
  document.addEventListener("keydown", onKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener("click", onDocumentClick);
  document.removeEventListener("keydown", onKeydown);
  document.documentElement.classList.remove("docs-lightbox-open");
});
</script>

<template>
  <Teleport to="body">
    <div
      v-if="visible"
      class="docs-lightbox"
      role="dialog"
      aria-modal="true"
      :aria-label="alt"
      @click.self="close"
    >
      <div class="docs-lightbox__toolbar">
        <span class="docs-lightbox__title">{{ alt }}</span>
        <div class="docs-lightbox__actions">
          <button type="button" @click="zoom(-0.2)">缩小</button>
          <button type="button" @click="resetView">重置</button>
          <button type="button" @click="zoom(0.2)">放大</button>
          <a :href="src" target="_blank" rel="noreferrer">新窗口打开</a>
          <button type="button" class="docs-lightbox__close" @click="close" aria-label="关闭预览">关闭</button>
        </div>
      </div>
      <div
        class="docs-lightbox__stage"
        :class="{ 'is-dragging': dragging, 'is-zoomed': scale > 1 }"
        @wheel="onWheel"
        @pointerdown="startDrag"
        @pointermove="onDrag"
        @pointerup="stopDrag"
        @pointercancel="stopDrag"
      >
        <img :src="src" :alt="alt" :style="imageStyle" draggable="false">
      </div>
    </div>
  </Teleport>
</template>
