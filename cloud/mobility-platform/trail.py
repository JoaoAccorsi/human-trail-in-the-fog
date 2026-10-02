"""Cloud Mobility Platform functions: pseudonym, TMS (Java) queries and charts."""

import hashlib
import hmac
import random

import matplotlib
import requests

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.colors import Normalize  # noqa: E402
from matplotlib.patches import Rectangle  # noqa: E402

import config  # noqa: E402


def pseudonym(uid: int, salt: str = config.SALT) -> str:
    """h(s, e) = HMAC-SHA256(salt, uid) - same as edge.EdgeApp.pseudonym (Java).
    Re-identification happens here, on the client: the TMS never sees the uid."""
    return hmac.new(salt.encode(), str(uid).encode(), hashlib.sha256).hexdigest()


def _period(day: int, start: str | None, end: str | None) -> dict:
    """Query parameters of a period on one day; start/end as "HH:MM" (None = whole day)."""
    params = {"day": day}
    if start:
        params["from"] = start
    if end:
        params["to"] = end
    return params


def fetch_trail(h: str, day: int, url: str = config.TMS_URL,
                start: str | None = None, end: str | None = None) -> list[dict]:
    """Visits of h that overlap the period (the TMS returns each visit whole)."""
    resp = requests.get(f"{url}/trail/{h}", params=_period(day, start, end), timeout=5)
    resp.raise_for_status()
    return resp.json()


def fetch_events(h: str, day: int, url: str = config.TMS_URL,
                 start: str | None = None, end: str | None = None) -> list[dict]:
    """GS1 EPCIS 2.0 events (arriving/departing) the Fogs sent for this pseudonym in the period."""
    resp = requests.get(f"{url}/events/{h}", params=_period(day, start, end), timeout=5)
    resp.raise_for_status()
    return resp.json()


def events_table(events: list[dict]) -> list[dict]:
    """One row per EPCIS event: what, when, where, why."""
    return [{
        "eventTime": e["eventTime"],
        "bizStep": e["bizStep"],
        "readPoint (Fog)": e["readPoint"]["id"],
        "bizLocation (cell)": e["bizLocation"]["id"],
        "epc": e["epcList"][0][:30] + "…",
    } for e in events]


def summary(trail: list[dict]) -> dict:
    return {
        "pings": sum(v["pings"] for v in trail),
        "fogs": len({v["fog"] for v in trail}),
        "handovers": sum(1 for v in trail if v["reason"] == "handover"),
        "start": trail[0]["arrival"],
        "end": trail[-1]["departure"],
    }


def table(trail: list[dict]) -> list[dict]:
    return [{
        "#": i,
        "Fog": v["fog"],
        "arrival": v["arrival"],
        "departure": v["departure"],
        "pings": v["pings"],
        "cells": " → ".join(f"({x},{y})" for x, y in v["cells"]),
        "ended by": "handover → Fog %d" % v["nextFog"] if v.get("nextFog") else v["reason"],
    } for i, v in enumerate(trail, 1)]


def side_of(v: dict) -> int:
    """Side of the Fog area (cells) of a visit; visits from older versions carry "fogSize"."""
    return v.get("fogAreaSide") or v.get("fogSize")


