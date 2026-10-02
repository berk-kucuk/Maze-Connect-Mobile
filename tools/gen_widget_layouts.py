#!/usr/bin/env python3
"""Generates every home-screen widget layout, and its picker preview, from one
description, so the preview and the real widget can never drift apart."""
import os, sys
from xml.sax.saxutils import escape

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "layout")

class E:
    def __init__(self, tag, a=None, *kids):
        self.tag, self.a, self.kids = tag, dict(a or {}), [k for k in kids if k is not None]
    def xml(self, ind=0, root=False):
        pad = "    " * ind
        attrs = []
        if root:
            attrs.append('xmlns:android="http://schemas.android.com/apk/res/android"')
        for k, v in self.a.items():
            name = k if (":" in k or k == "style") else "android:" + k
            attrs.append(f'{name}="{escape(str(v), {chr(34): "&quot;"})}"')
        inner = ("\n" + pad + "    ").join(attrs)
        head = f"{pad}<{self.tag}" + (("\n" + pad + "    " + inner) if attrs else "")
        if not self.kids:
            return head + " />"
        body = "\n".join(k.xml(ind + 1) for k in self.kids)
        return f"{head}>\n{body}\n{pad}</{self.tag}>"

M = [("CPU", 23, "52 °C"), ("RAM", 61, "9.4 / 15.5 GB"), ("Disk", 48, "228 / 476 GB"), ("Swap", 6, "0.5 / 8 GB")]
W, WC, MP = "wrap_content", "wrap_content", "match_parent"

def tv(id_, style, P, w=W, h=WC, **a):
    d = {"layout_width": w, "layout_height": h}
    if id_:
        d = {"id": "@+id/" + id_, **d}
    d["style"] = "@style/" + style
    d.update(a)
    if P is not None and id_ in P:
        d["text"] = P[id_]
    return E("TextView", d)

def root(pad_h, pad_v, *kids):
    return E("LinearLayout", {
        "id": "@android:id/background", "layout_width": MP, "layout_height": MP,
        "orientation": "vertical", "gravity": "center_vertical",
        "background": "@drawable/widget_background",
        "paddingHorizontal": f"{pad_h}dp", "paddingVertical": f"{pad_v}dp"}, *kids)

def hrow(a=None, *kids, w=MP):
    d = {"layout_width": w, "layout_height": WC, "orientation": "horizontal",
         "gravity": "center_vertical", "baselineAligned": "false"}
    d.update(a or {})
    return E("LinearLayout", d, *kids)

def vcol(a=None, *kids):
    d = {"layout_width": MP, "layout_height": WC, "orientation": "vertical"}
    d.update(a or {})
    return E("LinearLayout", d, *kids)

def header(P, small=False, age=True, tag=None):
    host_text = {} if P is None else {"widget_host": "maze-desktop"}
    kids = [
        E("ImageView", {"id": "@+id/widget_dot", "layout_width": "7dp", "layout_height": "7dp",
                        "src": "@drawable/widget_dot_live" if P is not None else "@drawable/widget_dot_stale",
                        "contentDescription": "@null"}),
        tv("widget_host", "WidgetText.Host.Small" if small else "WidgetText.Host", host_text or None,
           w="0dp", layout_weight="1", layout_marginStart="7dp",
           **({} if P is not None else {"text": "@string/widget_no_computer"})),
    ]
    if tag:
        kids.append(tv(None, "WidgetText.Tag", None, layout_marginStart="8dp", text=tag))
    elif age:
        kids.append(tv("widget_age", "WidgetText.Age", P, layout_marginStart="8dp"))
    return hrow({}, *kids)

def hint(P, top=6, lines=3, **a):
    return tv("widget_hint", "WidgetText.Hint", None, w=MP, layout_marginTop=f"{top}dp",
              maxLines=str(lines), visibility="gone", **a)

def ring(i, size, P, small=False):
    bar = {"id": f"@+id/meter_bar_{i}", "style": "@style/WidgetRing",
           "layout_width": MP, "layout_height": MP}
    if P is not None:
        bar["progress"] = str(M[i - 1][1])
    return E("FrameLayout", {"layout_width": f"{size}dp", "layout_height": f"{size}dp"},
             E("ProgressBar", bar),
             tv(f"meter_value_{i}", "WidgetText.Ring.Small" if small else "WidgetText.Ring", P, w=MP, h=MP))

