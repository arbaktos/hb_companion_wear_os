"""baby-svc: FastAPI app over Huckleberry sleep tracking.

Run (dev):   uvicorn main:app --port 8001
Run (prod):  see deploy/baby-svc.service (TLS flags, workers 1)

Required env: HB_EMAIL, HB_PASSWORD, API_BEARER_TOKEN
Optional env: HB_TIMEZONE (default UTC), STATE_DB (default ./state.db)
"""

from __future__ import annotations

import os
import secrets
from contextlib import asynccontextmanager
from typing import Literal

import aiohttp
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from hb import HuckleberryService
from state import Store


def _require_env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(f"missing required env var {name}")
    return value


class Status(BaseModel):
    state: Literal["awake", "sleeping", "paused"]
    session_started_at: float | None
    session_elapsed_sec: float | None
    last_sleep_end: float | None
    server_time: float


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.expected_auth = f"Bearer {_require_env('API_BEARER_TOKEN')}"
    store = Store(os.environ.get("STATE_DB", "state.db"))
    # One shared session + one HuckleberryAPI singleton for the process
    # lifetime (the lib's gRPC channel is loop-bound; --workers 1).
    async with aiohttp.ClientSession() as websession:
        app.state.hb = await HuckleberryService.create(
            email=_require_env("HB_EMAIL"),
            password=_require_env("HB_PASSWORD"),
            tz=os.environ.get("HB_TIMEZONE", "UTC"),
            websession=websession,
            store=store,
        )
        yield


app = FastAPI(title="baby-svc", lifespan=lifespan)


@app.middleware("http")
async def bearer_auth(request: Request, call_next):
    if request.url.path == "/healthz":
        return await call_next(request)
    provided = request.headers.get("authorization", "")
    expected = request.app.state.expected_auth
    if not secrets.compare_digest(provided.encode(), expected.encode()):
        return JSONResponse({"detail": "unauthorized"}, status_code=401)
    return await call_next(request)


async def _upstream(coro):
    """Translate Huckleberry/network failures into a clean 502 for clients."""
    try:
        return await coro
    except Exception as exc:
        raise HTTPException(
            status_code=502, detail=f"huckleberry upstream error: {type(exc).__name__}"
        ) from exc


@app.get("/healthz")
async def healthz() -> dict[str, bool]:
    return {"ok": True}


@app.get("/status", response_model=Status)
async def status(request: Request) -> Status:
    return await _upstream(request.app.state.hb.status())


@app.post("/sleep/start", response_model=Status)
async def sleep_start(request: Request) -> Status:
    return await _upstream(request.app.state.hb.action("start"))


@app.post("/sleep/pause", response_model=Status)
async def sleep_pause(request: Request) -> Status:
    return await _upstream(request.app.state.hb.action("pause"))


@app.post("/sleep/resume", response_model=Status)
async def sleep_resume(request: Request) -> Status:
    return await _upstream(request.app.state.hb.action("resume"))


@app.post("/sleep/stop", response_model=Status)
async def sleep_stop(request: Request) -> Status:
    return await _upstream(request.app.state.hb.action("stop"))
