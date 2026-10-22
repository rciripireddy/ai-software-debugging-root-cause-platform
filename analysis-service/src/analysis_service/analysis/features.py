"""Features of one event: exception chain, normalized message, stack frames and source."""

from dataclasses import dataclass
from functools import cached_property

from analysis_service.analysis.normalization import (
    informative_tokens,
    normalize_exception_type,
    normalize_message,
    normalize_source,
    simple_class_name,
)
from analysis_service.analysis.stacktrace import Frame, StackTrace, parse_stack_trace
from analysis_service.models.records import EventRecord

TOP_APPLICATION_FRAMES = 5


@dataclass(frozen=True)
class EventFeatures:
    event: EventRecord
    trace: StackTrace

    @cached_property
    def exception_type(self) -> str | None:
        """The reported exception type, or the outermost one in the stack trace."""
        reported = normalize_exception_type(self.event.exception_type)
        if reported:
            return reported.qualified
        return self.trace.exceptions[0].type if self.trace.exceptions else None

    @cached_property
    def exception_chain(self) -> tuple[str, ...]:
        """Reported exception first, then each distinct type in the trace down to the root cause."""
        chain: list[str] = []
        for name in (self.exception_type, *(e.type for e in self.trace.exceptions)):
            if name and name not in chain:
                chain.append(name)
        return tuple(chain)

    @property
    def exception_simple_name(self) -> str | None:
        return simple_class_name(self.exception_type) if self.exception_type else None

    @cached_property
    def raw_text(self) -> str:
        """Event message and exception messages, lower-cased, for rule matching."""
        parts = [self.event.message, *(e.message for e in self.trace.exceptions if e.message)]
        return "\n".join(part for part in parts if part).lower()

    @cached_property
    def normalized_message(self) -> str:
        return normalize_message(self.event.message)

    @cached_property
    def message_tokens(self) -> list[str]:
        """Informative words of the event message and the root-cause message."""
        tokens = informative_tokens(self.normalized_message)
        root = self.trace.root_cause
        if root and root.message:
            tokens += informative_tokens(normalize_message(root.message))
        return tokens

    @cached_property
    def source(self) -> str | None:
        return normalize_source(self.event.source)

    @cached_property
    def application_frames(self) -> tuple[Frame, ...]:
        return self.trace.application_frames(TOP_APPLICATION_FRAMES)

    @property
    def top_application_frame(self) -> Frame | None:
        return self.application_frames[0] if self.application_frames else None

    @property
    def has_stack_trace(self) -> bool:
        return bool(self.trace.all_frames)

    @cached_property
    def component_names(self) -> tuple[str, ...]:
        """Class/module names that identify the code involved: all frames plus the source."""
        names = [frame.qualified_class for frame in self.trace.all_frames]
        if self.source:
            names.append(self.source)
        return tuple(dict.fromkeys(names))


def extract_features(event: EventRecord) -> EventFeatures:
    return EventFeatures(event=event, trace=parse_stack_trace(event.stack_trace))
