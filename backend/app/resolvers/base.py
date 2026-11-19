"""Resolver protocol / abstract base class.

Every resolver must implement:
  - supports(url) -> bool: quick, no-network check for whether this resolver
    handles the given URL.
  - extract(url) -> ExtractResult: full metadata + tracks + stream URLs.
  - fetch_subtitle(url, track_id, fmt) -> tuple[str, str]: download subtitle
    content; returns (content, actual_format_lowercase).

All outbound network calls MUST go through ssrf_guard.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Dict, List

from ..schemas import MediaTrack, SubtitleTrack, VideoMetadata


@dataclass
class ExtractResult:
    metadata: VideoMetadata
    formats: List[MediaTrack] = field(default_factory=list)
    subtitles: List[SubtitleTrack] = field(default_factory=list)
    streamUrls: Dict[str, str] = field(default_factory=dict)
    downloadAvailable: bool = True
    message: str | None = None


class BaseResolver(ABC):
    """Abstract resolver. Implementations are stateless and async."""

    name: str = "base"

    @abstractmethod
    def supports(self, url: str) -> bool:
        """Return True if this resolver can handle the URL (no network)."""

    @abstractmethod
    async def extract(self, url: str) -> ExtractResult:
        """Resolve metadata + tracks. Must raise a ssrf_guard.BackendError subclass on failure."""

    @abstractmethod
    async def fetch_subtitle(self, url: str, track_id: str, fmt: str) -> tuple[str, str]:
        """Return (subtitle_content, actual_format). fmt is requested srt|vtt|ass."""
