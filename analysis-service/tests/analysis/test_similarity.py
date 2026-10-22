from datetime import timedelta

from factories import event, incident

from analysis_service.analysis.features import extract_features
from analysis_service.analysis.similarity import build_profile, related_incidents, score_pair
from analysis_service.models.analysis import RelationStrength
from analysis_service.models.records import IncidentRecord

WINDOW = timedelta(minutes=15)

REFUND_TRACE = (
    "java.net.SocketTimeoutException: Read timed out\n"
    "\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)\n"
    "\tat com.shop.payment.RefundClient.refund(RefundClient.java:39)"
)


def profile(record: IncidentRecord):
    return build_profile(record, [extract_features(e) for e in record.events])


def related(target: IncidentRecord, *others: IncidentRecord, threshold: int = 40):
    return related_incidents(profile(target), [profile(o) for o in others], WINDOW, threshold)


def signals(target: IncidentRecord, other: IncidentRecord) -> dict[str, int]:
    _, matched = score_pair(profile(target), profile(other), WINDOW)
    return {s.signal: s.points for s in matched}


def test_strongly_related_same_error_in_another_environment():
    staging = incident([event(minutes=i) for i in range(3)], fingerprint="a" * 64)
    production = incident([event(minutes=2)], environment="production", fingerprint="a" * 64)

    [result] = related(staging, production)

    assert result.incident_id == production.id
    assert result.strength is RelationStrength.STRONG
    assert result.score == 100
    assert {"SAME_FINGERPRINT", "SAME_EXCEPTION_TYPE", "SAME_TOP_FRAME", "SIMILAR_MESSAGE"} <= {
        s.signal for s in result.matching_signals
    }
    assert result.matching_signals[0].description == "Same fingerprint (same error in production)"


def test_partially_related_same_exception_different_code_path():
    payments = incident([event(minutes=i) for i in range(3)])
    refunds = incident(
        [
            event(
                minutes=3 * 24 * 60,
                message="Refund request failed",
                stack_trace=REFUND_TRACE,
                source="com.shop.payment.RefundClient",
            )
        ]
    )

    [result] = related(payments, refunds)

    assert result.strength is RelationStrength.PARTIAL
    matched = {s.signal for s in result.matching_signals}
    assert "SAME_EXCEPTION_TYPE" in matched and "SAME_SERVICE" in matched
    assert "SAME_TOP_FRAME" not in matched and "TEMPORAL_OVERLAP" not in matched


def test_unrelated_incidents_sharing_generic_words_are_not_related():
    export = incident(
        [
            event(
                message="Order export failed",
                exception_type="com.shop.ExportFailedException",
                stack_trace=None,
                source=None,
            )
        ],
        service="export-service",
    )
    loyalty = incident(
        [
            event(
                message="Loyalty points calculation failed",
                exception_type="com.shop.PointsCalculationException",
                stack_trace=None,
                source=None,
            )
        ],
        service="loyalty-service",
    )

    assert related(export, loyalty) == []
    # Only context signals match: same environment and time, no shared evidence.
    assert set(signals(export, loyalty)) <= {"SAME_ENVIRONMENT", "TEMPORAL_OVERLAP"}


def test_context_signals_alone_never_make_incidents_related():
    """Same service, environment, time and category, but different exceptions and code."""
    first = incident([event(message="Gateway authorization failed")])
    second = incident(
        [
            event(
                message="Ledger sync failed",
                exception_type="java.util.concurrent.TimeoutException",
                stack_trace=None,
                source="com.shop.payment.LedgerSync",
            )
        ]
    )

    matched = signals(first, second)
    assert {"SAME_SERVICE", "SAME_ENVIRONMENT", "TEMPORAL_OVERLAP", "SAME_CATEGORY"} <= set(matched)
    assert sum(matched.values()) >= 35
    assert related(first, second, threshold=30) == []


def test_shared_trace_ids_relate_incidents_across_services():
    payment = incident([event(minutes=i, trace_id=f"t{i}") for i in range(2)])
    checkout = incident(
        [
            event(
                minutes=i,
                trace_id=f"t{i}",
                message="Order could not be completed",
                exception_type="com.shop.checkout.PaymentFailedException",
                stack_trace=None,
                source=None,
            )
            for i in range(2)
        ],
        service="checkout-service",
    )

    [result] = related(checkout, payment)
    assert result.matching_signals[0].signal == "SHARED_TRACE"
    assert result.matching_signals[0].description == "2 shared trace/correlation IDs"


def test_shared_trace_ids_do_not_count_across_environments():
    staging = incident([event(trace_id="t1")])
    production = incident(
        [event(trace_id="t1", message="x", exception_type="a.B", stack_trace=None, source=None)],
        environment="production",
        service="other",
    )

    assert "SHARED_TRACE" not in signals(staging, production)


def test_score_is_symmetric():
    first = incident([event(minutes=i) for i in range(3)])
    second = incident(
        [event(minutes=40, message="Refund request failed", stack_trace=REFUND_TRACE)]
    )

    assert (
        score_pair(profile(first), profile(second), WINDOW)[0]
        == score_pair(profile(second), profile(first), WINDOW)[0]
    )
