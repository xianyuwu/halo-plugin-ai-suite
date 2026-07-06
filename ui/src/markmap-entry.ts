import MarkdownIt from "markdown-it";
import { buildTree } from "markmap-html-parser";
import { Markmap } from "markmap-view";

type MarkmapNode = {
  content: string;
  children: MarkmapNode[];
  [key: string]: unknown;
};

/**
 * Local-only Markdown transformer for the mind-map widget.
 *
 * The upstream markmap-lib default transformer includes optional Highlight.js and KaTeX
 * plugins whose asset resolver may fetch scripts and styles from jsDelivr or unpkg. The AI suite
 * only needs headings, lists and inline Markdown, so this deliberately small transformer avoids
 * all remote asset loaders and keeps visitor rendering fully self-contained.
 */
class LocalTransformer {
  private readonly markdown = new MarkdownIt({
    breaks: true,
    html: false,
    linkify: false,
  });

  transform(content: string): { root: MarkmapNode } {
    const html = this.markdown.render(content || "");
    const root = cleanNode(buildTree(html) as MarkmapNode);
    return { root };
  }
}

function cleanNode(node: MarkmapNode): MarkmapNode {
  let current = node;
  while (!current.content && current.children.length === 1) {
    current = current.children[0];
  }
  while (current.children.length === 1 && !current.children[0].content) {
    current = { ...current, children: current.children[0].children };
  }
  return {
    ...current,
    children: current.children.map(cleanNode),
  };
}

Object.assign(window, {
  markmap: {
    Markmap,
    Transformer: LocalTransformer,
  },
});
