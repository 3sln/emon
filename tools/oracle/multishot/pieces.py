"""Render seeded test crops. Read the case design as JSON on stdin."""
import hashlib
import json
import math
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageFilter, ImageOps


def warp(image, top, bottom, rng, careful):
    width, _ = image.size
    height = bottom - top
    pad = 0 if careful else round(width * rng.uniform(.03, .12))
    scale = 1 if careful else rng.uniform(1, 1.7)
    size = (round((width + 2 * pad) * scale), round(height * scale))
    corners = np.array([
        [-pad, top], [width + pad, top],
        [width + pad, bottom], [-pad, bottom],
    ], dtype=float)
    if careful:
        return image.crop((0, round(top), width, round(bottom))), np.array([
            [1, 0, 0], [0, 1, round(top)], [0, 0, 1]
        ])
    angle = rng.uniform(-.045, .045)
    center = corners.mean(axis=0)
    rotation = np.array([[math.cos(angle), -math.sin(angle)],
                         [math.sin(angle), math.cos(angle)]])
    corners = (corners - center) @ rotation.T + center
    corners += rng.uniform(-1, 1, (4, 2)) * [width * .025, height * .01]
    ow, oh = size
    equations, points = [], []
    for (x, y), (u, v) in zip([(0, 0), (ow, 0), (ow, oh), (0, oh)], corners):
        equations += [[x, y, 1, 0, 0, 0, -x*u, -y*u],
                      [0, 0, 0, x, y, 1, -x*v, -y*v]]
        points += [u, v]
    coeff = np.linalg.solve(equations, points)
    view = image.transform(size, Image.Transform.PERSPECTIVE, coeff,
                           Image.Resampling.BICUBIC, fillcolor=(82, 71, 61))
    view = view.filter(ImageFilter.GaussianBlur(rng.uniform(.15, 1.1)))
    gradient = np.linspace(1, rng.uniform(.77, .98), oh)[:, None, None]
    pixels = np.asarray(view, dtype=np.float32) * gradient
    pixels += rng.normal(0, rng.uniform(0, 2.5), (oh, ow, 1))
    return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8)), np.append(coeff, 1).reshape(3, 3)


def visibility(box, inverse, size):
    x0, y0, x1, y1 = box
    x, y = np.meshgrid(np.linspace(x0, x1, 9), np.linspace(y0, y1, 9))
    mapped = np.stack((x.ravel(), y.ravel(), np.ones(x.size)), axis=1) @ inverse.T
    px, py = (mapped[:, :2] / mapped[:, 2:3]).T
    fraction = ((px >= 0) & (py >= 0) & (px < size[0]) & (py < size[1])).mean()
    return [float(fraction), [float(px.min()), float(py.min()), float(px.max()), float(py.max())]]


def generate(case, out):
    image = ImageOps.exif_transpose(Image.open(case["source"])).convert("RGB")
    rng = np.random.default_rng(case["seed"])
    # Traps can insert the source strip of a real item or a deliberately repeated rule.
    inserts = sorted(case["insert"], key=lambda i: i["at"], reverse=True)
    for insert in inserts:
        at, top, bottom = (insert[k] for k in ("at", "top", "bottom"))
        strip = image.crop((0, top, image.width, bottom))
        if insert.get("rule"):
            strip.paste("white", (0, 0, strip.width, strip.height))
            for x in range(strip.width // 8, strip.width * 7 // 8, 9):
                y = strip.height // 2
                strip.paste("black", (x, y, x + 5, y + 2))
        enlarged = Image.new("RGB", (image.width, image.height + strip.height))
        enlarged.paste(image.crop((0, 0, image.width, at)), (0, 0))
        enlarged.paste(strip, (0, at))
        enlarged.paste(image.crop((0, at, image.width, image.height)), (0, at + strip.height))
        image = enlarged
    scan = out / "scan.png"
    image.save(scan)
    pieces = []
    for i, (top, bottom, blank) in enumerate(case["windows"]):
        view, matrix = warp(image, top, bottom, rng, case["careful"])
        inverse = np.linalg.inv(matrix)
        projections = [visibility(row["box"], inverse, view.size) for row in case["rows"]]
        if blank:
            view = ImageOps.expand(view, (0, 0, 0, blank), fill="white")
        path = out / f"piece-{i}.jpg"
        view.save(path, quality=96 if case["careful"] else int(rng.integers(66, 93)))
        pieces.append({"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                       "visible": [p[0] for p in projections],
                       "boxes": [p[1] for p in projections]})
    manifest = {"scan": str(scan), "pieces": pieces}
    if case.get("tiff", True):
        sequence = out / "sequence.tiff"
        frames = [Image.open(pieces[i]["path"]).convert("RGB") for i in case["order"]]
        if case.get("other"):
            frames = [frames[0], ImageOps.exif_transpose(Image.open(case["other"])).convert("RGB")]
        frames[0].save(sequence, save_all=True, append_images=frames[1:], compression="tiff_lzw")
        manifest["sequence"] = str(sequence)
    return manifest


def clip_cases(case, out):
    image = ImageOps.exif_transpose(Image.open(case["source"])).convert("RGB")
    rows, index = case["rows"], case["target"]
    x0, y0, x1, y1 = rows[index]["box"]
    left, upper = 0, max(0, rows[max(0, index - 5)]["box"][1] - 3)
    right, lower = image.width, min(image.height, rows[min(len(rows) - 1, index + 5)]["box"][3] + 3)
    dx, dy = max(2, min(24, (x1 - x0) // 10)), max(1, (y1 - y0) // 3)
    windows = {
        "top": (left, y0 + dy, right, lower),
        "bottom": (left, upper, right, y1 - dy),
        "left": (x0 + dx, upper, right, lower),
        "right": (left, upper, x1 - dx, lower),
        "tight": (left, max(0, y0 - 2), right, lower),
        "full": (0, 0, image.width, image.height),
    }
    readings = {}
    for edge, rect in windows.items():
        view = image.crop(rect)
        path = out / f"clip-{edge}.png"
        view.save(path)
        clipped = []
        for row in rows:
            x0, y0, x1, y1 = row["box"]
            area = (x1 - x0) * (y1 - y0)
            visible_width = max(0, min(x1, rect[2]) - max(x0, rect[0]))
            visible_height = max(0, min(y1, rect[3]) - max(y0, rect[1]))
            inside = visible_width * visible_height
            if 0 < inside < .97 * area:
                clipped.append([x0 - rect[0], y0 - rect[1], x1 - rect[0], y1 - rect[1]])
        readings[edge] = {
            "path": str(path),
            "clipped": clipped,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
    return readings


def run(spec, output):
    job = clip_cases if "target" in spec else generate
    print(json.dumps(job(spec, Path(output))))


if __name__ == "__main__":
    if sys.argv[1] == "--batch":
        for line in sys.stdin:
            spec, output = json.loads(line)
            run(spec, output)
    else:
        run(json.load(sys.stdin), sys.argv[1])
