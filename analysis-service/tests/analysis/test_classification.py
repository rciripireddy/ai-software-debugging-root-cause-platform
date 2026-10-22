import pytest
from factories import event

from analysis_service.analysis.classification import SignalKind, assess, primary_category
from analysis_service.analysis.features import extract_features
from analysis_service.models.analysis import Category


def classify(**fields) -> Category:
    return primary_category([extract_features(event(**fields))])


def test_timeout():
    assert classify() is Category.TIMEOUT


def test_python_timeout():
    trace = (
        "Traceback (most recent call last):\n"
        '  File "/app/reco/client.py", line 42, in fetch\n'
        "    response = session.get(url, timeout=5)\n"
        '  File "/usr/local/lib/python3.12/site-packages/requests/adapters.py", line 713, in send\n'
        "    raise ReadTimeout(e, request=request)\n"
        "requests.exceptions.ReadTimeout: Read timed out. (read timeout=5)"
    )
    assert (
        classify(
            exception_type="requests.exceptions.ReadTimeout",
            stack_trace=trace,
            message="Could not load recommendations",
            source=None,
        )
        is Category.TIMEOUT
    )


def test_database_connectivity_wins_over_timeout_wording():
    trace = (
        "org.springframework.jdbc.CannotGetJdbcConnectionException: "
        "Failed to obtain JDBC Connection\n"
        "\tat com.shop.inventory.StockRepository.reserve(StockRepository.java:63)\n"
        "Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not "
        "available, request timed out after 30000ms\n"
        "\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:179)"
    )
    features = [
        extract_features(
            event(
                exception_type="org.springframework.jdbc.CannotGetJdbcConnectionException",
                stack_trace=trace,
                message="Unable to reserve stock",
                source="com.shop.inventory.StockRepository",
            )
        )
    ]

    assessments = {a.category: a for a in assess(features)}
    assert primary_category(features) is Category.DATABASE
    assert assessments[Category.DATABASE].points == 4
    assert assessments[Category.TIMEOUT].points == 1  # only the message mentions "timed out"


def test_application_code_exception():
    trace = (
        'java.lang.NullPointerException: Cannot invoke "String.length()" because "sku" is null\n'
        "\tat com.shop.catalog.SkuFormatter.format(SkuFormatter.java:19)\n"
        "\tat com.shop.catalog.ProductController.show(ProductController.java:41)"
    )
    features = [
        extract_features(
            event(
                exception_type="java.lang.NullPointerException",
                stack_trace=trace,
                message="Failed to render product page",
                source=None,
            )
        )
    ]

    assessment = assess(features)[0]
    assert assessment.category is Category.APPLICATION_CODE
    assert (
        assessment.most_common(SignalKind.COMPONENT) == "SkuFormatter.format (SkuFormatter.java:19)"
    )


def test_authentication():
    trace = (
        "io.jsonwebtoken.ExpiredJwtException: JWT expired at 2026-10-14T08:00:00Z\n"
        "\tat io.jsonwebtoken.impl.DefaultJwtParser.parse(DefaultJwtParser.java:682)\n"
        "\tat com.shop.account.JwtTokenValidator.validate(JwtTokenValidator.java:38)"
    )
    assert (
        classify(
            exception_type="io.jsonwebtoken.ExpiredJwtException",
            stack_trace=trace,
            message="Request rejected",
            source=None,
        )
        is Category.AUTHENTICATION
    )


def test_authentication_from_message_and_component_only():
    assert (
        classify(
            exception_type="com.shop.GatewayRejectedException",
            stack_trace=None,
            message="Identity provider answered HTTP 401: invalid bearer token",
            source="com.shop.account.TokenValidator",
        )
        is Category.AUTHENTICATION
    )


def test_malformed_json():
    trace = (
        "org.springframework.http.converter.HttpMessageNotReadableException: JSON parse error\n"
        "\tat com.shop.cart.CartController.update(CartController.java:64)\n"
        "Caused by: com.fasterxml.jackson.core.JsonParseException: Unexpected character ('}')\n"
        "\tat com.fasterxml.jackson.core.JsonParser._constructError(JsonParser.java:2477)"
    )
    assert (
        classify(
            exception_type="org.springframework.http.converter.HttpMessageNotReadableException",
            stack_trace=trace,
            message="Rejected cart update",
            source=None,
        )
        is Category.SERIALIZATION
    )


def test_configuration():
    trace = (
        "org.springframework.beans.factory.BeanCreationException: Error creating bean\n"
        "\tat org.springframework.beans.factory.support.AbstractBeanFactory.getBean("
        "AbstractBeanFactory.java:1)\n"
        "Caused by: java.lang.IllegalArgumentException: Could not resolve placeholder 'api.key'\n"
        "\tat org.springframework.util.PropertyPlaceholderHelper.parse("
        "PropertyPlaceholderHelper.java:1)"
    )
    assert (
        classify(
            exception_type="org.springframework.beans.factory.BeanCreationException",
            stack_trace=trace,
            message="Startup failed",
            source=None,
        )
        is Category.CONFIGURATION
    )


def test_resource_exhaustion():
    assert (
        classify(
            exception_type="java.lang.OutOfMemoryError",
            stack_trace="java.lang.OutOfMemoryError: Java heap space",
            message="Report generation aborted",
            source=None,
        )
        is Category.RESOURCE_EXHAUSTION
    )


def test_downstream_http_error():
    assert (
        classify(
            exception_type="org.springframework.web.client.HttpServerErrorException$ServiceUnavailable",
            stack_trace="org.springframework.web.client."
            "HttpServerErrorException$ServiceUnavailable: "
            "503 Service Unavailable\n"
            "\tat com.shop.labels.CarrierApiClient.createLabel(CarrierApiClient.java:58)",
            message="Label creation failed",
            source="com.shop.labels.CarrierApiClient",
        )
        is Category.DOWNSTREAM_DEPENDENCY
    )


@pytest.mark.parametrize(
    ("exception_type", "message"),
    [
        ("com.shop.loyalty.PointsCalculationException", "Loyalty points could not be calculated"),
        (None, "Something unexpected happened"),
    ],
)
def test_unknown_when_no_rule_matches(exception_type, message):
    assert (
        classify(exception_type=exception_type, message=message, stack_trace=None, source=None)
        is Category.UNKNOWN
    )


def test_custom_exception_raised_in_application_code_is_not_enough():
    trace = (
        "com.shop.loyalty.PointsCalculationException: failed\n"
        "\tat com.shop.loyalty.PointsCalculator.calculate(PointsCalculator.java:71)"
    )
    features = [
        extract_features(
            event(
                exception_type="com.shop.loyalty.PointsCalculationException",
                stack_trace=trace,
                message="Loyalty failure",
                source=None,
            )
        )
    ]

    assessment = assess(features)[0]
    assert assessment.category is Category.APPLICATION_CODE
    assert assessment.points == 1
    assert not assessment.qualifies
    assert primary_category(features) is Category.UNKNOWN


def test_kind_counts_only_when_matched_in_at_least_half_of_the_events():
    timeouts = [extract_features(event(minutes=i)) for i in range(1)]
    others = [
        extract_features(
            event(
                minutes=10 + i,
                exception_type="com.shop.OtherException",
                message="Other problem",
                stack_trace=None,
                source=None,
            )
        )
        for i in range(3)
    ]

    timeout = next(a for a in assess(timeouts + others) if a.category is Category.TIMEOUT)
    assert timeout.count(SignalKind.EXCEPTION_TYPE) == 1
    assert not timeout.counts_for(SignalKind.EXCEPTION_TYPE)
    assert timeout.points == 0
