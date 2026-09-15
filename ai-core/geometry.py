"""Track-region estimation and intrusion geometry, independent of model weights."""
from __future__ import annotations
import math
import cv2
import numpy as np


def validate_roi(points):
    p = np.asarray(points, dtype=np.float32)
    if p.shape != (4, 2) or not np.isfinite(p).all() or (p < 0).any() or (p > 1).any():
        raise ValueError("Danger area must contain four normalized points")
    if not cv2.isContourConvex(p) or cv2.contourArea(p) < .005:
        raise ValueError("Danger area must be a non-intersecting convex quadrilateral")
    return p


def estimate_rails(frame):
    """Find two long, converging rail edges. Fail closed when evidence is weak.

    This geometric baseline assumes a forward-facing view of approximately straight
    rails. Curves, switches, aerial views and occlusion require a user-calibrated ROI.
    It is deliberately not represented as a trained rail segmentation model.
    """
    h, w = frame.shape[:2]
    scale = min(1.0, 960 / w)
    small = cv2.resize(frame, (round(w * scale), round(h * scale)))
    sh, sw = small.shape[:2]
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(cv2.GaussianBlur(gray, (5, 5), 0), 60, 160)
    edges[:round(sh * .10)] = 0
    lines = cv2.HoughLinesP(edges, 1, np.pi / 720, threshold=55,
                           minLineLength=sh * .22, maxLineGap=sh * .08)
    if lines is None:
        return None
    candidates = []
    top, bottom = sh * .16, sh * .97
    for x1, y1, x2, y2 in lines[:, 0]:
        if abs(int(y2) - int(y1)) < sh * .20:
            continue
        slope = (float(x2) - x1) / (float(y2) - y1)
        intercept = x1 - slope * y1
        xb, xt = slope * bottom + intercept, slope * top + intercept
        if 0.05 * sw < xb < .95 * sw and -.02 * sw < xt < 1.02 * sw and abs(slope) < 1.8:
            candidates.append((xt, xb, math.hypot(x2-x1, y2-y1)))
    if not candidates:
        return None
    # Evaluate the same pairs and thresholds in NumPy. Blocks bound temporary memory;
    # row-major argmax preserves the previous first-match behavior when scores tie.
    values = np.asarray(candidates, dtype=np.float64)
    best_pair, best_score = None, -1.0
    for start in range(0, len(values), 64):
        left = values[start:start+64]
        top_gap = values[None,:,0] - left[:,None,0]
        bottom_gap = values[None,:,1] - left[:,None,1]
        mid = (values[None,:,1] + left[:,None,1]) / 2
        valid = ((top_gap > .05*sw) & (top_gap < .50*sw) & (bottom_gap > .20*sw) &
                 (bottom_gap < .80*sw) & (bottom_gap >= top_gap*1.12) & (mid > .25*sw) & (mid < .75*sw))
        scores = (left[:,None,2]+values[None,:,2])/sh - np.abs(mid/sw-.5)*2 - np.abs(bottom_gap/sw-.38)
        scores[~valid] = -np.inf
        i,j = np.unravel_index(np.argmax(scores), scores.shape)
        if scores[i,j] > best_score:
            best_pair, best_score = (left[i], values[j]), float(scores[i,j])
    if best_pair is None or best_score < .8:
        return None
    left, right = best_pair
    margin_top, margin_bottom = (right[0]-left[0])*.14, (right[1]-left[1])*.14
    best = [[(left[0]-margin_top)/sw, top/sh], [(right[0]+margin_top)/sw, top/sh],
            [(right[1]+margin_bottom)/sw, bottom/sh], [(left[1]-margin_bottom)/sw, bottom/sh]]
    return validate_roi(np.clip(best, 0, 1)).tolist()


def in_danger(box, roi):
    """Use the object's ground-contact strip, not its upper-body overlap."""
    x1, y1, x2, y2 = box
    polygon = np.asarray(roi, dtype=np.float32)
    foot = ((x1+x2)/2, y2)
    if cv2.pointPolygonTest(polygon, foot, False) >= 0:
        return True
    strip = np.asarray([[x1, y2-(y2-y1)*.12], [x2,y2-(y2-y1)*.12], [x2,y2], [x1,y2]], dtype=np.float32)
    intersection, _ = cv2.intersectConvexConvex(polygon, strip)
    return float(intersection) / max(float(cv2.contourArea(strip)), 1e-9) >= .2


def iou(a, b):
    intersection = max(0, min(a[2], b[2])-max(a[0], b[0])) * max(0, min(a[3], b[3])-max(a[1], b[1]))
    return intersection / max((a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection, 1e-9)


def sample_frame_indices(frame_count, fps, sample_seconds):
    """Sample actual frame indices; rounded duration may point beyond the last frame."""
    if not all(math.isfinite(v) for v in (frame_count, fps, sample_seconds)) or frame_count < 1 or fps <= 0 or sample_seconds < 0:
        raise ValueError('Invalid sampling metadata')
    return range(0, int(frame_count), max(1, round(fps * sample_seconds)))


class EventTracker:
    """Greedy category+IoU association with one-to-one matching per sampled frame."""
    def __init__(self, sample_seconds=1):
        self.tracks = {}
        self.next_id = 1
        self.max_gap = max(3.0, sample_seconds * 2.5)

    def update(self, detections, timestamp):
        used = set()
        for d in sorted(detections, key=lambda x: x['confidence'], reverse=True):
            candidates = [(iou(d['box'], t['lastBox']), key) for key, t in self.tracks.items()
                          if key not in used and t['category'] == d['category'] and timestamp-t['lastSeen'] <= self.max_gap]
            score, key = max(candidates, default=(0, ''))
            if score < .20:
                key = f"object-{self.next_id}"
                self.next_id += 1
                self.tracks[key] = {**d, 'trackKey': key, 'lastBox': d['box'], 'lastSeen': timestamp}
            else:
                t = self.tracks[key]
                # Preserve evidence of an intrusion even when a later frame is outside.
                if (d['inDanger'] and not t['inDanger']) or (d['inDanger'] == t['inDanger'] and d['confidence'] > t['confidence']):
                    self.tracks[key] = {**d, 'trackKey': key}
                self.tracks[key].update(lastBox=d['box'], lastSeen=timestamp)
            d['trackKey'] = key
            used.add(key)
        if len(self.tracks) > 200:
            raise ValueError("Too many objects; split this video into shorter clips")

    def results(self):
        return [{k:v for k,v in t.items() if k not in ('lastBox','lastSeen')} for t in self.tracks.values()]

    def evidence_snapshots(self):
        return {track['snapshot'] for track in self.tracks.values()}
