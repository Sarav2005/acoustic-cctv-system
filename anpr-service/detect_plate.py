"""
Module 4 - ANPR: plate region detection (v2 - scored candidates).

v1 filtered candidates on geometry alone (area fraction + aspect ratio +
"roughly rectangular" polygon approx) and ranked survivors by contour
area. Real-world testing (car1.jpeg / car2.jpeg) showed this isn't enough:
a flat ceiling/structural region and a small on-screen CCTV watermark both
satisfied the geometric filters and out-scored (by area) the actual
plates, which never made it into the top candidates.

v2 keeps the same geometric filtering to generate a candidate pool (that
part was never wrong - plates DO need to pass those checks), then adds a
plate-LIKELIHOOD score per candidate based on what actually distinguishes
"license plate" from "flat surface" or "watermark text":

  1. Local contrast (std-dev of pixel intensity) - a plate is dark
     characters on a light background (or vice versa): high contrast.
     A ceiling/wall panel is close to a single flat color: low contrast.
  2. Edge density - plates have a moderate amount of edge pixels (character
     strokes + border). Flat surfaces have almost none; heavily textured
     regions (foliage, grilles) have far too much.
  3. Vertical stroke count - a horizontal projection of vertical edges
     across the crop's width should show several distinct peaks (one
     cluster per character, roughly). A flat region has ~0 peaks. This is
     also what should, in principle, catch small watermark text too - so
     it alone isn't enough...
  4. ...which is why v2 also adds a border-touch penalty: candidates whose
     bounding box touches (or is within a couple pixels of) the image
     edge are heavily penalized. Both confirmed false positives
     (ceiling band starting at (0,0), watermark near the bottom edge)
     touched the frame border; a plate mounted on a vehicle in-frame
     almost never does.
  5. A minimum absolute pixel size floor - very small crops (like a short
     watermark string) are penalized further, since a real plate captured
     at CCTV distance still needs to be a certain number of pixels wide
     to be legible at all, and OCR on a tiny crop is unreliable anyway.

Candidates are ranked by this composite score (not raw contour area), and
anything below MIN_SCORE is dropped rather than returned as a weak guess.
"""

import cv2
import numpy as np

MIN_ASPECT_RATIO = 1.0   # was 1.5 - real CCTV angle shots foreshorten plates
                          # (confirmed: Gemini's manual plate box on the real
                          # test photo measured aspect ratio ~1.21, which the
                          # old 1.5 floor was silently throwing away before it
                          # ever became a candidate)
MAX_ASPECT_RATIO = 6.5

MIN_PLATE_AREA_FRACTION = 0.0004  # loosened slightly - small/distant plates
MAX_PLATE_AREA_FRACTION = 0.35

MIN_CROP_WIDTH_PX = 40   # loosened from 60 - angled/distant real plates are smaller
MIN_CROP_HEIGHT_PX = 14

# Lowered from 0.40. Rationale: on the real test photos, the true plate
# region never even reached this scoring stage - candidate GENERATION
# (geometry filters above) was the actual bottleneck, not scoring. Once
# generation is loosened to admit angled/smaller plates, it will also admit
# more borderline non-plate regions - rather than risk dropping a real
# plate by keeping a strict score floor, we let more candidates through to
# OCR and let pattern-matching + database fuzzy-matching (both downstream,
# both already implemented) make the final call. A wrong OCR read on a
# non-plate region just fails to match the database; a real plate wrongly
# dropped here never gets a second chance.
MIN_SCORE = 0.15

BORDER_TOUCH_MARGIN_PX = 3  # bbox within this many px of the frame edge counts as "touching"


def _order_points(pts):
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