def bar(i, P, h=4, **a):
    d = {"id": f"@+id/meter_bar_{i}", "style": "@style/WidgetBar", "layout_width": MP,
         "layout_height": f"{h}dp"}
    d.update(a)
    if P is not None:
        d["progress"] = str(M[i - 1][1])
    return E("ProgressBar", d)

def sample(short=False):
    P = {"widget_age": "just now"}
    for i, (label, pct, detail) in enumerate(M, 1):
        P[f"meter_label_{i}"] = label
        P[f"meter_value_{i}"] = f"{pct}%"
        P[f"meter_detail_{i}"] = "8 cores · 52 °C" if (i == 1 and not short) else detail
    P.update({"stat_hardening_value": "92%", "stat_services_value": "5/6", "stat_network_value": "2/2"})
    return P

def comment(text):
    return "<!--\n" + "\n".join("  " + l if l else "" for l in text.strip().split("\n")) + "\n-->\n"

# ---------------------------------------------------------------- dashboard

def mini(P):
    cols = []
    for i in (1, 2):
        cols.append(hrow({"id": f"@+id/meter_row_{i}", "layout_width": "0dp", "layout_weight": "1",
                          "gravity": "bottom", **({"layout_marginStart": "10dp"} if i == 2 else {})},
                         tv(f"meter_label_{i}", "WidgetText.Label.Small", P),
                         tv(f"meter_value_{i}", "WidgetText.Value", P, layout_marginStart="4dp")))
    return root(12, 7, header(P, small=True, age=False),
                hrow({"id": "@+id/widget_meters", "layout_marginTop": "5dp"}, *cols),
                hint(P, top=4, lines=2, textSize="11sp"))

def mini_tall(P):
    cols = []
    for i in (1, 2):
        cols.append(vcol({"id": f"@+id/meter_row_{i}", "layout_width": "0dp", "layout_weight": "1",
                          "gravity": "center_horizontal"},
                         ring(i, 40, P),
                         tv(f"meter_label_{i}", "WidgetText.Title", P, layout_marginTop="5dp", textSize="10sp")))
    return root(10, 9, header(P, small=True),
                hrow({"id": "@+id/widget_meters", "layout_marginTop": "7dp"}, *cols),
                hint(P, top=6, lines=3, textSize="11sp"))

def compact(P):
    host = vcol({"layout_width": "0dp", "layout_weight": "1.3"},
                header(P, small=True, age=False),
                tv("widget_age", "WidgetText.Age", P, layout_marginStart="14dp", layout_marginTop="3dp"))
    cols = []
    for i in range(1, 5):
        cols.append(vcol({"id": f"@+id/meter_row_{i}", "layout_width": "0dp", "layout_weight": "1",
                          "layout_marginStart": "12dp"},
                         hrow({"gravity": "bottom"},
                              tv(f"meter_label_{i}", "WidgetText.Label.Small", P, w="0dp", layout_weight="1"),
                              tv(f"meter_value_{i}", "WidgetText.Value.Small", P, layout_marginStart="4dp")),
                         bar(i, P, h=3, layout_marginTop="5dp")))
    meters = hrow({"id": "@+id/widget_meters", "layout_width": "0dp", "layout_weight": "4"}, *cols)
    h = tv("widget_hint", "WidgetText.Hint", None, w="0dp", layout_weight="4", layout_marginStart="12dp",
           maxLines="2", textSize="11sp", visibility="gone")
    return root(14, 8, hrow({}, host, meters, h))

def strip(P):
    cols = []
    for i in range(1, 5):
        cols.append(vcol({"id": f"@+id/meter_row_{i}", "layout_width": "0dp", "layout_weight": "1",
                          **({"layout_marginStart": "12dp"} if i > 1 else {})},
                         hrow({"gravity": "bottom"},
                              tv(f"meter_label_{i}", "WidgetText.Label.Small", P, w="0dp", layout_weight="1"),
                              tv(f"meter_value_{i}", "WidgetText.Value.Large", P, layout_marginStart="4dp")),
                         bar(i, P, layout_marginTop="5dp"),
                         tv(f"meter_detail_{i}", "WidgetText.Detail", P, w=MP, layout_marginTop="5dp")))
    return root(14, 10, header(P),
                hrow({"id": "@+id/widget_meters", "layout_marginTop": "10dp", "gravity": "top"}, *cols),
                hint(P, top=8))

