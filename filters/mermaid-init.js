document.addEventListener("DOMContentLoaded", () => {
  mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: "neutral" });
  mermaid.run({ querySelector: "pre.mermaid" });
});