def _score_candidate(gray_crop, bbox, img_w, img_h):
    """
    Compute a 0.0-1.0 plate-likelihood score for a candidate crop.
    Returns (score, breakdown_dict) - breakdown is useful for debugging/
    tuning, not required by callers.
    """
    x0, y0, w, h = bbox

    # --- 1. Local contrast ---
    std_dev = float(gray_crop.std())
    # Flat surfaces: std well under 15. Real plate text on a plain
    # background: comfortably over 35-40 in practice. Map std=10->0,
    # std=50->1, clipped.
    contrast_score = np.clip((std_dev - 10) / 40.0, 0.0, 1.0)

    # --- 2. Edge density ---
    edges = cv2.Canny(gray_crop, 60, 180)
    edge_density = float(np.count_nonzero(edges)) / edges.size
    # Ideal range ~0.08-0.40 (character strokes + plate border). Score
    # peaks in that range and falls off outside it (too little = flat,
    # too much = noisy texture).
    if edge_density < 0.08:
        edge_score = edge_density / 0.08
    elif edge_density <= 0.40:
        edge_score = 1.0
    else:
        edge_score = max(0.0, 1.0 - (edge_density - 0.40) / 0.40)

    # --- 3. Vertical stroke count (horizontal projection of edges) ---
    col_sums = edges.sum(axis=0).astype(np.float32)
    if col_sums.max() > 0:
        col_sums /= col_sums.max()
    active = col_sums > 0.25
    # count transitions from inactive->active = number of distinct stroke
    # clusters across the width
    transitions = int(np.sum((active[1:].astype(int) - active[:-1].astype(int)) == 1))
    stroke_score = np.clip(transitions / 6.0, 0.0, 1.0)  # ~6+ clusters -> full score

    # --- 4. Border-proximity penalty (graduated, not just literal touch) ---
    # A plate mounted on a vehicle in-frame is almost never within a few
    # percent of the frame edge. Structural elements (ceiling/door edges)
    # and CCTV watermarks/logos both tend to sit close to an edge or
    # tucked into a corner - even when not touching pixel 0 exactly (the
    # real watermark case was ~1-3% off the bottom edge, not flush against
    # it), so this is a proportional distance check, not a hard touch check.
    dist_to_edge = min(x0, y0, img_w - (x0 + w), img_h - (y0 + h))
    proximity_fraction = dist_to_edge / min(img_w, img_h)
    if proximity_fraction < 0.02:
        border_penalty = 0.6
    elif proximity_fraction < 0.06:
        border_penalty = 0.3
    else:
        border_penalty = 0.0

    # Extra penalty for sitting in an actual corner (close to two edges at
    # once) - the classic watermark/logo position, and distinct from a
    # plate which is near at most one edge if any.
    near_left_or_right = x0 < 0.12 * img_w or (img_w - (x0 + w)) < 0.12 * img_w
    near_top_or_bottom = y0 < 0.12 * img_h or (img_h - (y0 + h)) < 0.12 * img_h
    corner_penalty = 0.2 if (near_left_or_right and near_top_or_bottom) else 0.0

    border_penalty = min(0.75, border_penalty + corner_penalty)
    touches_border = proximity_fraction < 0.06

    # --- 5. Minimum size floor ---
    size_penalty = 0.0
    if w < MIN_CROP_WIDTH_PX or h < MIN_CROP_HEIGHT_PX:
        size_penalty = 0.5

    base = 0.35 * contrast_score + 0.35 * edge_score + 0.30 * stroke_score
    score = max(0.0, base - border_penalty - size_penalty)

    breakdown = {
        "std_dev": round(std_dev, 1),
        "contrast_score": round(contrast_score, 2),
        "edge_density": round(edge_density, 3),
        "edge_score": round(edge_score, 2),
        "stroke_transitions": transitions,
        "stroke_score": round(stroke_score, 2),
        "touches_border": touches_border,
        "size_penalty": size_penalty,
        "score": round(score, 3),
    }
    return score, breakdown


