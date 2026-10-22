"""Compares analysis results with the scenario labels."""

from dataclasses import dataclass
from itertools import combinations

from evaluation.dataset import Labels


@dataclass(frozen=True)
class Prediction:
    category: str
    confidence: str
    title: str
    related_scenarios: frozenset[str]


@dataclass(frozen=True)
class ScenarioOutcome:
    scenario: str
    name: str
    expected: str
    predicted: str
    confidence: str
    title: str

    @property
    def correct(self) -> bool:
        return self.expected == self.predicted


@dataclass(frozen=True)
class Evaluation:
    outcomes: list[ScenarioOutcome]
    predicted_pairs: frozenset[frozenset[str]]
    expected_pairs: frozenset[frozenset[str]]

    @property
    def correct(self) -> int:
        return sum(1 for outcome in self.outcomes if outcome.correct)

    @property
    def accuracy(self) -> float:
        return self.correct / len(self.outcomes) if self.outcomes else 0.0

    @property
    def true_positive_pairs(self) -> frozenset[frozenset[str]]:
        return self.predicted_pairs & self.expected_pairs

    @property
    def related_precision(self) -> float | None:
        if not self.predicted_pairs:
            return None
        return len(self.true_positive_pairs) / len(self.predicted_pairs)

    @property
    def related_recall(self) -> float | None:
        if not self.expected_pairs:
            return None
        return len(self.true_positive_pairs) / len(self.expected_pairs)


def evaluate(predictions: dict[str, Prediction], labels: Labels) -> Evaluation:
    outcomes = [
        ScenarioOutcome(
            scenario=scenario,
            name=labels.names[scenario],
            expected=labels.expected_category[scenario],
            predicted=prediction.category,
            confidence=prediction.confidence,
            title=prediction.title,
        )
        for scenario, prediction in sorted(predictions.items())
    ]
    # A pair counts as predicted when either incident lists the other as related.
    predicted_pairs = frozenset(
        frozenset((a, b))
        for a, b in combinations(sorted(predictions), 2)
        if b in predictions[a].related_scenarios or a in predictions[b].related_scenarios
    )
    expected_pairs = frozenset(pair for pair in labels.related_pairs if pair <= predictions.keys())
    return Evaluation(outcomes, predicted_pairs, expected_pairs)


def format_report(evaluation: Evaluation, mode: str) -> str:
    lines = [
        f"Analysis evaluation on the synthetic scenario set ({mode}).",
        "Synthetic demo data written together with the rules: this measures whether the rules "
        "behave as designed, not accuracy on production incidents.",
        "",
        f"{'Scenario':<9}{'Expected':<23}{'Predicted':<23}{'Confidence':<11}Result",
    ]
    for o in evaluation.outcomes:
        lines.append(
            f"{o.scenario:<9}{o.expected:<23}{o.predicted:<23}{o.confidence:<11}"
            f"{'ok' if o.correct else 'WRONG'}"
        )
    lines += [
        "",
        f"Scenarios: {len(evaluation.outcomes)}",
        f"Correct primary category: {evaluation.correct}/{len(evaluation.outcomes)} "
        f"({evaluation.accuracy:.1%})",
    ]
    precision, recall = evaluation.related_precision, evaluation.related_recall
    tp = len(evaluation.true_positive_pairs)
    lines.append(
        f"Related-incident pairs: predicted {len(evaluation.predicted_pairs)}, expected "
        f"{len(evaluation.expected_pairs)}, correct {tp}"
    )
    lines.append(
        "Related-incident precision: "
        + (
            f"{precision:.1%} ({tp}/{len(evaluation.predicted_pairs)})"
            if precision is not None
            else "n/a (no pairs predicted)"
        )
    )
    lines.append(
        "Related-incident recall: "
        + (f"{recall:.1%} ({tp}/{len(evaluation.expected_pairs)})" if recall is not None else "n/a")
    )
    unexpected = sorted(
        "-".join(sorted(p)) for p in evaluation.predicted_pairs - evaluation.expected_pairs
    )
    missed = sorted(
        "-".join(sorted(p)) for p in evaluation.expected_pairs - evaluation.predicted_pairs
    )
    if unexpected:
        lines.append(f"Unexpected related pairs: {', '.join(unexpected)}")
    if missed:
        lines.append(f"Missed related pairs: {', '.join(missed)}")
    return "\n".join(lines)
