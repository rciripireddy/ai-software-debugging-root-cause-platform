"""Rule-based classification of failures into root-cause categories.

Each category has up to three kinds of rules, matched against every analyzed event:

- EXCEPTION_TYPE: any exception type in the event's chain (reported type and ``Caused by`` types);
- MESSAGE: the event message and the exception messages from the stack trace (lower-cased);
- COMPONENT: class/module names of all stack frames and the event source. For APPLICATION_CODE
  this is instead "the root cause was raised by an application frame".

A kind counts for a category when it matches in at least half of the analyzed events. The
categories are listed in tie-break order: when two categories score equally, the more specific
one (earlier in the list) is ranked first, and both get at most MEDIUM confidence.
"""

import re
from collections import Counter
from dataclasses import dataclass, field
from enum import StrEnum
from uuid import UUID

from analysis_service.analysis.features import EventFeatures
from analysis_service.models.analysis import Category


class SignalKind(StrEnum):
    EXCEPTION_TYPE = "EXCEPTION_TYPE"
    MESSAGE = "MESSAGE"
    COMPONENT = "COMPONENT"


# Points a kind contributes when it matches in at least half of the events.
KIND_POINTS = {SignalKind.EXCEPTION_TYPE: 2, SignalKind.MESSAGE: 1, SignalKind.COMPONENT: 1}
# A category needs at least this many points to become a candidate.
MINIMUM_CATEGORY_POINTS = 2


def _rx(pattern: str) -> re.Pattern[str]:
    return re.compile(pattern, re.IGNORECASE)


@dataclass(frozen=True)
class CategoryRules:
    category: Category
    label: str
    exception_types: re.Pattern[str] | None = None
    messages: re.Pattern[str] | None = None
    components: re.Pattern[str] | None = None