def detect_plate_candidates(image, max_candidates=5, debug=False, debug_all=False):
    """
    Find candidate license-plate regions in a BGR image.

    Returns a list of dicts, sorted by plate-likelihood score descending:
        {"crop", "bbox", "area", "aspect_ratio", "score", "score_breakdown"}
    Only candidates scoring >= MIN_SCORE are returned. Empty list if none
    pass geometry + scoring.

    If debug=True, also prints the breakdown for every geometric candidate
    (including ones that get filtered out by MIN_SCORE) - useful when
    tuning against new real-world images.
    """
    if image is None or image.size == 0:
        return []

    img_h, img_w = image.shape[:2]
    img_area = img_h * img_w

    gray_full = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray_filtered = cv2.bilateralFilter(gray_full, 11, 17, 17)
    edged = cv2.Canny(gray_filtered, 30, 200)

    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 3))
    edged = cv2.dilate(edged, kernel, iterations=1)

    contours, _ = cv2.findContours(edged.copy(), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)

    scored_candidates = []
    geometry_rejected_count = 0
    passed_to_scoring = 0
    for c in contours:
        area = cv2.contourArea(c)
        area_fraction = area / img_area if img_area else 0
        if area_fraction < MIN_PLATE_AREA_FRACTION or area_fraction > MAX_PLATE_AREA_FRACTION:
            geometry_rejected_count += 1
            continue

        x, y, w, h = cv2.boundingRect(c)
        if h == 0:
            continue
        aspect_ratio = w / float(h)
        if aspect_ratio < MIN_ASPECT_RATIO or aspect_ratio > MAX_ASPECT_RATIO:
            if debug_all:
                print(f"  [geometry-rejected: aspect={aspect_ratio:.2f}] bbox=({x},{y},{w},{h}) area={area:.0f} area_frac={area_fraction:.4f}")
            geometry_rejected_count += 1
            continue

        # Rectangularity check via extent (contour area vs. its bounding
        # box area) instead of a strict polygon-vertex count. The old
        # "approx to 4-6 points" check is brittle on real, noisy contours
        # (JPEG artifacts, motion blur, partial edges) - a real plate's
        # contour can easily approx to 3 or 8+ points and get wrongly
        # rejected. Extent tolerates that while still rejecting genuinely
        # non-rectangular blobs (a rectangle fills most of its own bbox;
        # an irregular/L-shaped/sparse contour does not).
        bbox_area = w * h
        extent = area / bbox_area if bbox_area else 0
        if extent < 0.30:
            if debug_all:
                print(f"  [geometry-rejected: extent={extent:.2f}] bbox=({x},{y},{w},{h}) area={area:.0f} aspect={aspect_ratio:.2f}")
            geometry_rejected_count += 1
            continue

        margin_x = int(0.03 * w)
        margin_y = int(0.08 * h)
        x0 = max(0, x - margin_x)
        y0 = max(0, y - margin_y)
        x1 = min(img_w, x + w + margin_x)
        y1 = min(img_h, y + h + margin_y)
        crop = image[y0:y1, x0:x1]
        if crop.size == 0:
            continue

        gray_crop = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        bbox = (x0, y0, x1 - x0, y1 - y0)
        score, breakdown = _score_candidate(gray_crop, bbox, img_w, img_h)
        passed_to_scoring += 1

        if debug:
            print(f"  candidate bbox={bbox} aspect={aspect_ratio:.2f} {breakdown}")

        if score >= MIN_SCORE:
            scored_candidates.append(
                {
                    "crop": crop,
                    "bbox": bbox,
                    "area": area,
                    "aspect_ratio": round(aspect_ratio, 2),
                    "score": round(score, 3),
                    "score_breakdown": breakdown,
                }
            )

    if debug_all:
        print(f"  total raw contours: {len(contours)}, geometry-rejected: {geometry_rejected_count}, passed to scoring: {passed_to_scoring}, above MIN_SCORE: {len(scored_candidates)}")

    scored_candidates.sort(key=lambda c: c["score"], reverse=True)
    return scored_candidates[:max_candidates]


if __name__ == "__main__":
    import sys

    path = sys.argv[1] if len(sys.argv) > 1 else "test_plate.jpg"
    debug = "--debug" in sys.argv
    debug_all = "--debug-all" in sys.argv
    img = cv2.imread(path)
    if img is None:
        print(f"Could not read image: {path}")
        sys.exit(1)

    results = detect_plate_candidates(img, debug=debug or debug_all, debug_all=debug_all)
    print(f"Found {len(results)} candidate(s) above MIN_SCORE={MIN_SCORE}")
    for i, r in enumerate(results):
        print(f"  [{i}] bbox={r['bbox']} score={r['score']} aspect_ratio={r['aspect_ratio']}")
        cv2.imwrite(f"candidate_{i}.jpg", r["crop"])
