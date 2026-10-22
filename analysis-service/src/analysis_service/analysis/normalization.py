"""Normalization of exception names, messages and source names.

Dynamic values (IDs, timestamps, addresses, ...) are replaced by placeholders so that two
occurrences of the same failure compare as equal. Values that usually distinguish different
failures, such as quoted names and HTTP status codes, are kept. The rules are listed in
docs/analysis.md.
"""

import re
from collections import Counter
from dataclasses import dataclass
from math import sqrt
from urllib.parse import parse_qsl, urlsplit

_URL = re.compile(r"\b[a-z][a-z0-9+.-]*://[^\s'\"<>]+")
_KEYED_ID = re.compile(
    r"\b(request|req|trace|span|correlation|session|transaction|txn)[-_ ]?id\b"
    r"(\s*[=:#]?\s*)['\"]?(?=[\w.:-]*\d)[\w.:-]+['\"]?"
)
_UUID = re.compile(r"\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
_TIMESTAMP = re.compile(
    r"\b\d{4}-\d{2}-\d{2}[t ]\d{2}:\d{2}(?::\d{2}(?:[.,]\d+)?)?(?:z|[+-]\d{2}:?\d{2})?\b"
)
_DATE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
_TIME = re.compile(r"\b\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\b")
_MEMORY_ADDRESS = re.compile(r"\b0x[0-9a-f]+\b")
_IDENTITY_HASH = re.compile(r"(?<=[\w$])@[0-9a-f]{4,}\b")
_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?\b")
_IPV6 = re.compile(r"(?<![\w:])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![\w:])")
_LONG_HEX = re.compile(r"\b(?=[0-9a-f]*\d)[0-9a-f]{16,}\b")
_LONG_TOKEN = re.compile(r"\b(?=[\w-]*\d)(?=[\w-]*[a-z])[\w-]{24,}\b")
_NUMBER = re.compile(r"(?<![a-z0-9<])\d+(?:[.,]\d+)*")
_WHITESPACE = re.compile(r"\s+")

# Three-digit numbers kept as HTTP status codes, because "HTTP 401" and "HTTP 503" are different
# failures. A number counts as a status code after one of these words or before a reason phrase.
_STATUS_CONTEXT_BEFORE = re.compile(r"(?:http|status|code|returned|responded|response)\W{0,3}$")
_STATUS_CONTEXT_AFTER = re.compile(
    r"^\s*(?:bad request|unauthorized|forbidden|not found|conflict|too many requests|"
    r"internal server error|bad gateway|service unavailable|gateway timeout)"
)

PLACEHOLDERS = (
    "<url-query>",
    "<id>",
    "<uuid>",
    "<timestamp>",
    "<date>",
    "<time>",
    "<address>",
    "<ip>",
    "<hex>",
    "<token>",
    "<num>",
)


def normalize_message(message: str | None) -> str:
    """Lower-cases a message and replaces dynamic values with placeholders."""
    if not message:
        return ""
    text = message.lower()
    text = _URL.sub(_normalize_url, text)
    text = _KEYED_ID.sub(lambda m: f"{m.group(1)} id{m.group(2) or ' '}<id>", text)
    text = _UUID.sub("<uuid>", text)
    text = _TIMESTAMP.sub("<timestamp>", text)
    text = _DATE.sub("<date>", text)
    text = _TIME.sub("<time>", text)
    text = _MEMORY_ADDRESS.sub("<address>", text)
    text = _IDENTITY_HASH.sub("@<address>", text)
    text = _IPV4.sub("<ip>", text)
    text = _IPV6.sub("<ip>", text)
    text = _LONG_HEX.sub("<hex>", text)
    text = _LONG_TOKEN.sub("<token>", text)
    text = _NUMBER.sub(_replace_number, text)
    return _WHITESPACE.sub(" ", text).strip()


def _normalize_url(match: re.Match[str]) -> str:
    """Keeps scheme, host and path; query values become placeholders and fragments are dropped."""
    raw = match.group(0).rstrip(".,;)")
    trailing = match.group(0)[len(raw) :]
    parts = urlsplit(raw)
    url = f"{parts.scheme}://{parts.netloc}{parts.path}"
    if parts.query:
        keys = [key for key, _ in parse_qsl(parts.query, keep_blank_values=True)]
        if keys:
            url += "?" + "&".join(f"{key}=<url-query>" for key in keys)
    return url + trailing


def _replace_number(match: re.Match[str]) -> str:
    value = match.group(0)
    if len(value) == 3 and value[0] in "12345" and value.isdigit():
        before = match.string[max(0, match.start() - 20) : match.start()]
        after = match.string[match.end() : match.end() + 30]
        if _STATUS_CONTEXT_BEFORE.search(before) or _STATUS_CONTEXT_AFTER.search(after):
            return value
    return "<num>"


@dataclass(frozen=True)
class ExceptionName:
    qualified: str
    simple: str


def normalize_exception_type(name: str | None) -> ExceptionName | None:
    """Cleans an exception type name, e.g. ``"Caused by: java.net.SocketTimeoutException:"``."""
    if not name:
        return None
    cleaned = name.strip()
    for prefix in ("Caused by:", "Suppressed:"):
        if cleaned.startswith(prefix):
            cleaned = cleaned[len(prefix) :].strip()
    cleaned = cleaned.split(":", 1)[0].strip().split()[0] if cleaned else ""
    if not cleaned:
        return None
    return ExceptionName(qualified=cleaned, simple=cleaned.rsplit(".", 1)[-1])


_GENERATED_CLASS_SUFFIX = re.compile(r"\$\$.*$")
_ANONYMOUS_CLASS_SUFFIX = re.compile(r"(?:\$\d+)+$")


def normalize_source(source: str | None) -> str | None:
    """Removes generated-class suffixes: proxies (``$$SpringCGLIB$$0``), lambdas, ``$1``."""
    if not source or not source.strip():
        return None
    cleaned = _GENERATED_CLASS_SUFFIX.sub("", source.strip())
    cleaned = _ANONYMOUS_CLASS_SUFFIX.sub("", cleaned)
    return cleaned or None


def simple_class_name(qualified: str) -> str:
    return qualified.rsplit(".", 1)[-1]


# Words that appear in almost every error message. They carry no information about which
# failure occurred, so they are ignored when messages are compared.
GENERIC_WORDS = frozenset(
    """
    a an and are as at be been being but by can cannot could did do does due during error errors
    exception exceptions fail failed failing fails failure for from has have in into is it its
    no not of on or occurred processing request requests the this to unable unexpected was were
    when while with
    """.split()
)
_TOKEN = re.compile(r"[a-z][a-z0-9_]+")


def informative_tokens(normalized_text: str) -> list[str]:
    """Words of a normalized message, without placeholders and generic error vocabulary."""
    without_placeholders = normalized_text
    for placeholder in PLACEHOLDERS:
        without_placeholders = without_placeholders.replace(placeholder, " ")
    return [token for token in _TOKEN.findall(without_placeholders) if token not in GENERIC_WORDS]


def token_cosine(first: list[str], second: list[str]) -> float:
    """Cosine similarity of two token lists (bag of words); 0.0 when either is empty."""
    if not first or not second:
        return 0.0
    a, b = Counter(first), Counter(second)
    dot = sum(count * b[token] for token, count in a.items())
    norm = sqrt(sum(c * c for c in a.values())) * sqrt(sum(c * c for c in b.values()))
    return dot / norm if norm else 0.0
