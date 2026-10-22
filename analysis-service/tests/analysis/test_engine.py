from datetime import UTC, datetime

from factories import event, incident

from analysis_service.analysis.engine import ANALYSIS_VERSION, AnalysisSettings, analyze
from analysis_service.models.analysis import Category, Confidence

NOW = datetime(2026, 10, 20, tzinfo=UTC)
SETTINGS = AnalysisSettings()


def test_recurring_timeout_gets_high_confidence_with_evidence():
    target = incident([event(minutes=m, host="payment-1") for m in (0, 3, 6, 9)])

    report = analyze(target, [], SETTINGS, NOW)

    primary = report.primary_candidate
    assert primary.category is Category.TIMEOUT
    assert primary.confidence is Confidence.HIGH
    assert primary.score == 5
    assert (
        primary.title
        == "Timeout waiting for a downstream dependency called from GatewayClient.authorize"
    )
    descriptions = [e.description for e in primary.evidence]
    assert "java.net.SocketTimeoutException in 4/4 events" in descriptions
    assert "4 occurrences within 9 minutes (burst)." in descriptions
    assert "All 4 analyzed events with a host came from payment-1" in descriptions
    assert primary.confidence_basis[-1] == "= 5 of 6 points: HIGH"
    assert primary.limitations
    assert report.summary.startswith("java.net.SocketTimeoutException in payment-service (staging)")
    assert "Most likely cause based on available evidence (HIGH confidence)" in report.summary
    assert report.analysis_version == ANALYSIS_VERSION


def test_single_occurrence_never_reaches_high_confidence():
    report = analyze(incident([event()]), [], SETTINGS, NOW)

    primary = report.primary_candidate
    assert primary.category is Category.TIMEOUT
    assert primary.confidence is Confidence.MEDIUM
    assert "Potential cause (MEDIUM confidence)" in report.summary


DATABASE_EVENT = {
    "exception_type": "org.springframework.dao.DataAccessResourceFailureException",
    "message": "HikariPool-1 - Connection is not available, request timed out after 30000ms",
    "source": "com.shop.inventory.StockRepository",
}


def test_missing_stack_traces_cap_confidence_and_are_reported():
    target = incident([event(minutes=m, stack_trace=None, **DATABASE_EVENT) for m in (0, 3, 6)])

    report = analyze(target, [], SETTINGS, NOW)

    primary = report.primary_candidate
    assert primary.category is Category.DATABASE
    assert primary.score == 5
    assert primary.confidence is Confidence.MEDIUM
    assert primary.confidence_basis[-2:] == [
        "= 5 of 6 points: HIGH",
        "capped at MEDIUM: no stack trace available",
    ]
    assert "None of the analyzed events has a stack trace." in report.limitations
    assert report.patterns.top_application_frame is None


def test_same_evidence_with_stack_traces_reaches_high():
    trace = (
        "org.springframework.dao.DataAccessResourceFailureException: pool exhausted\n"
        "\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)\n"
        "\tat com.shop.inventory.StockRepository.reserve(StockRepository.java:63)"
    )
    target = incident([event(minutes=m, stack_trace=trace, **DATABASE_EVENT) for m in (0, 3, 6)])

    primary = analyze(target, [], SETTINGS, NOW).primary_candidate

    assert (primary.score, primary.confidence) == (6, Confidence.HIGH)


def test_insufficient_evidence_gives_low_confidence_unknown():
    target = incident(
        [
            event(
                message="Something odd happened",
                exception_type=None,
                stack_trace=None,
                source=None,
                severity="WARN",
            )
        ]
    )

    report = analyze(target, [], SETTINGS, NOW)

    assert [c.category for c in report.candidates] == [Category.UNKNOWN]
    assert report.confidence is Confidence.LOW
    assert report.primary_candidate.title == "Insufficient evidence to determine a cause"
    assert "No cause category is supported by the available evidence." in report.summary


