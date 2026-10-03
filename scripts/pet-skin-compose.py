#!/usr/bin/env python3
"""Compose AI-edited pet expressions into a locked transparent master image."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageChops


DEFAULT_BACKGROUNDS = ("#f4f6f8", "#18222d")


def resolve(config_dir: Path, value: str) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else (config_dir / path).resolve()


def load_rgba(path: Path) -> Image.Image:
    with Image.open(path) as image:
        return image.convert("RGBA")


def load_mask(path: Path, expected_size: tuple[int, int]) -> Image.Image:
    with Image.open(path) as image:
        mask = image.convert("L")
    if mask.size != expected_size:
        raise ValueError(
            f"mask size {mask.size} does not match master size {expected_size}: {path}"
        )
    return mask


def alpha_values(image: Image.Image) -> list[int]:
    return list(image.getchannel("A").getdata())


def compose(master: Image.Image, edited: Image.Image, mask: Image.Image) -> Image.Image:
    if edited.size != master.size:
        raise ValueError(
            f"edited image size {edited.size} does not match master size {master.size}"
        )
    result = Image.composite(edited, master, mask)
    result.putalpha(master.getchannel("A"))
    return result


def count_changed_outside_mask(
    master: Image.Image, result: Image.Image, mask: Image.Image
) -> int:
    changed = ImageChops.difference(master.convert("RGB"), result.convert("RGB"))
    changed_pixels = changed.load()
    mask_pixels = mask.load()
    width, height = master.size
    return sum(
        1
        for y in range(height)
        for x in range(width)
        if mask_pixels[x, y] == 0 and changed_pixels[x, y] != (0, 0, 0)
    )


def make_review(
    frames: dict[str, Image.Image],
    output_size: tuple[int, int],
    widget_size: tuple[int, int],
    backgrounds: tuple[str, ...],
) -> Image.Image:
    states = list(frames)
    cell_width, cell_height = output_size
    widget_width, widget_height = widget_size
    lower_height = max(widget_height + 30, 126)
    row_height = cell_height + lower_height
    sheet = Image.new("RGB", (len(states) * cell_width, len(backgrounds) * row_height))

    for row, background in enumerate(backgrounds):
        panel = Image.new(
            "RGBA", (len(states) * cell_width, row_height), background
        )
        for column, state in enumerate(states):
            frame = frames[state]
            x = column * cell_width
            panel.alpha_composite(frame, (x, 0))
            widget = frame.resize(widget_size, Image.Resampling.LANCZOS)
            widget_x = x + (cell_width - widget_width) // 2
            panel.alpha_composite(widget, (widget_x, cell_height + 15))
        sheet.paste(panel.convert("RGB"), (0, row * row_height))
    return sheet


def run(config_path: Path) -> dict[str, object]:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    config_dir = config_path.parent.resolve()
    master_path = resolve(config_dir, config["master"])
    mask_path = resolve(config_dir, config["mask"])
    output_dir = resolve(config_dir, config["output_dir"])
    review_path = resolve(config_dir, config["review"])
    report_path = resolve(config_dir, config["report"])
    output_size = tuple(config.get("output_size", [384, 384]))
    widget_size = tuple(config.get("widget_size", [96, 96]))
    backgrounds = tuple(config.get("backgrounds", DEFAULT_BACKGROUNDS))

    master = load_rgba(master_path)
    mask = load_mask(mask_path, master.size)
    master_alpha = alpha_values(master)
    mask_values = list(mask.getdata())
    if not any(mask_values):
        raise ValueError("mask is empty")
    if all(value == 255 for value in mask_values):
        raise ValueError("mask covers the entire image; no master pixels would be locked")

    native_frames = {"idle": master}
    frame_reports: dict[str, object] = {}
    for state, source_value in config["sources"].items():
        source_path = resolve(config_dir, source_value)
        edited = load_rgba(source_path)
        result = compose(master, edited, mask)
        outside_changes = count_changed_outside_mask(master, result, mask)
        alpha_matches = alpha_values(result) == master_alpha
        if outside_changes != 0 or not alpha_matches:
            raise ValueError(
                f"quality gate failed for {state}: outside_changes={outside_changes}, "
                f"alpha_matches={alpha_matches}"
            )
        native_frames[state] = result
        frame_reports[state] = {
            "source": str(source_path),
            "source_size": list(edited.size),
            "changed_pixels_outside_mask": outside_changes,
            "alpha_matches_master": alpha_matches,
        }

    output_dir.mkdir(parents=True, exist_ok=True)
    resized_frames: dict[str, Image.Image] = {}
    for state, image in native_frames.items():
        resized = image.resize(output_size, Image.Resampling.LANCZOS)
        resized.save(output_dir / f"{state}.png", optimize=True)
        resized_frames[state] = resized

    review_path.parent.mkdir(parents=True, exist_ok=True)
    make_review(resized_frames, output_size, widget_size, backgrounds).save(review_path)

    idle_output_alpha = alpha_values(resized_frames["idle"])
    for state, image in resized_frames.items():
        if alpha_values(image) != idle_output_alpha:
            raise ValueError(f"resized alpha silhouette differs for {state}")

    report: dict[str, object] = {
        "status": "passed",
        "master": str(master_path),
        "master_size": list(master.size),
        "mask": str(mask_path),
        "mask_nonzero_pixels": sum(value > 0 for value in mask_values),
        "mask_solid_pixels": sum(value == 255 for value in mask_values),
        "output_size": list(output_size),
        "transparent_output_pixels": sum(value == 0 for value in idle_output_alpha),
        "frames": {
            "idle": {
                "alpha_matches_master": True,
                "changed_pixels_outside_mask": 0,
            },
            **frame_reports,
        },
        "review": str(review_path),
        "output_dir": str(output_dir),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Lock a pet master image and composite AI expressions through a mask."
    )
    parser.add_argument("config", type=Path, help="Path to the pipeline JSON config")
    args = parser.parse_args()
    try:
        report = run(args.config.resolve())
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"pet-skin-compose: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
