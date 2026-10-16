// A helper for reviewing the mock-ups, not part of any interface: it keeps the theme chosen on one page for
// the others, and `?theme=pulse-dark` in an address picks one.
(function () {
  var key = "pulse-design-theme";
  var root = document.documentElement;
  function read() { try { return window.localStorage.getItem(key); } catch (e) { return null; } }
  function write(value) { try { window.localStorage.setItem(key, value); } catch (e) { /* private window */ } }

  var asked = new URLSearchParams(window.location.search).get("theme");
  if (asked) { write(asked); }
  var theme = asked || read();
  if (theme) { root.setAttribute("data-theme", theme); }

  document.addEventListener("DOMContentLoaded", function () {
    var select = document.getElementById("theme-select");
    if (!select) { return; }
    select.value = root.getAttribute("data-theme");
    select.addEventListener("change", function () {
      root.setAttribute("data-theme", select.value);
      write(select.value);
    });
  });
})();