RULES: tuple[CategoryRules, ...] = (
    CategoryRules(
        Category.DATABASE,
        "database or database connectivity",
        exception_types=_rx(
            r"SQL\w*Exception|PSQLException|CannotGetJdbcConnection|CannotCreateTransaction|"
            r"DataAccessResourceFailure|JDBCConnectionException|QueryTimeoutException|"
            r"DeadlockLoser|CannotAcquireLock|TransientDataAccessResource|PessimisticLockingFailure|"
            r"DataIntegrityViolation|BadSqlGrammar|org\.hibernate\.exception\.|"
            r"(?:jakarta|javax)\.persistence\.\w*Exception|Mongo\w*Exception|RedisConnectionFailure|"
            r"JedisConnection|psycopg2?\.|sqlalchemy\.exc\.|asyncpg\.|pymysql\.|pymongo\.errors\.|"
            r"(?:^|\.)(?:OperationalError|DatabaseError|InterfaceError|IntegrityError)$"
        ),
        messages=_rx(
            r"connection is not available|hikaripool|connection pool|"
            r"(?:jdbc|database|db) connection|failed to obtain jdbc connection|"
            r"could not (?:get|obtain|open|acquire) (?:a )?(?:jdbc |database )?connection|"
            r"connection to \S+ refused|connection to server at|too many (?:clients|connections)|"
            r"database \S+ does not exist|database (?:is )?(?:down|unavailable)|deadlock detected|"
            r"lock wait timeout|could not serialize access|terminating connection|"
            r"server closed the connection unexpectedly|the connection attempt failed|sqlstate|"
            r"\bjdbc\b|relation \S+ does not exist|duplicate key value|"
            r"violates (?:unique|foreign key|not-null) constraint"
        ),
        components=_rx(
            r"^(?:com\.zaxxer\.hikari|org\.postgresql|com\.mysql|oracle\.jdbc|org\.mariadb|org\.h2|"
            r"org\.hibernate|org\.springframework\.jdbc|org\.springframework\.orm|"
            r"org\.springframework\.dao|org\.springframework\.data\.jpa|jakarta\.persistence|"
            r"javax\.persistence|com\.mongodb|redis\.clients|io\.lettuce)\.|"
            r"(?:^|/)(?:psycopg2?|sqlalchemy|pymysql|asyncpg|pymongo|redis)/|"
            r"(?:Repository|Dao|DAO)$"
        ),
    ),
    CategoryRules(
        Category.AUTHENTICATION,
        "authentication or authorization",
        exception_types=_rx(
            r"Authentication\w*Exception|BadCredentials|AccessDenied|InsufficientAuthentication|"
            r"AuthorizationDenied|\w*Jwt\w*Exception|JWTVerificationException|TokenExpired|"
            r"SignatureException|InvalidToken|InvalidBearerToken|OAuth2\w*Exception|Unauthorized|"
            r"Forbidden|PermissionError|PermissionDenied|NotAuthenticated|ExpiredSignatureError|"
            r"InvalidSignatureError|(?:^|\.)jwt\.\w*Error"
        ),
        messages=_rx(
            r"\b(?:401|403)\b|unauthori[sz]ed|forbidden|access (?:is )?denied|permission denied|"
            r"(?:invalid|expired|missing|malformed|revoked) (?:bearer |access |refresh |id |jwt |"
            r"auth(?:entication)? )?token|token (?:has )?expired|jwt expired|"
            r"signature (?:verification )?failed|invalid (?:credentials|signature|api key)|"
            r"bad credentials|authentication (?:failed|required)|not authenticated|login failed"
        ),
        components=_rx(
            r"^(?:org\.springframework\.security|io\.jsonwebtoken|com\.auth0|com\.nimbusds|"
            r"org\.keycloak)\.|(?:^|/)(?:jwt|jose|authlib|oauthlib)/|"
            r"(?:Authentication|Jwt|Token|Security|Credential|Login|Auth)"
            r"(?:Filter|Service|Provider|Validator|Verifier|Manager|Handler|Interceptor|Decoder|"
            r"Converter|Client)?$"
        ),
    ),
    CategoryRules(
        Category.SERIALIZATION,
        "serialization or data format",
        exception_types=_rx(
            r"Json\w*Exception|JSONException|JSONDecodeError|MismatchedInput|InvalidFormatException|"
            r"UnrecognizedProperty|InvalidDefinition|HttpMessageNotReadable|HttpMessageConversion|"
            r"StreamReadException|DatabindException|Serializ\w*(?:Exception|Error)|"
            r"Deserializ\w*(?:Exception|Error)|DateTimeParseException|NumberFormatException|"
            r"(?:Un)?[Mm]arshalException|XMLStreamException|SAXParseException|"
            r"InvalidProtocolBuffer|ConversionFailedException|MessageConversionException|"
            r"UnicodeDecodeError|pydantic\S*ValidationError"
        ),
        messages=_rx(
            r"json parse error|malformed (?:json|request|payload|input|body)|"
            r"unexpected (?:character|token|end-of-input|end of (?:json )?input)|"
            r"cannot deserialize|could not (?:read|parse|deserialize) (?:json|document|payload)|"
            r"failed to (?:de)?serialize|invalid json|not valid json|"
            r"expecting (?:value|property name)|unrecognized field|unparseable date|"
            r"could not be parsed|for input string"
        ),
        components=_rx(
            r"^(?:com\.fasterxml\.jackson|tools\.jackson|com\.google\.gson|org\.json|"
            r"com\.google\.protobuf|jakarta\.xml\.bind|javax\.xml|"
            r"org\.springframework\.http\.converter)\.|"
            r"(?:^|/)(?:json|pydantic|pydantic_core|marshmallow|orjson)/|"
            r"(?:Serializer|Deserializer|Codec)$"
        ),
    ),
    CategoryRules(
        Category.CONFIGURATION,
        "configuration",
        exception_types=_rx(
            r"BeanCreationException|BeanDefinition\w*Exception|UnsatisfiedDependencyException|"
            r"NoSuchBeanDefinition|ConfigurationException|ConfigDataException|"
            r"ConfigurationProperties\w*Exception|InvalidConfigurationProperty|"
            r"properties\.bind\.BindException|MissingResourceException|ImproperlyConfigured|"
            r"ConfigError|MissingEnvironmentVariable|PlaceholderResolutionException"
        ),
        messages=_rx(
            r"could not resolve placeholder|"
            r"missing (?:required )?(?:configuration|property|properties|setting|"
            r"environment variable|config)|not configured|invalid configuration|"
            r"configuration (?:error|is invalid)|no qualifying bean|failed to bind properties|"
            r"environment variable \S+ (?:is )?(?:not set|missing)"
        ),
        components=_rx(
            r"^org\.springframework\.(?:beans|context|boot\.context\.properties|"
            r"boot\.context\.config|core\.env)\.|(?:^|/)(?:config|settings)\.py$|"
            r"(?:^|/)pydantic_settings/|(?:Config|Configuration|Properties|Settings)$"
        ),
    ),
    CategoryRules(
        Category.RESOURCE_EXHAUSTION,
        "resource exhaustion",
        exception_types=_rx(
            r"OutOfMemoryError|StackOverflowError|RejectedExecutionException|"
            r"(?:^|\.)(?:MemoryError|RecursionError)$|ResourceExhausted|BulkheadFullException"
        ),
        messages=_rx(
            r"out of memory|java heap space|gc overhead limit|metaspace|"
            r"unable to create (?:new )?native thread|too many open files|no space left on device|"
            r"disk (?:is )?full|pool (?:is )?exhausted|queue (?:is )?full|rejected from|"
            r"resources? exhausted|quota exceeded|maximum recursion depth|cannot allocate memory|"
            r"memory limit"
        ),
    ),
    CategoryRules(
        Category.TIMEOUT,
        "timeout",
        exception_types=_rx(r"time-?out|timedout"),
        messages=_rx(r"\btimed?[ -]?out\b|\btimeout\b|deadline exceeded|took too long"),
    ),
    CategoryRules(
        Category.DOWNSTREAM_DEPENDENCY,
        "downstream dependency",
        exception_types=_rx(
            r"ConnectException|NoRouteToHost|UnknownHost|HttpServerError|ResourceAccessException|"
            r"WebClientRe(?:sponse|quest)Exception|FeignException|RetryableException|"
            r"CallNotPermittedException|ServiceUnavailable|BadGateway|GatewayTimeout|"
            r"requests\.exceptions\.(?:ConnectionError|HTTPError)|"
            r"httpx\.(?:ConnectError|HTTPStatusError|RemoteProtocolError)|urllib3\.exceptions\.|"
            r"aiohttp\.Client\w*Error|StatusRuntimeException|ConnectionRefusedError"
        ),
        messages=_rx(
            r"\b(?:502|503|504)\b|bad gateway|service unavailable|gateway time-?out|\bupstream\b|"
            r"downstream|connection refused|connection reset|no route to host|unknown host|"
            r"name or service not known|temporary failure in name resolution|circuit ?breaker|"
            r"remote (?:service|host|server|end)|"
            r"i/o error on (?:get|post|put|patch|delete) request|"
            r"failed to connect to|max retries exceeded"
        ),
        components=_rx(
            r"^(?:java\.net\.(?:Socket|http\.|HttpURLConnection|PlainSocket)|sun\.nio\.ch\.|"
            r"sun\.net\.www\.|okhttp3\.|org\.apache\.hc\.|org\.apache\.http\.|reactor\.netty\.|"
            r"feign\.|org\.springframework\.web\.client\.|"
            r"org\.springframework\.web\.reactive\.function\.client\.|io\.grpc\.|"
            r"io\.github\.resilience4j\.)|(?:^|/)(?:requests|urllib3|httpx|aiohttp|grpc)/|"
            r"(?:Client|Gateway|Proxy|Connector)$|(?:^|/)\w*(?:client|gateway|proxy)\.py$"
        ),
    ),
    CategoryRules(
        Category.APPLICATION_CODE,
        "application code",
        exception_types=_rx(
            r"NullPointerException|IllegalArgumentException|IllegalStateException|"
            r"IndexOutOfBoundsException|ClassCastException|ArithmeticException|"
            r"UnsupportedOperationException|ConcurrentModificationException|"
            r"NoSuchElementException|NegativeArraySizeException|"
            r"(?:^|\.)(?:AttributeError|KeyError|TypeError|IndexError|ZeroDivisionError|NameError|"
            r"UnboundLocalError|ValueError|AssertionError|NotImplementedError)$"
        ),
        messages=_rx(
            r"cannot invoke \S+ because|\bis null\b|'nonetype' object|index \d+ out of bounds|"
            r"list index out of range|division by zero|/ by zero|cannot be cast to|"
            r"has no attribute|unsupported operand|not subscriptable"
        ),
        # Component: the root cause was raised by an application frame (see match_event).
    ),
)