def ring_rows(P, rows, size, first, gap, small=False):
    out = []
    for i in range(1, rows + 1):
        out.append(hrow({"id": f"@+id/meter_row_{i}", "layout_marginTop": f"{first if i == 1 else gap}dp"},
                        ring(i, size, P, small),
                        vcol({"layout_width": "0dp", "layout_weight": "1", "layout_marginStart": "9dp"},
                             tv(f"meter_label_{i}", "WidgetText.Title", P, w=MP),
                             tv(f"meter_detail_{i}", "WidgetText.Detail", P, w=MP, layout_marginTop="4dp"))))
    return out

def tile(P):
    return root(12, 11, header(P),
                vcol({"id": "@+id/widget_meters"}, *ring_rows(P, 2, 40, 10, 8)),
                hint(P, top=8, lines=5))

def tile_tall(P):
    return root(12, 12, header(P),
                vcol({"id": "@+id/widget_meters"}, *ring_rows(P, 4, 36, 10, 7, small=True)),
                hint(P, top=8, lines=6))

def dashboard(P):
    rows = []
    for i in range(1, 5):
        rows.append(hrow({"id": f"@+id/meter_row_{i}", **({"layout_marginTop": "4dp"} if i > 1 else {})},
                         tv(f"meter_label_{i}", "WidgetText.Label", P, w="32dp"),
                         bar(i, P, layout_width="0dp", layout_weight="1", layout_marginHorizontal="10dp"),
                         tv(f"meter_value_{i}", "WidgetText.Value", P, w="36dp", gravity="end")))
    return root(14, 8, header(P), vcol({"id": "@+id/widget_meters", "layout_marginTop": "6dp"}, *rows),
                hint(P))

def grid(P):
    def cell(i):
        return hrow({"id": f"@+id/meter_row_{i}", "layout_width": "0dp", "layout_weight": "1",
                     "background": "@drawable/widget_cell", "padding": "12dp",
                     **({"layout_marginEnd": "8dp"} if i % 2 else {})},
                    ring(i, 42, P),
                    vcol({"layout_width": "0dp", "layout_weight": "1", "layout_marginStart": "10dp"},
                         tv(f"meter_label_{i}", "WidgetText.Title", P, w=MP),
                         tv(f"meter_detail_{i}", "WidgetText.Detail", P, w=MP, layout_marginTop="4dp")))
    return root(12, 12, header(P),
                vcol({"id": "@+id/widget_meters", "layout_marginTop": "11dp"},
                     hrow({}, cell(1), cell(2)),
                     hrow({"layout_marginTop": "8dp"}, cell(3), cell(4))),
                hint(P, top=10, lines=5))

def detailed(P):
    rows = []
    for i in range(1, 5):
        rows.append(vcol({"id": f"@+id/meter_row_{i}", **({"layout_marginTop": "6dp"} if i > 1 else {})},
                         hrow({"gravity": "bottom"},
                              tv(f"meter_label_{i}", "WidgetText.Title", P, w="44dp", textSize="12sp"),
                              tv(f"meter_detail_{i}", "WidgetText.Detail", P, w="0dp", layout_weight="1",
                                 layout_marginStart="4dp"),
                              tv(f"meter_value_{i}", "WidgetText.Value", P, layout_marginStart="6dp")),
                         bar(i, P, layout_marginTop="4dp")))
    def stat(key, last=False):
        return vcol({"layout_width": "0dp", "layout_weight": "1", "background": "@drawable/widget_cell",
                     "paddingHorizontal": "9dp", "paddingVertical": "6dp",
                     **({} if last else {"layout_marginEnd": "6dp"})},
                    tv(f"stat_{key}_value", "WidgetText.Stat", P, w=MP),
                    tv(None, "WidgetText.Detail", None, w=MP, layout_marginTop="3dp",
                       text=f"@string/widget_stat_{key}"))
    return root(12, 12, header(P),
                vcol({"id": "@+id/widget_meters", "layout_marginTop": "9dp"}, *rows),
                hrow({"id": "@+id/widget_stats", "layout_marginTop": "9dp"},
                     stat("hardening"), stat("services"), stat("network", True)),
                hint(P, top=10, lines=5))

# ------------------------------------------------------------------ controls

CTL = [("camera", "Camera", "blocked", "on"), ("microphone", "Mic", "blocked", "on"),
       ("wifi", "Wi-Fi", "allowed", ""), ("bluetooth", "BT", "absent", "off")]
ICON = {"camera": "ic_camera", "microphone": "ic_microphone", "wifi": "ic_wifi", "bluetooth": "ic_bluetooth"}

