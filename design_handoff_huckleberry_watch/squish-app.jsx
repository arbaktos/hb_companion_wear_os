/* squish-app.jsx — tweakable refined Squish gallery */
(function () {
  const { useTweaks, TweaksPanel, TweakSection, TweakSelect, TweakSlider, TweakRadio } = window;
  const { DesignCanvas, DCSection, DCArtboard, SquishFinal } = window;

  const FONTS = {
    Fredoka:   '"Fredoka", ui-rounded, system-ui, sans-serif',
    "Baloo 2": '"Baloo 2", ui-rounded, system-ui, sans-serif',
    Quicksand: '"Quicksand", ui-rounded, system-ui, sans-serif',
    Comfortaa: '"Comfortaa", ui-rounded, system-ui, sans-serif',
    Nunito:    '"Nunito", ui-rounded, system-ui, sans-serif',
  };
  const PALETTES = {
    "Dusk → Dawn":     "duskdawn",
    "Ember → Ocean":   "emberocean",
    "Rose → Twilight": "rosetwilight",
    "Sun → Midnight":  "sunmidnight",
    "Peach → Aurora":  "peachaurora",
  };
  const ASLEEP = { "Slosh + Z's": "both", "Sloshing": "slosh", "Breathing": "breathe", "Floating Z's": "zzz" };
  const BG = { Stars: "stars", Pattern: "pattern", Plain: "plain" };

  const STATES = [
    { id: "awake",    label: "Warm + idle motion → tap blob to Start" },
    { id: "sleeping", label: "Cool + sleep motion → tap blob to Pause · Stop below" },
    { id: "paused",   label: "Muted + frozen → tap blob to Resume · Stop below" },
  ];

  const DEFAULTS = /*EDITMODE-BEGIN*/{
    palette: "Dusk → Dawn",
    font: "Comfortaa",
    awakeAnim: "jiggle",
    asleepAnim: "Breathing",
    background: "Pattern",
    blob: 280,
  }/*EDITMODE-END*/;

  function App() {
    const [t, setTweak] = useTweaks(DEFAULTS);
    return (
      <div id="sf-root"
        className={"pal-" + (PALETTES[t.palette] || "duskdawn")}
        data-aw={t.awakeAnim}
        data-sl={ASLEEP[t.asleepAnim] || "both"}
        data-bg={BG[t.background] || "stars"}
        style={{ "--sf-font": FONTS[t.font], "--blob": t.blob + "px", width: "100%", height: "100%" }}>
        <DesignCanvas>
          <DCSection id="squish" title="Squish · refined"
            subtitle="State = colour (warm→cool→muted) + animation. Timer + one primary action on the blob; Stop is the bottom secondary. Open Tweaks to explore palettes, fonts and animations.">
            {STATES.map((st) => (
              <DCArtboard key={st.id} id={st.id} label={st.label} width={544} height={544}>
                <div style={{ width: "100%", height: "100%", display: "grid", placeItems: "center" }}>
                  <SquishFinal initialMode={st.id} />
                </div>
              </DCArtboard>
            ))}
          </DCSection>
        </DesignCanvas>

        <TweaksPanel>
          <TweakSection label="Palette · warm → cool" />
          <TweakSelect label="Theme" value={t.palette} options={Object.keys(PALETTES)}
            onChange={(v) => setTweak("palette", v)} />
          <TweakSection label="Type" />
          <TweakSelect label="Font" value={t.font} options={Object.keys(FONTS)}
            onChange={(v) => setTweak("font", v)} />
          <TweakSection label="Animation" />
          <TweakSelect label="Awake" value={t.awakeAnim} options={["wobble", "bob", "pulse", "jiggle"]}
            onChange={(v) => setTweak("awakeAnim", v)} />
          <TweakSelect label="Asleep" value={t.asleepAnim} options={Object.keys(ASLEEP)}
            onChange={(v) => setTweak("asleepAnim", v)} />
          <TweakSection label="Background" />
          <TweakRadio label="Backdrop" value={t.background} options={Object.keys(BG)}
            onChange={(v) => setTweak("background", v)} />
          <TweakSection label="Shape" />
          <TweakSlider label="Blob size" value={t.blob} min={200} max={288} step={4} unit="px"
            onChange={(v) => setTweak("blob", v)} />
        </TweaksPanel>
      </div>
    );
  }

  window.SquishApp = App;
})();