def _area(fog: int, area_side: int) -> tuple[float, float]:
    per_axis = config.GRID // area_side
    return ((fog - 1) % per_axis) * area_side + 0.5, ((fog - 1) // per_axis) * area_side + 0.5


def trail_map(trail: list[dict], title: str):
    """Map: areas of the visited Fogs + the cell-by-cell path (zoomed on the trail)."""
    points = [tuple(c) for v in trail for c in v["cells"]]
    xs, ys = [c[0] for c in points], [c[1] for c in points]
    margin = 4
    x0v, x1v = min(xs) - margin, max(xs) + margin
    y0v, y1v = min(ys) - margin, max(ys) + margin
    colors = plt.cm.tab10.colors
    fig, ax = plt.subplots(figsize=(7, 7))
    # each visit carries the area size of the Fog that produced it
    fogs = dict.fromkeys((v["fog"], side_of(v)) for v in trail)
    for i, (fog, area_side) in enumerate(fogs):
        x0, y0 = _area(fog, area_side)
        ax.add_patch(Rectangle((x0, y0), area_side, area_side, alpha=0.25, color=colors[i % 10]))
        # Fog name at the center of the visible part of the area (only if the area shows on the map)
        vx0, vx1 = max(x0, x0v), min(x0 + area_side, x1v)
        vy0, vy1 = max(y0, y0v), min(y0 + area_side, y1v)
        if vx0 < vx1 and vy0 < vy1:
            ax.text((vx0 + vx1) / 2, (vy0 + vy1) / 2, f"Fog {fog}", ha="center", va="center",
                    fontsize=11, alpha=0.6, weight="bold", clip_on=True)
    ax.plot(xs, ys, "-o", color="black", markersize=4, linewidth=1)
    ax.plot(xs[0], ys[0], "o", color="green", markersize=12, label=f"start {trail[0]['arrival']}")
    ax.plot(xs[-1], ys[-1], "o", color="red", markersize=12, label=f"end {trail[-1]['departure']}")
    ax.set_xlim(x0v, x1v)
    ax.set_ylim(y0v, y1v)
    ax.set_aspect("equal")
    ax.set_title(title)
    ax.set_xlabel("x (500 m cells)")
    ax.set_ylabel("y (500 m cells)")
    # below the axes, so it never covers the trail
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.09), ncol=2, frameon=False)
    fig.tight_layout()
    return fig


def timeline(trail: list[dict]):
    """Timeline: which Fog the user was in at each time of the day."""
    fogs = list(dict.fromkeys(v["fog"] for v in trail))
    colors = plt.cm.tab10.colors
    fig, ax = plt.subplots(figsize=(9, 0.5 * len(fogs) + 1.5))
    for v in trail:
        row = fogs.index(v["fog"])
        start = v["arrivalT"] / 2                 # timeslot -> hour
        duration = (v["departureT"] - v["arrivalT"] + 1) / 2
        ax.barh(row, duration, left=start, color=colors[row % 10], edgecolor="black")
    ax.set_yticks(range(len(fogs)), [f"Fog {f}" for f in fogs])
    ax.invert_yaxis()
    ax.set_xlim(0, 24)
    ax.set_xticks(range(0, 25, 2), [f"{h:02d}h" for h in range(0, 25, 2)])
    ax.set_xlabel("time of day")
    ax.grid(axis="x", alpha=0.3)
    ax.set_title("Time spent in each Fog node")
    fig.tight_layout()
    return fig


# ====================================================================== query by Fog node

def list_fogs(day: int, url: str = config.TMS_URL) -> list[dict]:
    """Fog nodes with visits on the day: [{fog, people, visits}]."""
    resp = requests.get(f"{url}/fogs", params={"day": day}, timeout=5)
    resp.raise_for_status()
    return resp.json()


def visits_at_fog(fog: int, day: int, url: str = config.TMS_URL) -> dict[str, list[dict]]:
    """Visits the Fog recorded on the day, grouped by pseudonym (in order of first arrival)."""
    resp = requests.get(f"{url}/visits", params={"fog": fog, "day": day}, timeout=5)
    resp.raise_for_status()
    at_fog: dict[str, list[dict]] = {}
    for v in resp.json():
        at_fog.setdefault(v["h"], []).append(v)
    return at_fog


def sample(people: dict[str, list[dict]], limit: int | None) -> list[str]:
    """Up to `limit` pseudonyms chosen at random (all of them if limit is None or larger), kept in
    order of first arrival. Each call draws a new sample."""
    keys = list(people)
    if limit is None or limit >= len(keys):
        return keys
    chosen = set(random.sample(keys, limit))
    return [h for h in keys if h in chosen]


