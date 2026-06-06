/* watch-core.jsx — shared timer state machine, formatters, icons, tap FX
   Exposes window.WatchCore */
(function () {
  const { useState, useRef, useEffect, useMemo, useCallback } = React;

  /* ---------- sleep state machine ---------- */
  function useSleepMachine({
    initialMode = "awake",
    seedAwakeMs = (2 * 3600 + 14 * 60) * 1000,
    seedSleepMs = (14 * 60 + 32) * 1000,
  } = {}) {
    const [mode, setMode] = useState(initialMode);
    const [, force] = useState(0);
    const lastSleepEnd = useRef(Date.now() - seedAwakeMs);
    const sleepStart = useRef(
      initialMode === "sleeping" ? Date.now() - seedSleepMs : 0
    );
    const baseElapsed = useRef(initialMode === "paused" ? seedSleepMs : 0);

    useEffect(() => {
      const id = setInterval(() => force((n) => n + 1), 500);
      return () => clearInterval(id);
    }, []);

    const awakeMs = Date.now() - lastSleepEnd.current;
    const sleepMs =
      mode === "sleeping"
        ? baseElapsed.current + (Date.now() - sleepStart.current)
        : baseElapsed.current;

    const start = useCallback(() => {
      baseElapsed.current = 0;
      sleepStart.current = Date.now();
      setMode("sleeping");
    }, []);
    const pause = useCallback(() => {
      baseElapsed.current += Date.now() - sleepStart.current;
      setMode("paused");
    }, []);
    const resume = useCallback(() => {
      sleepStart.current = Date.now();
      setMode("sleeping");
    }, []);
    const stop = useCallback(() => {
      lastSleepEnd.current = Date.now();
      baseElapsed.current = 0;
      setMode("awake");
    }, []);

    return { mode, awakeMs, sleepMs, start, pause, resume, stop };
  }

  /* ---------- formatters ---------- */
  const pad = (n) => String(n).padStart(2, "0");
  function fmtHM(ms) {
    const t = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    if (m > 0) return `${m}m`;
    return "just now";
  }
  function fmtClock(ms) {
    const t = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    const s = t % 60;
    return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
  }

  /* ---------- icons ---------- */
  const Moon = () => (
    <svg viewBox="0 0 24 24" fill="none"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" fill="currentColor"/></svg>
  );
  const Sun = () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <circle cx="12" cy="12" r="4.4" fill="currentColor" stroke="none"/>
      {Array.from({length:8}).map((_,i)=>{const a=i*Math.PI/4;return <line key={i} x1={12+Math.cos(a)*7} y1={12+Math.sin(a)*7} x2={12+Math.cos(a)*9.4} y2={12+Math.sin(a)*9.4}/>;})}
    </svg>
  );
  const Pause = () => (
    <svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4.2" height="14" rx="2"/><rect x="13.8" y="5" width="4.2" height="14" rx="2"/></svg>
  );
  const Play = () => (
    <svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2v13.6c0 .9 1 1.5 1.8 1l10.5-6.8c.7-.5.7-1.5 0-2L9.8 4.2C9 3.7 8 4.3 8 5.2Z"/></svg>
  );
  const Stop = () => (
    <svg viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="3.5"/></svg>
  );

  /* ---------- decorative star field ---------- */
  function useStars(count, maxR = 200) {
    return useMemo(() => {
      const arr = [];
      for (let i = 0; i < count; i++) {
        const ang = Math.random() * Math.PI * 2;
        const rad = 24 + Math.random() * maxR;
        arr.push({
          left: 228 + Math.cos(ang) * rad,
          top: 228 + Math.sin(ang) * rad,
          size: 1.4 + Math.random() * 2.6,
          dur: (2.2 + Math.random() * 3.6).toFixed(2),
          delay: (Math.random() * 4).toFixed(2),
        });
      }
      return arr;
    }, [count, maxR]);
  }

  /* ---------- tap FX: bounce / ripple / squish ---------- */
  function useTap(kind) {
    return useCallback(
      (fn) => (e) => {
        const btn = e.currentTarget;
        if (kind === "bounce" || kind === "squish") {
          const cls = kind === "squish" ? "fx-squish" : "fx-bounce";
          btn.classList.remove(cls);
          void btn.offsetWidth;
          btn.classList.add(cls);
          setTimeout(() => btn.classList.remove(cls), 580);
        }
        if (kind === "ripple") {
          const r = document.createElement("span");
          r.className = "fx-ripple";
          const rect = btn.getBoundingClientRect();
          r.style.left = e.clientX - rect.left + "px";
          r.style.top = e.clientY - rect.top + "px";
          r.style.width = r.style.height = Math.max(rect.width, rect.height) / 4 + "px";
          btn.appendChild(r);
          setTimeout(() => r.remove(), 650);
        }
        if (fn) fn();
      },
      [kind]
    );
  }

  window.WatchCore = { useSleepMachine, fmtHM, fmtClock, useStars, useTap, Moon, Sun, Pause, Play, Stop };
})();
