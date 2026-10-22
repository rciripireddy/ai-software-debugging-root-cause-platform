"""Confidence bands for root-cause candidates.

A candidate collects evidence points (0-6, see docs/analysis.md#confidence):

    exception type matches the category rules ............ +2
    messages match the category rules ..................... +1
    stack frames / source match the category rules ........ +1
    the evidence appears in >= 80% of >= 3 analyzed events  +1
    same top application frame in >= 80% of >= 2 traces .... +1

Trace-linked propagation candidates use their own points (see candidates.py). The points map to
a band, which caps then lower when the evidence is incomplete. Bands are reported instead of
percentages because the points are not calibrated probabilities.
"""

from analysis_service.models.analysis import Confidence

MAX_SCORE = 6
HIGH_MINIMUM = 5
MEDIUM_MINIMUM = 3

_ORDER = [Confidence.LOW, Confidence.MEDIUM, Confidence.HIGH]


def band(score: int) -> Confidence:
    if score >= HIGH_MINIMUM:
        return Confidence.HIGH
    if score >= MEDIUM_MINIMUM:
        return Confidence.MEDIUM
    return Confidence.LOW


def cap(confidence: Confidence, maximum: Confidence) -> Confidence:
    return min(confidence, maximum, key=_ORDER.index)