def ctl_cell(i, P, slim):
    dev, name, state, kind = CTL[i - 1]
    bg = "@drawable/widget_cell"
    ink = "#F2F1EC"
    sub = None
    if P is not None:
        bg = {"on": "@drawable/widget_cell_on", "off": "@drawable/widget_cell_off"}.get(kind, bg)
        ink = {"on": "#0A0A0B", "off": "#56565B"}.get(kind, ink)
        sub = {"on": "#9E0A0A0B", "off": "#56565B"}.get(kind, "#8E8E93")
    a = {"id": f"@+id/control_{i}", "layout_width": "0dp", "layout_height": WC, "layout_weight": "1",
         "gravity": "center", "background": bg,
         "orientation": "horizontal" if slim else "vertical"}
    if i < 4:
        a["layout_marginEnd"] = "6dp"
    if slim:
        a.update({"paddingVertical": "8dp", "paddingHorizontal": "8dp"})
    else:
        a["paddingVertical"] = "6dp"
    icon = E("ImageView", {"id": f"@+id/control_icon_{i}", "layout_width": "18dp", "layout_height": "18dp",
                           "src": f"@drawable/{ICON[dev]}", "contentDescription": "@null",
                           **({"tint": ink} if P is not None else {})})
    nm = {"text": name, "textColor": ink} if P is not None else {}
    st = {"text": state.capitalize() if not slim else "· " + state, "textColor": sub} if P is not None else {}
    name_v = tv(f"control_name_{i}", "WidgetText.CellName", None,
                **({"layout_marginStart": "7dp"} if slim else {"layout_marginTop": "3dp"}), **nm)
    state_v = tv(f"control_state_{i}", "WidgetText.CellState", None,
                 **({"layout_marginStart": "4dp"} if slim else {"layout_marginTop": "2dp"}), **st)
    return E("LinearLayout", a, icon, name_v, state_v)

def controls(P):
    return root(10, 9, header(P, small=True, tag="Guard"),
                hrow({"id": "@+id/control_row", "layout_marginTop": "7dp"}, *[ctl_cell(i, P, False) for i in range(1, 5)]),
                hint(P, top=6, lines=3, textSize="11sp"))

def controls_slim(P):
    return root(8, 6,
                hrow({"id": "@+id/control_row"}, *[ctl_cell(i, P, True) for i in range(1, 5)]),
                hint(P, top=0, lines=2, textSize="11sp", layout_marginHorizontal="6dp"))

CMDS = ["Update system", "Lock screen", "Backup home", "Restart audio"]

def pill(i, P, slim):
    a = {"id": f"@+id/command_{i}", "layout_width": "0dp", "layout_height": "32dp" if slim else "27dp",
         "layout_weight": "1", "orientation": "horizontal", "gravity": "center_vertical",
         "background": "@drawable/widget_pill"}
    if (slim and i < 4) or (not slim and i % 2):
        a["layout_marginEnd"] = "6dp" if slim else "5dp"
    txt = {"text": CMDS[i - 1]} if P is not None else {}
    if slim:
        a["paddingStart"] = "12dp"
        a["paddingEnd"] = "7dp"
        return E("LinearLayout", a,
                 tv(f"command_name_{i}", "WidgetText.Pill", None, w="0dp", layout_weight="1", **txt),
                 E("ImageView", {"layout_width": "18dp", "layout_height": "18dp", "layout_marginStart": "6dp",
                                 "background": "@drawable/widget_go", "padding": "3dp",
                                 "src": "@drawable/widget_ic_run", "contentDescription": "@null"}))
    a["paddingHorizontal"] = "11dp"
    return E("LinearLayout", a,
             E("ImageView", {"layout_width": "14dp", "layout_height": "14dp", "src": "@drawable/ic_commands",
                             "tint": "#8E8E93", "contentDescription": "@null"}),
             tv(f"command_name_{i}", "WidgetText.Pill", None, w="0dp", layout_weight="1",
                layout_marginStart="7dp", **txt))

def commands(P):
    return root(10, 8, header(P, small=True, tag="Commands"),
                vcol({"id": "@+id/command_row", "layout_marginTop": "6dp"},
                     hrow({}, pill(1, P, False), pill(2, P, False)),
                     hrow({"id": "@+id/command_row_2", "layout_marginTop": "5dp"}, pill(3, P, False), pill(4, P, False))),
                hint(P, top=6, lines=3, textSize="11sp"))