def test_trace_propagation_points_to_the_upstream_service():
    payment = incident([event(minutes=m, trace_id=f"t{m}") for m in (0, 3, 6)])
    checkout = incident(
        [
            event(
                minutes=m + 0.005,
                trace_id=f"t{m}",
                message="Order could not be completed",
                exception_type="com.shop.checkout.PaymentFailedException",
                stack_trace="com.shop.checkout.PaymentFailedException: "
                "Payment service returned 504\n"
                "\tat com.shop.checkout.PaymentClient.charge(PaymentClient.java:52)",
                source="com.shop.checkout.OrderService",
            )
            for m in (0, 3, 6)
        ],
        service="checkout-service",
    )

    report = analyze(checkout, [payment], SETTINGS, NOW)

    primary = report.primary_candidate
    assert primary.category is Category.DOWNSTREAM_DEPENDENCY
    assert primary.title == "Failure propagated from payment-service (SocketTimeoutException)"
    assert primary.affected_component.service == "payment-service"
    assert primary.affected_component.location == "GatewayClient.authorize"
    assert primary.related_incident_ids == [payment.id]
    assert primary.score == 6
    assert primary.confidence_basis[-1] == "= 7 points, counted as the maximum 6 of 6: HIGH"
    assert any("do not prove" in limitation for limitation in primary.limitations)
    assert report.patterns.preceding_errors[0].incident_id == payment.id
    assert report.related_incidents[0].incident_id == payment.id

    upstream_report = analyze(payment, [checkout], SETTINGS, NOW)
    assert upstream_report.patterns.subsequent_errors[0].incident_id == checkout.id
    assert upstream_report.primary_candidate.category is Category.TIMEOUT


def test_competing_candidates_with_equal_scores_are_capped():
    trace = (
        "org.postgresql.util.PSQLException: Connection to db:5432 refused.\n"
        "\tat org.postgresql.jdbc.PgConnection.<init>(PgConnection.java:273)\n"
        "\tat com.shop.orders.OrderDao.find(OrderDao.java:58)\n"
        "Caused by: java.net.ConnectException: Connection refused\n"
        "\tat java.base/java.net.Socket.connect(Socket.java:751)"
    )
    target = incident(
        [
            event(
                minutes=m,
                exception_type="org.postgresql.util.PSQLException",
                stack_trace=trace,
                message="Failed to load orders",
                source=None,
            )
            for m in (0, 1)
        ]
    )

    report = analyze(target, [], SETTINGS, NOW)

    first, second = report.candidates[:2]
    assert (first.category, second.category) == (Category.DATABASE, Category.DOWNSTREAM_DEPENDENCY)
    assert first.score == second.score
    assert first.confidence is second.confidence is Confidence.MEDIUM
    assert "capped at MEDIUM: another candidate has the same score" in first.confidence_basis


def test_analysis_is_deterministic():
    others = [incident([event(minutes=m, trace_id=f"t{m}")]) for m in range(3)]
    target = incident([event(minutes=m, trace_id=f"t{m}") for m in range(3)])

    first = analyze(target, others, SETTINGS, NOW).model_dump_json()
    second = analyze(target, list(reversed(others)), SETTINGS, NOW).model_dump_json()

    assert first == second


def test_target_is_excluded_from_its_own_comparison_pool():
    target = incident([event()])

    report = analyze(target, [target], SETTINGS, NOW)

    assert report.related_incidents == []
    assert report.data_coverage.incidents_compared == 0


def test_truncated_history_is_reported():
    target = incident([event(minutes=m) for m in range(3)], occurrence_count=80)

    report = analyze(target, [], SETTINGS, NOW)

    assert "Only the 3 most recent of 80 occurrences were analyzed." in report.limitations


def test_metadata_and_title_do_not_influence_the_category():
    plain = incident([event(minutes=m) for m in range(3)], title="Database is down")

    assert analyze(plain, [], SETTINGS, NOW).primary_candidate.category is Category.TIMEOUT
