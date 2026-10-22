from datetime import timedelta

from factories import event, incident

from analysis_service.analysis.temporal import host_distribution, linked_incidents, recurrence
from analysis_service.models.analysis import RecurrencePattern

WINDOW = timedelta(minutes=15)


def test_single_occurrence():
    result = recurrence(incident([event()]), WINDOW)

    assert result.pattern is RecurrencePattern.SINGLE
    assert result.median_interval_seconds is None


def test_burst_within_window():
    result = recurrence(incident([event(minutes=m) for m in (0, 4, 9)]), WINDOW)

    assert result.pattern is RecurrencePattern.BURST
    assert result.description == "3 occurrences within 9 minutes (burst)."
    assert result.max_occurrences_in_window == 3
    assert result.median_interval_seconds == 270


def test_recurring_over_hours():
    result = recurrence(incident([event(minutes=m) for m in (0, 5, 120, 300)]), WINDOW)

    assert result.pattern is RecurrencePattern.RECURRING
    assert result.max_occurrences_in_window == 2
    assert (
        result.description == "4 occurrences over 5 hours; up to 2 within 15 minutes (recurring)."
    )


def test_persistent_over_days():
    result = recurrence(incident([event(minutes=m) for m in (0, 1500, 3000)]), WINDOW)

    assert result.pattern is RecurrencePattern.PERSISTENT


def test_span_uses_all_occurrences_not_only_analyzed_events():
    record = incident([event(minutes=0), event(minutes=5)], occurrence_count=120)
    record = record.__class__(
        **{**record.__dict__, "first_seen_at": record.first_seen_at - timedelta(days=3)}
    )

    result = recurrence(record, WINDOW)
    assert result.pattern is RecurrencePattern.PERSISTENT
    assert result.events_analyzed == 2


def test_host_concentration():
    concentrated = host_distribution(incident([event(host="h1"), event(host="h1")]).events)
    spread = host_distribution(
        incident([event(host="h1"), event(host="h2"), event(host="h1")]).events
    )

    assert concentrated.concentrated and concentrated.distinct == 1
    assert not spread.concentrated
    assert [(s.value, s.count) for s in spread.top] == [("h1", 2), ("h2", 1)]


def test_errors_preceding_in_the_same_trace():
    upstream = incident([event(minutes=i * 10, trace_id=f"t{i}") for i in range(3)])
    downstream = incident(
        [event(minutes=i * 10 + 0.01, trace_id=f"t{i}", message="Order failed") for i in range(3)],
        service="checkout-service",
    )

    [link] = linked_incidents(downstream, [upstream], timedelta(minutes=2), preceding=True)
    model = link.to_model()
    assert (model.basis, model.linked_events, model.shared_trace_events) == ("TRACE", 3, 3)
    assert model.median_gap_seconds == 0.6
    assert linked_incidents(upstream, [downstream], timedelta(minutes=2), preceding=True) == []
    [following] = linked_incidents(upstream, [downstream], timedelta(minutes=2), preceding=False)
    assert following.incident.id == downstream.id


def test_time_only_link_requires_repetition():
    once = incident([event(minutes=0)], service="other")
    repeated = incident([event(minutes=m) for m in (0, 30)], service="other")
    target = incident([event(minutes=0.5), event(minutes=30.5)])

    assert linked_incidents(target, [once], timedelta(minutes=2), preceding=True) == []
    [link] = linked_incidents(target, [repeated], timedelta(minutes=2), preceding=True)
    assert link.to_model().basis == "TIME"


def test_links_ignore_other_environments():
    upstream = incident([event(trace_id="t1")], environment="production")
    target = incident([event(minutes=0.1, trace_id="t1")])

    assert linked_incidents(target, [upstream], timedelta(minutes=2), preceding=True) == []