def commands_slim(P):
    return root(8, 6,
                hrow({"id": "@+id/command_row"}, *[pill(i, P, True) for i in range(1, 5)]),
                hint(P, top=0, lines=2, textSize="11sp", layout_marginHorizontal="6dp"))

# ---------------------------------------------------------------- the files

LAYOUTS = {
    # name: (builder, measured, note, preview name or None, short sample)
    "widget_mini": (mini, "110 x 50", "7 + 15.2 host + 5 + 15.2 values + 7 = 49.4",
                    "A 2x1 on a landscape phone (about 127 x 51 dp): name and two values.", None),
    "widget_mini_tall": (mini_tall, "110 x 97", "9 + 15.2 header + 7 + 40 ring + 5 + 11.7 label + 9 = 96.9",
                         "A 2x1 on a portrait phone (about 130 x 102 dp): two ring gauges fill it.", "widget_preview_mini"),
    "widget_compact": (compact, "200 x 46", "8 + (15.2 host + 3 + 11.7 age) + 8 = 45.9",
                       "A short strip — 4x1 in landscape (about 554 x 51 dp): four meters in one row.", None),
    "widget_strip": (strip, "200 x 90", "10 + 16.4 header + 10 + 17.6 value + 5 + 4 bar + 5 + 11.7 detail + 10 = 89.7",
                     "A portrait 4x1 (about 276 x 102 dp), or a wide landscape 4x2: four readings\n"
                     "  with a large value, a bar and the computer's detail each.", "widget_preview_compact"),
    "widget_tile": (tile, "110 x 137", "11 + 16.4 header + 10 + 40 ring + 8 + 40 ring + 11 = 136.4",
                    "A small square: two ring gauges with their detail.", None),
    "widget_tile_tall": (tile_tall, "110 x 216", "12 + 16.4 header + 10 + 4 x 36 ring + 3 x 7 + 12 = 215.4",
                         "A portrait 2x2 (about 130 x 220 dp): all four readings as rings.", "widget_preview_tile"),
    "widget_dashboard": (dashboard, "180 x 112", "8 + 16.4 header + 6 + 4 x 15.2 rows + 3 x 4 + 8 = 111.2",
                         "A narrow, short block: four bars in a list.", None),
    "widget_grid": (grid, "240 x 192", "12 + 16.4 header + 11 + 2 x (12 + 42 ring + 12) + 8 + 12 = 191.4",
                    "A portrait 4x2 (about 276 x 220 dp): four cells, each a ring with its detail.", "widget_preview_grid"),
    "widget_detailed": (detailed, "200 x 213",
                        "12 + 16.4 header + 9 + 4 x (15.2 line + 4 + 4 bar) + 3 x 6 + 9\n"
                        "    + (6 + 16.4 + 3 + 11.7 + 6) stats + 12 = 212.3",
                        "Every reading with its detail, plus hardening, services and network.", "widget_preview_detailed"),
    "widget_controls": (controls, "180 x 100", "9 + 15.2 header + 7 + (6 + 18 icon + 3 + 12.9 + 2 + 11.7 + 6) + 9 = 99.8",
                        "Killswitches, normal height: blocked cells are filled paper.", "widget_preview_controls"),
    "widget_controls_slim": (controls_slim, "250 x 46", "6 + (8 + 18 icon + 8) + 6 = 46",
                             "Killswitches in one row, for a short cell (4x1 in landscape).", None),
    "widget_commands": (commands, "180 x 97", "8 + 15.2 header + 6 + 27 pill + 5 + 27 pill + 8 = 96.2",
                        "Pinned commands as a 2x2 of pills.", "widget_preview_commands"),
    "widget_commands_slim": (commands_slim, "180 x 44", "6 + 32 pill + 6 = 44",
                             "Pinned commands in one row, for a short cell.", None),
}

HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
for name, (fn, size, math, note, preview) in LAYOUTS.items():
    c = comment(f"GENERATED by tools/gen_widget_layouts.py — edit that, not this file.\n\n{note}\n\n"
                f"Measured for {size} dp (text is 1.17 x its size, font padding off):\n  {math}")
    with open(os.path.join(OUT, name + ".xml"), "w") as f:
        f.write(HEAD + c + fn(None).xml(0, root=True) + "\n")
    if preview:
        with open(os.path.join(OUT, preview + ".xml"), "w") as f:
            f.write(HEAD + comment("GENERATED — the widget picker's preview of " + name + ", with sample readings.")
                    + fn(sample()).xml(0, root=True) + "\n")
print("ok", len(LAYOUTS))
