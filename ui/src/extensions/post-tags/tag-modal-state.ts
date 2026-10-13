import { createApp, ref, shallowRef, type App } from "vue";
import TagGenerationModal from "./TagGenerationModal.vue";

export interface TagResource {
  metadata: { name: string };
  spec: { displayName: string };
}

export interface ApplyResult {
  tags: string[];
  added: number;
  tagResources: TagResource[];
}

export interface TagModalTarget {
  postName: string;
  title: string;
  currentTags: string[];
  onApplied: (result: ApplyResult) => void;
}

const visible = ref(false);
const target = shallowRef<TagModalTarget | null>(null);
let app: App | null = null;
let container: HTMLDivElement | null = null;

function ensureMounted() {
  if (app) return;
  container = document.createElement("div");
  container.id = "ai-tag-modal-root";
  document.body.appendChild(container);
  app = createApp(TagGenerationModal);
  app.mount(container);
}

export function openTagModal(next: TagModalTarget) {
  ensureMounted();
  target.value = next;
  visible.value = true;
}

export function closeTagModal() {
  visible.value = false;
}

export function disposeTagModal() {
  visible.value = false;
  target.value = null;
  app?.unmount();
  container?.remove();
  app = null;
  container = null;
}

export function useTagModalState() {
  return { visible, target };
}
