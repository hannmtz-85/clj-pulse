// A helper for reviewing the mock-ups, not part of any interface.
//
// Every page: keeps the theme chosen on one page for the others, and `?theme=pulse-dark` in an address picks one.
// A page that shows a mock-up in a frame (it has #device-frame): the buttons with a data-width set the width of the
// frame, to see the page on a phone, a tablet or a desk, and the frame is as high as the page in it. A page inside
// that frame: tells the theme and the height to the page around it, which cannot read them (a file is its own origin).
(function () {
  var themeKey = "pulse-design-theme";
  var root = document.documentElement;
  var frame = null;

  function read(key) { try { return window.localStorage.getItem(key); } catch (e) { return null; } }
  function write(key, value) { try { window.localStorage.setItem(key, value); } catch (e) { /* private window */ } }

  var asked = new URLSearchParams(window.location.search).get("theme");
  if (asked) { write(themeKey, asked); }
  var theme = asked || read(themeKey);
  if (theme) { root.setAttribute("data-theme", theme); }

  window.addEventListener("message", function (event) {
    var data = event.data || {};
    if (data.type === "pulse-theme" && typeof data.theme === "string") {
      root.setAttribute("data-theme", data.theme);
    }
    if (data.type === "pulse-height" && frame && typeof data.height === "number") {
      frame.style.height = Math.max(data.height, 480) + "px";
    }
  });

  document.addEventListener("DOMContentLoaded", function () {
    var select = document.getElementById("theme-select");
    frame = document.getElementById("device-frame");

    function tellTheFrame() {
      if (frame && frame.contentWindow) {
        frame.contentWindow.postMessage({ type: "pulse-theme", theme: root.getAttribute("data-theme") }, "*");
      }
    }

    if (select) {
      select.value = root.getAttribute("data-theme");
      select.addEventListener("change", function () {
        root.setAttribute("data-theme", select.value);
        write(themeKey, select.value);
        tellTheFrame();
      });
    }

    if (frame) {
      frame.addEventListener("load", tellTheFrame);
      var buttons = document.querySelectorAll("[data-width]");
      buttons.forEach(function (button) {
        button.addEventListener("click", function () {
          frame.style.width = button.getAttribute("data-width") + "px";
          buttons.forEach(function (other) { other.setAttribute("aria-pressed", String(other === button)); });
        });
      });
    }

    if (window.parent !== window) {
      // the links of a page in a frame take the whole window to another mock-up (and a link to # stays on the page)
      document.querySelectorAll("a[href]").forEach(function (link) {
        var address = link.getAttribute("href");
        if (address && address.charAt(0) !== "#") { link.setAttribute("target", "_top"); }
      });
      var report = function () {
        window.parent.postMessage({ type: "pulse-height", height: document.documentElement.scrollHeight }, "*");
      };
      report();
      window.addEventListener("load", report);
      window.addEventListener("resize", report);
      if (window.ResizeObserver) { new ResizeObserver(report).observe(document.body); }
    }
  });
})();
