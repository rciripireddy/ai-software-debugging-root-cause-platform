import json
import logging

from analysis_service.logging_config import (
    CorrelationIdFilter,
    EcsJsonFormatter,
    KeyValueTextFormatter,
    correlation_id,
)


def record(**extra) -> logging.LogRecord:
    log_record = logging.LogRecord(
        "analysis", logging.INFO, __file__, 1, "Incident analysis completed", None, None
    )
    for key, value in extra.items():
        setattr(log_record, key, value)
    CorrelationIdFilter().filter(log_record)
    return log_record


def test_json_lines_use_ecs_names_and_top_level_fields():
    token = correlation_id.set("corr-1")
    try:
        line = EcsJsonFormatter().format(record(action="analysis.complete", candidateCount=2))
    finally:
        correlation_id.reset(token)

    document = json.loads(line)
    assert document["message"] == "Incident analysis completed"
    assert document["log.level"] == "INFO"
    assert document["service.name"] == "analysis-service"
    assert (document["action"], document["candidateCount"], document["correlationId"]) == (
        "analysis.complete",
        2,
        "corr-1",
    )


def test_warnings_use_the_same_level_name_as_the_java_services():
    warning = record()
    warning.levelname = "WARNING"

    assert json.loads(EcsJsonFormatter().format(warning))["log.level"] == "WARN"


def test_missing_values_are_left_out_instead_of_null():
    document = json.loads(EcsJsonFormatter().format(record(primaryCategory=None)))
    text = KeyValueTextFormatter().format(record(primaryCategory=None))

    assert "primaryCategory" not in document and "correlationId" not in document
    assert "primaryCategory" not in text and "None" not in text


def test_text_lines_append_key_values():
    text = KeyValueTextFormatter().format(record(action="analysis.start", incidentId="abc"))

    assert text.endswith('Incident analysis completed action="analysis.start" incidentId="abc"')
