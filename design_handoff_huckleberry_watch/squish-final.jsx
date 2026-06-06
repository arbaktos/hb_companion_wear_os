/* squish-final.jsx — refined Squish watch
   State shown by colour + animation (no state words). Timer + one primary action on the blob.
   Exposes window.SquishFinal */
(function () {
  const C = window.WatchCore;
  const { Moon, Pause, Play, Stop } = C;

  function SquishFinal({ initialMode = "sleeping" }) {
    const m = C.useSleepMachine({ initialMode });
    const tapBlob = C.useTap("squish");
    const tapBtn = C.useTap("bounce");
    const stars = C.useStars(16, 150);

    const primary = m.mode === "awake" ? m.start : m.mode === "sleeping" ? m.pause : m.resume;
    const actionIcon = m.mode === "awake" ? <Moon /> : m.mode === "sleeping" ? <Pause /> : <Play />;
    const actionWord = m.mode === "awake" ? "Start" : m.mode === "sleeping" ? "Pause" : "Resume";
    const readout = m.mode === "awake" ? C.fmtHM(m.awakeMs) : C.fmtClock(m.sleepMs);

    return (
      <div className="device">
        <div className="crown" /><div className="sidebtn" />
        <div className="screen sf" data-state={m.mode}>
          <div className="sf-bg">
            <span className="layer awake" /><span className="layer sleeping" /><span className="layer paused" />
          </div>

          <div className="sf-stars" style={{ position: "absolute", inset: 0, zIndex: 1 }}>
            {stars.map((s, i) => (
              <span key={i} className="star" style={{ left: s.left, top: s.top, width: s.size,
                height: s.size, "--tw": s.dur + "s", "--dly": s.delay + "s" }} />
            ))}
          </div>

          <div className="sf-pattern">
            <span className="pat awake" /><span className="pat sleeping" /><span className="pat paused" />
          </div>

          <div className="sf-zzz"><span>z</span><span>z</span><span>z</span></div>

          <div className="sf-stage">
            <button className="sf-blob" onClick={tapBlob(primary)} aria-label={actionWord}>
              <span className="layer awake" /><span className="layer sleeping" /><span className="layer paused" />
              {m.mode !== "awake" && <div className="sf-liquid" />}
              <div className="sf-face">
                <div className="sf-time">{readout}</div>
                <div className="sf-action">{actionIcon} {actionWord}</div>
              </div>
            </button>
          </div>

          {m.mode !== "awake" && (
            <button className="ghost sf-stop" onClick={tapBtn(m.stop)}><Stop /> Stop</button>
          )}

          <div className="vignette" /><div className="glass" />
        </div>
      </div>
    );
  }

  window.SquishFinal = SquishFinal;
})();