def people_at_fog(at_fog: dict[str, list[dict]], chosen: list[str], day: int,
                  url: str = config.TMS_URL) -> list[dict]:
    """For each chosen pseudonym: the visits to the Fog and the day's trail (one TMS query each).
    The platform only sees pseudonyms - without the salt and the uid there is no way to know who the person is."""
    return [{
        "label": f"Person {i} ({h[:8]}…)",
        "h": h,
        "at_fog": at_fog[h],
        "trail": fetch_trail(h, day, url),
    } for i, h in enumerate(chosen, 1)]


def fog_summary(at_fog: dict[str, list[dict]]) -> dict:
    """Totals over everyone who passed through the Fog (not only the sample on the charts)."""
    visits = [v for vs in at_fog.values() for v in vs]
    return {
        "people": len(at_fog),
        "visits": len(visits),
        "pings": sum(v["pings"] for v in visits),
        "start": min(v["arrival"] for v in visits),
        "end": max(v["departure"] for v in visits),
    }


def fog_table(people: list[dict], fog: int) -> list[dict]:
    """One row per visit to the Fog: where the person came from and where they went."""
    rows = []
    for p in people:
        trail = p["trail"]
        for v in p["at_fog"]:
            i = next(k for k, t in enumerate(trail) if t["arrivalT"] == v["arrivalT"] and t["fog"] == fog)
            rows.append({
                "person": p["label"],
                "arrival": v["arrival"],
                "departure": v["departure"],
                "pings": v["pings"],
                "came from": f"Fog {trail[i - 1]['fog']}" if i > 0 else "start of day",
                "went to": f"Fog {v['nextFog']}" if v.get("nextFog") else "end of day",
                "Fogs that day": len({t["fog"] for t in trail}),
            })
    return sorted(rows, key=lambda r: r["arrival"])


# ====================================================================== shared by the multi-person charts

LEGEND_MAX = 40  # above this, the map has no legend (the presence chart beside it names everyone)


