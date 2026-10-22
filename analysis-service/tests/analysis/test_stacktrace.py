from analysis_service.analysis.stacktrace import parse_stack_trace

JAVA_WITH_CAUSE = """\
org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
\tat org.springframework.jdbc.datasource.DataSourceUtils.getConnection(DataSourceUtils.java:84)
\tat com.shop.inventory.StockRepository.reserve(StockRepository.java:63)
\tat com.shop.inventory.ReservationService.reserve(ReservationService.java:29)
\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)
Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:686)
\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:179)
\t... 3 more
"""

PYTHON_CHAINED = """\
Traceback (most recent call last):
  File "/usr/local/lib/python3.12/site-packages/urllib3/connection.py", line 507, in getresponse
    httplib_response = super().getresponse()
TimeoutError: The read operation timed out

The above exception was the direct cause of the following exception:

Traceback (most recent call last):
  File "/app/recommendations/api.py", line 12, in handler
    return client.fetch_similar(item)
  File "/app/recommendations/client.py", line 42, in fetch_similar
    response = self.session.get(url, timeout=5)
  File "/usr/local/lib/python3.12/site-packages/requests/adapters.py", line 713, in send
    raise ReadTimeout(e, request=request)
requests.exceptions.ReadTimeout: Read timed out. (read timeout=5)
"""


def test_java_frames_are_extracted_innermost_first():
    trace = parse_stack_trace(
        "java.net.SocketTimeoutException: Read timed out\n"
        "\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)\n"
        "\tat com.example.payment.PaymentGateway.authorize(PaymentGateway.java:142)\n"
    )

    assert trace.language == "java"
    exception = trace.exceptions[0]
    assert (exception.type, exception.message) == (
        "java.net.SocketTimeoutException",
        "Read timed out",
    )
    framework, application = exception.frames
    assert framework.class_name == "sun.nio.ch.NioSocketImpl"  # module prefix removed
    assert not framework.application
    assert application.class_name == "com.example.payment.PaymentGateway"
    assert application.module == "com.example.payment"
    assert application.function == "authorize"
    assert (application.file, application.line) == ("PaymentGateway.java", 142)
    assert application.application
    assert application.display == "PaymentGateway.authorize (PaymentGateway.java:142)"
    assert application.label == "PaymentGateway.authorize"


def test_java_cause_chain_and_application_frames():
    trace = parse_stack_trace(JAVA_WITH_CAUSE)

    assert [e.type for e in trace.exceptions] == [
        "org.springframework.jdbc.CannotGetJdbcConnectionException",
        "java.sql.SQLTransientConnectionException",
    ]
    assert trace.root_cause.message == "HikariPool-1 - Connection is not available"
    # The root cause section has only library frames; application frames come from the
    # enclosing exception, without Spring and Hikari frames.
    assert [f.label for f in trace.application_frames()] == [
        "StockRepository.reserve",
        "ReservationService.reserve",
    ]
    assert not trace.raised_in_application_code


def test_framework_frames_are_filtered_deterministically():
    trace = parse_stack_trace(
        "java.lang.IllegalStateException: boom\n"
        "\tat org.springframework.util.Assert.state(Assert.java:79)\n"
        "\tat com.fasterxml.jackson.databind.ObjectMapper.readValue(ObjectMapper.java:3)\n"
        "\tat com.shop.cart.CartService.add(CartService.java:20)\n"
        "\tat jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)\n"
        "\tat java.base/java.lang.Thread.run(Native Method)\n"
    )

    assert [f.key for f in trace.application_frames()] == ["com.shop.cart.CartService.add"]
    native = trace.exceptions[0].frames[-1]
    assert (native.file, native.line) == (None, None)


def test_application_frames_are_limited_and_unique():
    frames = "".join(f"\tat com.shop.Step{i}.run(Step{i}.java:{i})\n" for i in range(8))
    trace = parse_stack_trace("java.lang.RuntimeException: x\n" + frames + frames)

    assert len(trace.application_frames(limit=5)) == 5
    assert len({f.key for f in trace.application_frames(limit=10)}) == 8


def test_exception_raised_in_application_code():
    trace = parse_stack_trace(
        "java.lang.NullPointerException\n"
        "\tat com.shop.catalog.SkuFormatter.format(SkuFormatter.java:19)"
    )

    assert trace.raised_in_application_code
    assert trace.exceptions[0].message is None


def test_python_chained_traceback():
    trace = parse_stack_trace(PYTHON_CHAINED)

    assert trace.language == "python"
    assert [e.type for e in trace.exceptions] == ["requests.exceptions.ReadTimeout", "TimeoutError"]
    reported = trace.exceptions[0]
    assert reported.message == "Read timed out. (read timeout=5)"
    # innermost first: the library frame that raised, then the application call sites
    assert [f.function for f in reported.frames] == ["send", "fetch_similar", "handler"]
    assert [f.label for f in trace.application_frames()] == [
        "recommendations/client.py in fetch_similar",
        "recommendations/api.py in handler",
    ]
    assert reported.frames[1].line == 42


def test_python_simple_traceback():
    trace = parse_stack_trace(
        "Traceback (most recent call last):\n"
        '  File "/app/pricing/calculator.py", line 31, in total\n'
        '    rate = self.rates[item["region"]]\n'
        "KeyError: 'eu-north'\n"
    )

    assert trace.exceptions[0].type == "KeyError"
    assert trace.raised_in_application_code


def test_missing_or_empty_trace():
    for text in (None, "", "   \n  "):
        trace = parse_stack_trace(text)
        assert trace.exceptions == ()
        assert trace.application_frames() == ()
        assert trace.root_cause is None


def test_text_that_is_not_a_trace_has_no_frames():
    trace = parse_stack_trace("Something went wrong while exporting the report")

    assert trace.all_frames == ()
    assert trace.language == "unknown"


def test_header_without_frames_still_gives_exception():
    trace = parse_stack_trace("java.lang.OutOfMemoryError: Java heap space")

    assert trace.exceptions[0].type == "java.lang.OutOfMemoryError"
    assert trace.all_frames == ()
