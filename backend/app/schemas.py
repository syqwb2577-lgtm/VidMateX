"""Pydantic models matching the frozen VidMateX backend contract (CONTRACTS §2).

Field names are camelCase to match the Kotlin @Serializable models in
core/model/Models.kt exactly. The Android Json config sets ignoreUnknownKeys=true,
so the extra top-level fields `downloadAvailable` and `message` on ExtractResponse
are safe (they are mandated by CONTRACTS §2 for platform-not-permitted responses).
"""
from __future__ import annotations

from enum import Enum
from typing import Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field

MAX_URL_LEN = 2048


# --- Enums (values match Kotlin enum names exactly) ---

class SourceType(str, Enum):
    YOUTUBE = "YOUTUBE"
    FACEBOOK = "FACEBOOK"
    TIKTOK = "TIKTOK"
    DIRECT_URL = "DIRECT_URL"
    UNKNOWN = "UNKNOWN"


class SubtitleKind(str, Enum):
    OFFICIAL = "OFFICIAL"
    CREATOR_PROVIDED = "CREATOR_PROVIDED"
    HUMAN = "HUMAN"
    AUTOMATIC = "AUTOMATIC"
    TRANSLATED = "TRANSLATED"


class SubtitleFormat(str, Enum):
    SRT = "SRT"
    VTT = "VTT"
    ASS = "ASS"


class MediaKind(str, Enum):
    VIDEO = "VIDEO"
    AUDIO = "AUDIO"


class ErrorCode(str, Enum):
    INVALID_URL = "INVALID_URL"
    SSRF_BLOCKED = "SSRF_BLOCKED"
    PLATFORM_NOT_SUPPORTED = "PLATFORM_NOT_SUPPORTED"
    PLATFORM_DOWNLOAD_NOT_PERMITTED = "PLATFORM_DOWNLOAD_NOT_PERMITTED"
    UPSTREAM_ERROR = "UPSTREAM_ERROR"
    RATE_LIMITED = "RATE_LIMITED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    # Additional code only emitted when RESOLVER_API_KEY is enabled and the
    # request fails authentication. Not in the frozen list; documented as such.
    UNAUTHORIZED = "UNAUTHORIZED"


# --- Core response models ---

class VideoMetadata(BaseModel):
    sourceType: SourceType
    sourceUrl: str
    sourceId: str
    title: str
    author: Optional[str] = None
    durationSec: Optional[int] = None
    thumbnailUrl: Optional[str] = None
    description: Optional[str] = None


class MediaTrack(BaseModel):
    trackId: str
    kind: MediaKind
    container: str  # mp4, webm, m4a, mp3
    heightPx: Optional[int] = None
    widthPx: Optional[int] = None
    fps: Optional[int] = None
    vcodec: Optional[str] = None
    acodec: Optional[str] = None
    bitrateBps: Optional[int] = None
    sampleRate: Optional[int] = None
    estimatedBytes: Optional[int] = None
    language: Optional[str] = None
    resumable: bool = False


class SubtitleTrack(BaseModel):
    trackId: str
    languageCode: str
    languageName: str
    kind: SubtitleKind
    isTranslatable: bool = True
    defaultFormat: SubtitleFormat = SubtitleFormat.VTT


# --- Request / response envelopes ---

class ExtractRequest(BaseModel):
    # Unknown/extra request fields are ignored consistently (Android sends a
    # strict subset; tolerant parsing avoids 400s over harmless additions).
    model_config = ConfigDict(extra="ignore")

    url: str = Field(..., min_length=1, max_length=MAX_URL_LEN)


class ExtractResponse(BaseModel):
    metadata: VideoMetadata
    formats: List[MediaTrack] = Field(default_factory=list)
    subtitles: List[SubtitleTrack] = Field(default_factory=list)
    streamUrls: Dict[str, str] = Field(default_factory=dict)
    # Extra top-level fields mandated by CONTRACTS §2 for platform responses.
    # Safe on Android because ignoreUnknownKeys=true.
    downloadAvailable: bool = Field(
        default=True,
        description=(
            "False for platform (YouTube/Facebook/TikTok) metadata-only responses: "
            "no downloadable stream URL is returned and the platform Terms of "
            "Service restrict downloading. True only for probed direct media URLs "
            "or the operator-enabled opt-in YTDLP_PATH branch."
        ),
    )
    message: Optional[str] = Field(
        default=None,
        description=(
            "Compliance/ToS note, e.g. the platform download-not-permitted "
            "message shown when downloadAvailable=false."
        ),
    )


class SubtitleRequest(BaseModel):
    # Extra request fields ignored consistently (same policy as ExtractRequest).
    model_config = ConfigDict(extra="ignore")

    url: str = Field(..., min_length=1, max_length=MAX_URL_LEN)
    trackId: str = Field(..., min_length=1, max_length=512)
    format: str = Field(..., pattern="^(srt|vtt|ass)$")


class SubtitleResponse(BaseModel):
    content: str
    format: str  # actual format of returned content (lowercase: srt|vtt|ass)


class ErrorDetail(BaseModel):
    code: str
    message: str


class ErrorEnvelope(BaseModel):
    error: ErrorDetail


class HealthResponse(BaseModel):
    status: str = "ok"
    version: str
    resolvers: List[str]
