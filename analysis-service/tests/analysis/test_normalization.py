import pytest

from analysis_service.analysis.normalization import (
    informative_tokens,
    normalize_exception_type,
    normalize_message,
    normalize_source,
    token_cosine,
)


@pytest.mark.parametrize(
    ("first", "second"),
    [
        # UUIDs
        (
            "Order 3f2b8c1e-9a4d-4e5f-8b6a-1c2d3e4f5a6b not found",
            "Order 0a1b2c3d-4e5f-4a6b-9c8d-7e6f5a4b3c2d not found",
        ),
        # numeric database IDs
        ("Customer 1234 has no default address", "Customer 98765 has no default address"),
        # timestamps, dates and times
        ("Lock held since 2026-10-14T10:02:11.204Z", "Lock held since 2026-10-15 08:00:00+02:00"),
        ("Batch for 2026-10-14 failed at 10:02:11", "Batch for 2026-10-15 failed at 23:59:59"),
        # request IDs
        ("Upstream rejected requestId=req-8f3a9c21", "Upstream rejected requestId=req-11ab07ff"),
        # memory addresses and Java identity hashes
        ("Buffer at 0x7ffd5e8c1a20 freed twice", "Buffer at 0x55d0c2e3b4f0 freed twice"),
        ("Stale handle com.shop.Session@1b6d3586", "Stale handle com.shop.Session@4554617c"),
        # IP addresses and ports
        ("Cannot reach 10.0.3.17:5432", "Cannot reach 192.168.1.20:5433"),
        # long hexadecimal tokens
        (
            "Checksum 9f86d081884c7d659a2feaa0c55ad015 mismatch",
            "Checksum 2c26b46b68ffc68ff99b453c1d304134 mismatch",
        ),
        # URL query values
        (
            "GET https://api.example.com/v1/items?id=17&lang=en returned 503",
            "GET https://api.example.com/v1/items?id=99&lang=de returned 503",
        ),
        # durations and pool numbers
        (
            "HikariPool-1 - request timed out after 30000ms",
            "HikariPool-2 - request timed out after 15000ms",
        ),
    ],
)
def test_dynamic_values_do_not_make_messages_different(first, second):
    assert normalize_message(first) == normalize_message(second)


def test_placeholders_name_the_kind_of_value():
    normalized = normalize_message(
        "Order 3f2b8c1e-9a4d-4e5f-8b6a-1c2d3e4f5a6b for customer 1234 at 2026-10-14T10:02:11Z "
        "from 10.0.3.17"
    )

    assert normalized == "order <uuid> for customer <num> at <timestamp> from <ip>"


@pytest.mark.parametrize(
    ("first", "second"),
    [
        ("Connection refused", "Connection reset"),
        ("HTTP 401 from identity provider", "HTTP 503 from identity provider"),
        ("Payment service returned 502", "Payment service returned 504"),
        ("Column 'email' cannot be null", "Column 'name' cannot be null"),
        ("Insufficient funds", "Card declined"),
        ("Unsupported encoding utf8", "Unsupported encoding utf16"),
    ],
)
def test_genuinely_different_messages_stay_different(first, second):
    assert normalize_message(first) != normalize_message(second)


def test_status_code_kept_only_in_http_context():
    assert normalize_message("Gateway answered 503 Service Unavailable") == (
        "gateway answered 503 service unavailable"
    )
    assert normalize_message("Processed 503 records") == "processed <num> records"


def test_words_mentioning_ids_are_not_swallowed():
    assert normalize_message("Missing request id header") == "missing request id header"


def test_digits_inside_words_are_kept():
    assert normalize_message("sha256 digest of abc123 differs") == "sha256 digest of abc123 differs"


def test_empty_message():
    assert normalize_message(None) == ""
    assert normalize_message("   ") == ""


@pytest.mark.parametrize(
    ("raw", "qualified", "simple"),
    [
        (
            "java.net.SocketTimeoutException",
            "java.net.SocketTimeoutException",
            "SocketTimeoutException",
        ),
        ("Caused by: java.sql.SQLException: boom", "java.sql.SQLException", "SQLException"),
        ("  KeyError  ", "KeyError", "KeyError"),
        ("requests.exceptions.ReadTimeout:", "requests.exceptions.ReadTimeout", "ReadTimeout"),
    ],
)
def test_exception_names(raw, qualified, simple):
    name = normalize_exception_type(raw)

    assert (name.qualified, name.simple) == (qualified, simple)


def test_missing_exception_name():
    assert normalize_exception_type(None) is None
    assert normalize_exception_type("  ") is None


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("com.shop.payment.GatewayClient", "com.shop.payment.GatewayClient"),
        ("com.shop.payment.GatewayClient$$SpringCGLIB$$0", "com.shop.payment.GatewayClient"),
        ("com.shop.Worker$$Lambda$123/0x0000000800c4b440", "com.shop.Worker"),
        ("com.shop.Worker$1", "com.shop.Worker"),
        ("com.shop.Outer$Inner", "com.shop.Outer$Inner"),
        ("", None),
        (None, None),
    ],
)
def test_source_names(raw, expected):
    assert normalize_source(raw) == expected


def test_generic_error_words_are_not_informative():
    assert informative_tokens(normalize_message("Request failed: unexpected error")) == []
    assert (
        token_cosine(
            informative_tokens(normalize_message("Payment failed")),
            informative_tokens(normalize_message("Order export failed")),
        )
        == 0.0
    )


def test_similar_messages_have_high_cosine():
    first = informative_tokens(normalize_message("Payment authorization failed for order 17"))
    second = informative_tokens(normalize_message("Payment authorization failed for order 99"))

    assert token_cosine(first, second) == pytest.approx(1.0)
