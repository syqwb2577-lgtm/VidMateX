"""Shared test fixtures. Env is set before app.main is imported (it reads env at import)."""
import os

# High rate limit and no auth by default; individual tests reload with different env.
os.environ["RATE_LIMIT_PER_MINUTE"] = "1000"
os.environ.pop("RESOLVER_API_KEY", None)
os.environ.pop("YTDLP_PATH", None)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402


@pytest.fixture
def client():
    from app.main import app

    return TestClient(app)


@pytest.fixture
def fake_resolver():
    """A resolver that returns canned data with no network."""
    from app.resolvers.base import BaseResolver, ExtractResult
    from app.schemas import (
        MediaKind,
        MediaTrack,
        SourceType,
        VideoMetadata,
    )

    class FakeResolver(BaseResolver):
        name = "fake"

        def supports(self, url):
            return True

        async def extract(self, url):
            return ExtractResult(
                metadata=VideoMetadata(
                    sourceType=SourceType.DIRECT_URL,
                    sourceUrl=url,
                    sourceId="fake",
                    title="Fake Video",
                ),
                formats=[
                    MediaTrack(
                        trackId="t1",
                        kind=MediaKind.VIDEO,
                        container="mp4",
                        estimatedBytes=12345,
                        resumable=True,
                    )
                ],
                streamUrls={"t1": url},
            )

        async def fetch_subtitle(self, url, track_id, fmt):
            return "1\n00:00:01,000 --> 00:00:02,000\nHi\n\n", "srt"

    return FakeResolver()
