package cn.rainwu.halo.ai.suite.service;

import java.util.List;
import lombok.Data;

/** Exact preview selection, encoded as sorted [start, length] runs in row-major order. */
@Data
public class ColorEraseMask {
    private int width;
    private int height;
    private List<List<Integer>> runs;

    void apply(double[] weights, int imageWidth, int imageHeight) {
        if (width != imageWidth || height != imageHeight || width <= 0 || height <= 0
            || (long) width * height > 16_777_216 || runs == null || runs.isEmpty()
            || runs.size() > 100_000) {
            throw new IllegalArgumentException("颜色选区无效或图片尺寸已变化，请重新选择");
        }
        long previousEnd = 0;
        for (var run : runs) {
            if (run == null || run.size() != 2 || run.get(0) == null || run.get(1) == null)
                throw new IllegalArgumentException("颜色选区格式无效");
            long start = run.get(0), length = run.get(1), end = start + length;
            if (start < previousEnd || length <= 0 || end > weights.length)
                throw new IllegalArgumentException("颜色选区范围无效");
            java.util.Arrays.fill(weights, (int) start, (int) end, 1d);
            previousEnd = end;
        }
    }
}
