"""Stack-trace parsing for Java and Python traces.

Supported formats (anything else yields a trace without frames):

- Java (``Throwable.printStackTrace``): a ``Type: message`` header,
  ``at pkg.Class.method(File.java:N)`` frames (also ``Native Method``, ``Unknown Source`` and
  Java 9+ module prefixes such as ``java.base/``), ``Caused by:`` sections and ``... N more``
  lines.
- Python (``traceback`` module): ``Traceback (most recent call last):``, ``File "path", line N,
  in function`` frames and the final ``Type: message`` line, including chained tracebacks.

Frames are returned innermost first (where the exception was raised), for both languages.
"""

import re
from dataclasses import dataclass, field
from typing import Literal

from analysis_service.analysis.normalization import normalize_exception_type

# Frames from these packages belong to the JVM, frameworks or libraries, not to the application.
# The list is fixed so that results are reproducible; see docs/analysis.md.
JAVA_FRAMEWORK_PREFIXES = (
    "java.",
    "javax.",
    "jakarta.",
    "jdk.",
    "sun.",
    "com.sun.",
    "kotlin.",
    "kotlinx.",
    "scala.",
    "org.springframework.",
    "org.hibernate.",
    "org.apache.",
    "org.eclipse.",
    "org.glassfish.",
    "org.jboss.",
    "org.postgresql.",
    "org.mariadb.",
    "org.h2.",
    "org.flywaydb.",
    "org.slf4j.",
    "org.junit.",
    "org.mockito.",
    "org.aspectj.",
    "org.json.",
    "com.zaxxer.",
    "com.mysql.",
    "oracle.jdbc.",
    "com.fasterxml.",
    "tools.jackson.",
    "com.google.",
    "io.netty.",
    "reactor.",
    "io.reactivex.",
    "io.grpc.",
    "io.micrometer.",
    "io.opentelemetry.",
    "io.undertow.",
    "io.jsonwebtoken.",
    "com.auth0.",
    "com.nimbusds.",
    "okhttp3.",
    "okio.",
    "retrofit2.",
    "feign.",
    "io.github.resilience4j.",
    "ch.qos.logback.",
    "com.mongodb.",
    "redis.clients.",
    "io.lettuce.",
    "net.bytebuddy.",
    "lombok.",
)

# Python frames whose file path contains one of these are library or interpreter frames.
PYTHON_LIBRARY_PATH_MARKERS = (
    "site-packages",
    "dist-packages",
    "/lib/python",
    "\\lib\\python",
    "<frozen",
    "/usr/lib/python",
)

_JAVA_FRAME = re.compile(r"^\s*at\s+(?:\S*/)?(?P<method>[\w$.<>-]+)\((?P<location>[^)]*)\)\s*$")
_JAVA_HEADER = re.compile(
    r"^(?P<prefix>Caused by: |Suppressed: )?(?P<type>[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)"
    r"(?::\s?(?P<message>.*))?$"
)
_JAVA_OMITTED = re.compile(r"^\s*\.\.\.\s+\d+\s+(?:more|common frames omitted)\s*$")
_PYTHON_TRACEBACK = "Traceback (most recent call last):"
_PYTHON_FRAME = re.compile(r'^\s*File "(?P<file>[^"]+)", line (?P<line>\d+)(?:, in (?P<func>.+))?$')
_PYTHON_EXCEPTION = re.compile(
    r"^(?P<type>[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)(?::\s?(?P<message>.*))?$"
)


@dataclass(frozen=True)
class Frame:
    language: Literal["java", "python"]
    module: str  # Java: package; Python: file path
    class_name: str | None  # Java only
    function: str
    file: str | None
    line: int | None
    application: bool

    @property
    def key(self) -> str:
        """Identity of the code location without the line number."""
        if self.language == "java":
            return f"{self.class_name}.{self.function}"
        return f"{self.module}:{self.function}"

    @property
    def label(self) -> str:
        """Short name without the line number, e.g. ``GatewayClient.authorize``."""
        if self.language == "java":
            return f"{(self.class_name or '').rsplit('.', 1)[-1]}.{self.function}"
        return f"{_short_path(self.module)} in {self.function}"

    @property
    def display(self) -> str:
        if self.language == "java":
            simple = (self.class_name or "").rsplit(".", 1)[-1]
            location = f"{self.file}:{self.line}" if self.file and self.line else self.file
            return f"{simple}.{self.function}" + (f" ({location})" if location else "")
        return f"{_short_path(self.module)}:{self.line} in {self.function}"

    @property
    def qualified_class(self) -> str:
        return self.class_name or self.module


@dataclass(frozen=True)
class ThrownException:
    type: str
    message: str | None
    frames: tuple[Frame, ...] = ()


