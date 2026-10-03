package cn.rainwu.halo.ai.suite.service;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/** Fixed pixel grid and master-derived palette; independent of cloud model capabilities. */
final class PixelPetProcessor {
    static final int GRID = 96;
    static final int CANVAS = GRID * 4;
    private static final int COLORS = 24;

    private PixelPetProcessor() { }

    static byte[] prepareMaster(byte[] bytes) {
        BufferedImage grid = resize(read(bytes), GRID, false);
        List<Integer> palette = palette(grid);
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int p = grid.getRGB(x, y);
                grid.setRGB(x, y, (p >>> 24) < 128 ? 0 : 0xFF000000 | nearest(p, palette));
            }
        }
        return write(resize(grid, CANVAS, true));
    }

    static byte[] modelReference(byte[] master) {
        return write(resize(read(master), 1024, true));
    }

    /** Reapply grid-aligned binary transparency after the background eraser. */
    static byte[] cleanup(byte[] bytes) {
        BufferedImage grid = resize(read(bytes), GRID, true);
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int p = grid.getRGB(x, y);
                grid.setRGB(x, y, (p >>> 24) < 128 ? 0 : p | 0xFF000000);
            }
        }
        return write(resize(grid, CANVAS, true));
    }

    static byte[] compose(byte[] masterBytes, byte[] candidateBytes,
                          PetStore.ExpressionRegion requested) {
        BufferedImage master = read(masterBytes);
        BufferedImage candidate = read(candidateBytes);
        if (master.getWidth() != CANVAS || master.getHeight() != CANVAS
            || candidate.getWidth() != 1024 || candidate.getHeight() != 1024) {
            throw new IllegalArgumentException("像素母版或表情候选尺寸不符合要求");
        }
        BufferedImage grid = resize(master, GRID, true);
        BufferedImage edited = resize(candidate, GRID, false);
        // Exact colors from the accepted master; never create an expression-specific palette.
        List<Integer> colors = new ArrayList<>();
        for (int p : grid.getRGB(0, 0, GRID, GRID, null, 0, GRID)) {
            if ((p >>> 24) != 0 && !colors.contains(p & 0xFFFFFF)) {
                colors.add(p & 0xFFFFFF);
            }
        }
        if (colors.isEmpty() || colors.size() > COLORS) {
            throw new IllegalArgumentException("像素母版色板无效，请重新生成母版");
        }
        PetStore.ExpressionRegion region = PetGeneratorService.normalizeRegion(requested);
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                double dx = (x + .5 - GRID * region.getCenterX()) / (GRID * region.getWidth() / 2);
                double dy = (y + .5 - GRID * region.getCenterY()) / (GRID * region.getHeight() / 2);
                int p = grid.getRGB(x, y);
                if (dx * dx + dy * dy < 1 && (p >>> 24) != 0) {
                    grid.setRGB(x, y, (p & 0xFF000000) | nearest(edited.getRGB(x, y), colors));
                }
            }
        }
        return write(resize(grid, CANVAS, true));
    }

    /** Deterministic weighted median-cut palette of visible pixels only, without dithering. */
    private static List<Integer> palette(BufferedImage image) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int p : image.getRGB(0, 0, GRID, GRID, null, 0, GRID)) {
            if ((p >>> 24) >= 128) {
                counts.merge(p & 0xFFFFFF, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            throw new IllegalArgumentException("像素母版没有可见角色，请重新生成");
        }
        List<List<Integer>> boxes = new ArrayList<>();
        boxes.add(new ArrayList<>(counts.keySet()));
        while (boxes.size() < COLORS) {
            List<Integer> box = boxes.stream().filter(b -> b.size() > 1)
                .max(Comparator.comparingInt(PixelPetProcessor::range)).orElse(null);
            if (box == null) break;
            int channel = splitChannel(box);
            box.sort(Comparator.comparingInt((Integer c) -> (c >> channel) & 255)
                .thenComparingInt(Integer::intValue));
            int half = box.stream().mapToInt(counts::get).sum() / 2;
            int weight = 0;
            int cut = 0;
            do { weight += counts.get(box.get(cut++)); }
            while (weight < half && cut < box.size() - 1);
            boxes.remove(box);
            boxes.add(new ArrayList<>(box.subList(0, cut)));
            boxes.add(new ArrayList<>(box.subList(cut, box.size())));
        }
        List<Integer> result = new ArrayList<>();
        for (List<Integer> box : boxes) {
            int total = 0; long red = 0, green = 0, blue = 0;
            for (int c : box) {
                int n = counts.get(c); total += n;
                red += ((c >> 16) & 255) * (long) n;
                green += ((c >> 8) & 255) * (long) n;
                blue += (c & 255) * (long) n;
            }
            result.add(((int) (red / total) << 16) | ((int) (green / total) << 8) | (int) (blue / total));
        }
        return result;
    }

    private static int range(List<Integer> box) {
        int channel = splitChannel(box);
        return box.stream().mapToInt(c -> (c >> channel) & 255).max().orElse(0)
            - box.stream().mapToInt(c -> (c >> channel) & 255).min().orElse(0);
    }

    private static int splitChannel(List<Integer> box) {
        int best = 16, max = -1;
        for (int shift : new int[]{16, 8, 0}) {
            int range = box.stream().mapToInt(c -> (c >> shift) & 255).max().orElse(0)
                - box.stream().mapToInt(c -> (c >> shift) & 255).min().orElse(0);
            if (range > max) { max = range; best = shift; }
        }
        return best;
    }

    private static int nearest(int p, List<Integer> colors) {
        int best = colors.getFirst(), min = Integer.MAX_VALUE;
        for (int c : colors) {
            int r = ((p >> 16) & 255) - ((c >> 16) & 255);
            int g = ((p >> 8) & 255) - ((c >> 8) & 255);
            int b = (p & 255) - (c & 255);
            int distance = r * r + g * g + b * b;
            if (distance < min) { min = distance; best = c; }
        }
        return best;
    }

    private static BufferedImage resize(BufferedImage image, int size, boolean nearest) {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, nearest
            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, size, size, null); g.dispose();
        return result;
    }

    private static BufferedImage read(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != image.getHeight()) {
                throw new IllegalArgumentException("像素图片必须是有效的方形图片");
            }
            return image;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法读取像素图片", e);
        }
    }

    private static byte[] write(BufferedImage image) {
        try {
            var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("保存像素图片失败", e);
        }
    }
}