def _color(i: int):
    return plt.cm.tab20.colors[(2 * i) % 20 + (i // 10) % 2]  # 20 distinct colors, strong ones first


def _legend_columns(n: int) -> int:
    return 0 if n > LEGEND_MAX else (1 if n <= 20 else 2)


def _legend_width(n: int) -> float:
    """Extra figure width (inches) for the legend outside the axes."""
    return 2.4 * _legend_columns(n)


def _legend_outside(fig, ax, n: int):
    """Legend to the right of the axes, so it never covers the trails."""
    cols = _legend_columns(n)
    if cols:
        ax.legend(loc="upper left", bbox_to_anchor=(1.02, 1), borderaxespad=0, ncol=cols, fontsize=8,
                  title="● start  ■ end", title_fontsize=8, frameon=False)


def _plot_people(ax, people: list[dict]):
    """Each person's trail: line through the cells, circle at the start, square at the end."""
    for i, p in enumerate(people):
        color = _color(i)
        pts = [tuple(c) for v in p["trail"] for c in v["cells"]]
        px, py = [c[0] for c in pts], [c[1] for c in pts]
        ax.plot(px, py, "-o", color=color, markersize=3, linewidth=1.5, label=p["label"])
        ax.plot(px[0], py[0], "o", color=color, markersize=9, markeredgecolor="white")    # start
        ax.plot(px[-1], py[-1], "s", color=color, markersize=9, markeredgecolor="white")  # end


def fog_map(people: list[dict], fog: int, title: str):
    """Map: the queried Fog highlighted, the other visited Fogs and each person's trail."""
    everything = [tuple(c) for p in people for v in p["trail"] for c in v["cells"]]
    xs, ys = [c[0] for c in everything], [c[1] for c in everything]
    area_side = side_of(people[0]["at_fog"][0])
    fx, fy = _area(fog, area_side)
    margin = 4
    x0v, x1v = min(min(xs), fx) - margin, max(max(xs), fx + area_side) + margin
    y0v, y1v = min(min(ys), fy) - margin, max(max(ys), fy + area_side) + margin

    fig, ax = plt.subplots(figsize=(7 + _legend_width(len(people)), 7), layout="constrained")
    visited = dict.fromkeys((v["fog"], side_of(v)) for p in people for v in p["trail"])
    for f, size in visited:
        x0, y0 = _area(f, size)
        highlight = f == fog
        ax.add_patch(Rectangle((x0, y0), size, size, facecolor="#f2c94c" if highlight else "#dddddd",
                               alpha=0.35 if highlight else 0.25, edgecolor="#b8860b" if highlight else "#999999",
                               linewidth=2 if highlight else 0.8))
        vx0, vx1 = max(x0, x0v), min(x0 + size, x1v)
        vy0, vy1 = max(y0, y0v), min(y0 + size, y1v)
        if vx0 < vx1 and vy0 < vy1:
            ax.text(vx0 + 1, vy1 - 1, f"Fog {f}", ha="left", va="top", fontsize=10,
                    alpha=0.8 if highlight else 0.5, weight="bold", clip_on=True)

    _plot_people(ax, people)
    ax.set_xlim(x0v, x1v)
    ax.set_ylim(y0v, y1v)
    ax.set_aspect("equal")
    ax.set_title(title)
    ax.set_xlabel("x (500 m cells)")
    ax.set_ylabel("y (500 m cells)")
    _legend_outside(fig, ax, len(people))
    return fig


def fog_presence(people: list[dict], fog: int):
    """Timeline: when each person was at the queried Fog (colored) and in the rest of the day (gray)."""
    fig, ax = plt.subplots(figsize=(9, 0.55 * len(people) + 1.6))
    for i, p in enumerate(people):
        for v in p["trail"]:
            start = v["arrivalT"] / 2
            duration = (v["departureT"] - v["arrivalT"] + 1) / 2
            here = v["fog"] == fog
            ax.barh(i, duration, left=start, height=0.6 if here else 0.3,
                    color=_color(i) if here else "#cccccc", edgecolor="white", linewidth=1)
    ax.set_yticks(range(len(people)), [p["label"] for p in people])
    ax.invert_yaxis()
    ax.set_xlim(0, 24)
    ax.set_xticks(range(0, 25, 2), [f"{h:02d}h" for h in range(0, 25, 2)])
    ax.set_xlabel("time of day")
    ax.grid(axis="x", alpha=0.3)
    ax.set_title(f"Presence at Fog {fog} (colored) and at other Fogs (gray)")
    fig.tight_layout()
    return fig


# ====================================================================== whole city on a day

def day_trails(day: int, url: str = config.TMS_URL) -> dict[str, list[dict]]:
    """Every visit of the day (all Fog nodes, one TMS query), grouped into one trail per pseudonym."""
    resp = requests.get(f"{url}/visits", params={"day": day}, timeout=30)
    resp.raise_for_status()
    trails: dict[str, list[dict]] = {}
    for v in sorted(resp.json(), key=lambda v: v["arrivalT"]):
        trails.setdefault(v["h"], []).append(v)
    return trails


def city_people(trails: dict[str, list[dict]], chosen: list[str]) -> list[dict]:
    return [{"label": f"Person {i} ({h[:8]}…)", "h": h, "trail": trails[h]} for i, h in enumerate(chosen, 1)]


def city_summary(trails: dict[str, list[dict]]) -> dict:
    visits = [v for t in trails.values() for v in t]
    return {
        "people": len(trails),
        "visits": len(visits),
        "pings": sum(v["pings"] for v in visits),
        "handovers": sum(1 for v in visits if v["reason"] == "handover"),
        "fogs": len({v["fog"] for v in visits}),
    }


def _people_per_fog(trails: dict[str, list[dict]]) -> dict[int, int]:
    people: dict[int, set] = {}
    for h, trail in trails.items():
        for v in trail:
            people.setdefault(v["fog"], set()).add(h)
    return {f: len(hs) for f, hs in people.items()}


def city_map(people: list[dict], trails: dict[str, list[dict]], title: str):
    """Map of the whole city: every Fog area shaded by how many people passed through it on the day
    (counting everyone), with the trails of the sampled people on top."""
    area_side = side_of(next(iter(trails.values()))[0])
    per_axis = config.GRID // area_side
    counts = _people_per_fog(trails)
    norm = Normalize(vmin=0, vmax=max(counts.values()))
    cmap = plt.cm.YlOrRd
    fig, ax = plt.subplots(figsize=(7 + _legend_width(len(people)), 7.8), layout="constrained")
    label_size = max(5, 11 - per_axis // 2)
    for f in range(1, per_axis * per_axis + 1):
        x0, y0 = _area(f, area_side)
        n = counts.get(f, 0)
        ax.add_patch(Rectangle((x0, y0), area_side, area_side, facecolor=cmap(norm(n)), alpha=0.45,
                               edgecolor="#999999", linewidth=0.6))
        ax.text(x0 + area_side * 0.04, y0 + area_side * 0.96, f"Fog {f}\n{n}", ha="left", va="top",
                fontsize=label_size, alpha=0.75, weight="bold")
    _plot_people(ax, people)
    lo, hi = 0.5 - 2, config.GRID + 0.5 + 2
    ax.set_xlim(lo, hi)
    ax.set_ylim(lo, hi)
    ax.set_aspect("equal")
    ax.set_title(title)
    ax.set_xlabel("x (500 m cells)")
    ax.set_ylabel("y (500 m cells)")
    bar = fig.colorbar(plt.cm.ScalarMappable(norm=norm, cmap=cmap), ax=ax, location="bottom", shrink=0.6,
                       aspect=40, pad=0.01)
    bar.set_label("people who passed through the Fog")
    _legend_outside(fig, ax, len(people))
    return fig


def city_table(trails: dict[str, list[dict]]) -> list[dict]:
    """One row per Fog node: people, visits, pings and handovers that left / arrived."""
    rows: dict[int, dict] = {}
    for h, trail in trails.items():
        for v in trail:
            r = rows.setdefault(v["fog"], {"Fog": v["fog"], "people": set(), "visits": 0, "pings": 0,
                                           "handovers out": 0, "handovers in": 0})
            r["people"].add(h)
            r["visits"] += 1
            r["pings"] += v["pings"]
            if v["reason"] == "handover" and v.get("nextFog"):
                r["handovers out"] += 1
                rows.setdefault(v["nextFog"], {"Fog": v["nextFog"], "people": set(), "visits": 0, "pings": 0,
                                               "handovers out": 0, "handovers in": 0})["handovers in"] += 1
    out = []
    for f in sorted(rows):
        r = dict(rows[f])
        r["people"] = len(r["people"])
        out.append(r)
    return out


def city_presence(people: list[dict]):
    """Timeline of the sampled people: which Fog node served each one at each time of the day."""
    fig, ax = plt.subplots(figsize=(14, 0.45 * len(people) + 1.6), layout="constrained")
    for i, p in enumerate(people):
        for v in p["trail"]:
            start = v["arrivalT"] / 2
            duration = (v["departureT"] - v["arrivalT"] + 1) / 2
            ax.barh(i, duration, left=start, height=0.6, color=plt.cm.tab20.colors[(v["fog"] - 1) % 20],
                    edgecolor="white", linewidth=1)
            if duration >= 1:  # room for the Fog number inside the bar
                ax.text(start + duration / 2, i, str(v["fog"]), ha="center", va="center", fontsize=7)
    ax.set_yticks(range(len(people)), [p["label"] for p in people])
    ax.invert_yaxis()
    ax.set_xlim(0, 24)
    ax.set_xticks(range(0, 25, 1), [f"{h:02d}h" for h in range(0, 25, 1)])
    ax.set_xlabel("time of day")
    ax.grid(axis="x", alpha=0.3)
    ax.set_title("Fog node serving each sampled person over the day (number = Fog)")
    return fig