RULES_BY_CATEGORY = {rules.category: rules for rules in RULES}
CATEGORY_ORDER = {rules.category: index for index, rules in enumerate(RULES)}


def match_event(features: EventFeatures) -> dict[Category, dict[SignalKind, str]]:
    """Which rule kinds match one event, per category, with the matched value."""
    result: dict[Category, dict[SignalKind, str]] = {}
    for rules in RULES:
        matches: dict[SignalKind, str] = {}
        if rules.exception_types:
            for name in features.exception_chain:
                if rules.exception_types.search(name):
                    matches[SignalKind.EXCEPTION_TYPE] = name
                    break
        if rules.messages:
            found = rules.messages.search(features.raw_text)
            if found:
                matches[SignalKind.MESSAGE] = found.group(0)
        if rules.category is Category.APPLICATION_CODE:
            root = features.trace.root_cause
            if features.trace.raised_in_application_code and root:
                matches[SignalKind.COMPONENT] = root.frames[0].display
        elif rules.components:
            for name in features.component_names:
                if rules.components.search(name):
                    matches[SignalKind.COMPONENT] = name
                    break
        if matches:
            result[rules.category] = matches
    return result


@dataclass
class CategoryAssessment:
    """How strongly the events of one incident support one category."""

    category: Category
    total_events: int
    matches: dict[SignalKind, list[tuple[UUID, str]]] = field(default_factory=dict)

    def count(self, kind: SignalKind) -> int:
        return len(self.matches.get(kind, ()))

    def counts_for(self, kind: SignalKind) -> bool:
        return self.count(kind) * 2 >= self.total_events > 0 and self.count(kind) > 0

    def most_common(self, kind: SignalKind) -> str | None:
        values = Counter(value for _, value in self.matches.get(kind, ()))
        return min(values.items(), key=lambda item: (-item[1], item[0]))[0] if values else None

    @property
    def points(self) -> int:
        return sum(points for kind, points in KIND_POINTS.items() if self.counts_for(kind))

    @property
    def event_ids(self) -> set[UUID]:
        return {event_id for matches in self.matches.values() for event_id, _ in matches}

    @property
    def qualifies(self) -> bool:
        return self.points >= MINIMUM_CATEGORY_POINTS

    @property
    def label(self) -> str:
        return RULES_BY_CATEGORY[self.category].label


def assess(features: list[EventFeatures]) -> list[CategoryAssessment]:
    """Assessments of every category with at least one match, strongest first."""
    by_category: dict[Category, CategoryAssessment] = {}
    for item in features:
        for category, matches in match_event(item).items():
            assessment = by_category.setdefault(
                category, CategoryAssessment(category, total_events=len(features))
            )
            for kind, value in matches.items():
                assessment.matches.setdefault(kind, []).append((item.event.id, value))
    return sorted(
        by_category.values(),
        key=lambda a: (-a.points, -len(a.event_ids), CATEGORY_ORDER[a.category]),
    )


def primary_category(features: list[EventFeatures]) -> Category:
    """The strongest qualifying category from the rules alone, or UNKNOWN."""
    for assessment in assess(features):
        if assessment.qualifies:
            return assessment.category
    return Category.UNKNOWN