@dataclass(frozen=True)
class StackTrace:
    language: Literal["java", "python", "unknown"]
    # Outermost (reported) exception first, root cause last.
    exceptions: tuple[ThrownException, ...] = field(default_factory=tuple)

    @property
    def root_cause(self) -> ThrownException | None:
        return self.exceptions[-1] if self.exceptions else None

    @property
    def all_frames(self) -> tuple[Frame, ...]:
        return tuple(frame for exception in self.exceptions for frame in exception.frames)

    def application_frames(self, limit: int = 5) -> tuple[Frame, ...]:
        """Application frames, innermost first, starting at the root cause.

        A ``Caused by`` section is often truncated (``... N more``) or contains only library
        frames; the application frames of the enclosing exceptions then follow.
        """
        seen: set[str] = set()
        frames: list[Frame] = []
        for exception in reversed(self.exceptions):
            for frame in exception.frames:
                if frame.application and frame.key not in seen:
                    seen.add(frame.key)
                    frames.append(frame)
        return tuple(frames[:limit])

    @property
    def raised_in_application_code(self) -> bool:
        """True when the root cause's innermost frame is application code."""
        root = self.root_cause
        return bool(root and root.frames and root.frames[0].application)


EMPTY_TRACE = StackTrace(language="unknown")


def parse_stack_trace(text: str | None) -> StackTrace:
    if not text or not text.strip():
        return EMPTY_TRACE
    lines = text.replace("\r\n", "\n").split("\n")
    if any(line.strip() == _PYTHON_TRACEBACK for line in lines):
        return _parse_python(lines)
    return _parse_java(lines)


def _parse_java(lines: list[str]) -> StackTrace:
    exceptions: list[ThrownException] = []
    current_type: str | None = None
    current_message: str | None = None
    frames: list[Frame] = []
    saw_frame = False

    def close() -> None:
        if current_type:
            exceptions.append(ThrownException(current_type, current_message, tuple(frames)))

    for raw in lines:
        line = raw.rstrip()
        if not line.strip() or _JAVA_OMITTED.match(line):
            continue
        frame_match = _JAVA_FRAME.match(line)
        if frame_match:
            saw_frame = True
            if current_type:
                frames.append(_java_frame(frame_match["method"], frame_match["location"]))
            continue
        header = _JAVA_HEADER.match(line.strip())
        starts_section = line.lstrip().startswith(("Caused by:", "Suppressed:"))
        if header and (starts_section or current_type is None):
            if header["prefix"] == "Suppressed: ":
                # Suppressed exceptions are secondary; their frames are not part of the cause chain.
                close()
                current_type, current_message, frames = None, None, []
                continue
            close()
            name = normalize_exception_type(header["type"])
            current_type = name.qualified if name else header["type"]
            current_message = (header["message"] or "").strip() or None
            frames = []
    close()
    if not exceptions:
        return EMPTY_TRACE
    return StackTrace(language="java" if saw_frame else "unknown", exceptions=tuple(exceptions))


def _java_frame(qualified_method: str, location: str) -> Frame:
    class_name, _, method = qualified_method.rpartition(".")
    file, line = None, None
    if ":" in location:
        file, _, number = location.rpartition(":")
        line = int(number) if number.isdigit() else None
    elif location and location not in ("Native Method", "Unknown Source"):
        file = location
    return Frame(
        language="java",
        module=class_name.rpartition(".")[0],
        class_name=class_name,
        function=method,
        file=file,
        line=line,
        application=not class_name.startswith(JAVA_FRAMEWORK_PREFIXES),
    )


def _parse_python(lines: list[str]) -> StackTrace:
    """Python prints chained exceptions oldest first; the last section is the reported one."""
    sections: list[ThrownException] = []
    frames: list[Frame] = []
    in_traceback = False
    for raw in lines:
        line = raw.rstrip()
        if line.strip() == _PYTHON_TRACEBACK:
            in_traceback, frames = True, []
            continue
        if not in_traceback:
            continue
        frame_match = _PYTHON_FRAME.match(line)
        if frame_match:
            path = frame_match["file"]
            frames.append(
                Frame(
                    language="python",
                    module=path.replace("\\", "/"),
                    class_name=None,
                    function=(frame_match["func"] or "<module>").strip(),
                    file=path.replace("\\", "/").rsplit("/", 1)[-1],
                    line=int(frame_match["line"]),
                    application=not any(
                        marker in path.lower() for marker in PYTHON_LIBRARY_PATH_MARKERS
                    ),
                )
            )
            continue
        if line.startswith((" ", "\t")):
            continue  # source line shown under a frame, or a caret marker
        exception_match = _PYTHON_EXCEPTION.match(line.strip())
        if exception_match:
            sections.append(
                ThrownException(
                    type=exception_match["type"],
                    message=(exception_match["message"] or "").strip() or None,
                    frames=tuple(reversed(frames)),
                )
            )
            in_traceback, frames = False, []
    if not sections:
        return EMPTY_TRACE
    return StackTrace(language="python", exceptions=tuple(reversed(sections)))


def _short_path(path: str) -> str:
    parts = path.split("/")
    return "/".join(parts[-2:]) if len(parts) > 2 else path
