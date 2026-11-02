"""Render one inning segment of the shared game video (1080x1920, 9:16, H.264/AAC).

Job JSON (built by handler.py from the server's RENDER_SEGMENT message, clip paths already downloaded):
{
  "header": {"awayTeamCode", "homeTeamCode", "awayScore", "homeScore", "datetime", "stadium"},
  "inning": 3, "half": "TOP" | "BOTTOM" | null, "solo": bool,
  "footer": {"handle", "team"},
  "members": [{"name", "team", "clips": [{"path", "time", "caption", "score"}]}]   # first = requester
}

Pillow draws the static layers (header, card chrome, per-clip text); FFmpeg places each member's clips inside
their rounded card in one encode. A member with several clips in the inning plays them in capture order;
a member with none gets a "이 시간대 기록 없음" card. The segment is as long as the longest card; shorter cards
hold their last frame.
"""
import json
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920
FONT = os.environ.get("FONT", "/opt/assets/fonts/regular.ttf")
FONT_BOLD = os.environ.get("FONT_BOLD", FONT)
# Team logos are baked into the Lambda image as <TEAM_LOGO_DIR>/<kbo_teams.team_code>.png (transparent PNG),
# e.g. LG.png, OB.png, HT.png
TEAM_LOGO_DIR = os.environ.get("TEAM_LOGO_DIR", "/opt/assets/teams")
LOGO_SIZE = 136
# Instagram story/reel UI (progress bar, profile) covers roughly the top 150px of a 1080x1920 frame
SAFE_TOP = 160
INK = (25, 25, 25)
QUIET = (130, 130, 130)
CARD = (78, 78, 78)
FPS = 30


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, int(size))


def layout(n):
    """Card rectangles for n members. Up to 3 stack in one column, more go in two columns."""
    left, right, top = 45, W - 45, SAFE_TOP + 270
    bottom = H - 170  # room for the "@handle · team / 이닝로그" footer
    gap = 26
    cols = 1 if n <= 3 else 2
    rows = math.ceil(n / cols)
    cw = (right - left - gap * (cols - 1)) / cols
    ch = (bottom - top - gap * (rows - 1)) / rows
    rects = []
    for i in range(n):
        r, c = divmod(i, cols)
        x, y = left + c * (cw + gap), top + r * (ch + gap)
        rects.append((round(x), round(y), round(cw) // 2 * 2, round(ch) // 2 * 2))
    return rects


def team_logo(canvas, cx, cy, team_code):
    """Paste the team's logo centred on (cx, cy), fitted into a LOGO_SIZE square."""
    path = os.path.join(TEAM_LOGO_DIR, f"{team_code}.png")
    if not os.path.exists(path):
        raise FileNotFoundError(f"team logo missing: {path}")
    logo = Image.open(path).convert("RGBA")
    logo.thumbnail((LOGO_SIZE, LOGO_SIZE), Image.LANCZOS)
    canvas.paste(logo, (round(cx - logo.width / 2), round(cy - logo.height / 2)), logo)


def centered(draw, xy, text, f, fill, bold=False):
    # a same-colour stroke thickens the glyphs when no bold font file is installed
    stroke = max(1, round(f.size / 28)) if bold and FONT_BOLD == FONT else 0
    draw.text(xy, text, font=f, fill=fill, anchor="mm", stroke_width=stroke, stroke_fill=fill)


def text_scale(h):
    return max(0.62, min(1.0, h / 1450 + 0.25))


def draw_layers(job, rects, out_dir):
    """base.png (under the videos), top.png (always on top) and clip_<m>_<c>.png (on top while that clip plays)."""
    hd = job["header"]
    solo = job.get("solo")
    base = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(base)
    team_logo(base, 190, SAFE_TOP + 90, hd["awayTeamCode"])
    team_logo(base, W - 190, SAFE_TOP + 90, hd["homeTeamCode"])
    d.text((W / 2, SAFE_TOP + 68), f'{hd["awayScore"]}  :  {hd["homeScore"]}', font=font(68, True), fill=INK, anchor="mm")
    d.text((W / 2, SAFE_TOP + 135), hd["datetime"], font=font(30), fill=QUIET, anchor="mm")
    d.text((W / 2, SAFE_TOP + 172), hd["stadium"], font=font(30), fill=QUIET, anchor="mm")

    top = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    t = ImageDraw.Draw(top)
    rad = 64 if len(rects) == 1 else 48
    for mi, (m, (x, y, w, h)) in enumerate(zip(job["members"], rects)):
        if not m["clips"]:
            d.rounded_rectangle([x, y, x + w, y + h], rad, fill=CARD)
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], rad, fill=255)
        mask.save(f"{out_dir}/mask_{w}x{h}.png")
        s = text_scale(h)
        cr = 26 * s
        t.ellipse([x + 30, y + 30, x + 30 + 2 * cr, y + 30 + 2 * cr], fill="white")
        t.text((x + 30 + cr, y + 30 + cr), str(job["inning"]), font=font(cr * 1.2, True), fill=INK, anchor="mm")
        if not solo:
            centered(t, (x + w / 2, y + 30 + cr), f'{m["name"]} · {m["team"]}', font(36 * s), "white")
        if not m["clips"]:
            centered(t, (x + w / 2, y + h / 2), "이 시간대 기록 없음", font(40 * s), "white")
            continue
        for ci, clip in enumerate(m["clips"]):
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            c = ImageDraw.Draw(layer)
            # solo: the footer already names the owner, so the top line shows the score instead
            if solo:
                centered(c, (x + w / 2, y + 30 + cr), clip["score"], font(48 * s), "white")
            centered(c, (x + w / 2, y + h / 2 - 30 * s), clip["time"], font(84 * s, True), "white", bold=True)
            centered(c, (x + w / 2, y + h / 2 + 45 * s), clip["caption"], font(44 * s), "white")
            if not solo:
                centered(c, (x + w - 76 * s, y + h / 2), clip["score"], font(42 * s), "white")
            layer.save(f"{out_dir}/clip_{mi}_{ci}.png")
    footer = job["footer"]
    d.text((W / 2, H - 112), f'@{footer["handle"]} · {footer["team"]}', font=font(30), fill=INK, anchor="mm")
    d.text((W / 2, H - 70), "이닝로그", font=font(30), fill=INK, anchor="mm")
    base.save(f"{out_dir}/base.png")
    top.save(f"{out_dir}/top.png")


def probe_duration(path):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path])
    return float(out)


def has_audio(path):
    out = subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index",
                                   "-of", "csv=p=0", path])
    return bool(out.strip())


def render(job, out_path, work_dir):
    """Renders the segment to out_path and returns its duration in seconds."""
    rects = layout(len(job["members"]))
    draw_layers(job, rects, work_dir)
    for m in job["members"]:
        for clip in m["clips"]:
            clip["duration"] = probe_duration(clip["path"])
    totals = [sum(c["duration"] for c in m["clips"]) for m in job["members"]]
    dur = max(totals)
    if dur <= 0:
        raise ValueError("segment has no playable clips")

    args = ["ffmpeg", "-y", "-loglevel", "error", "-loop", "1", "-framerate", str(FPS), "-t", f"{dur:.3f}",
            "-i", f"{work_dir}/base.png"]
    idx = 1
    filters = []
    chain = "[0:v]"
    audio_member = None  # the requester's audio, or the next card's when the requester has no clip here
    for mi, (m, (x, y, w, h)) in enumerate(zip(job["members"], rects)):
        if not m["clips"]:
            continue
        parts = []
        for ci, clip in enumerate(m["clips"]):
            args += ["-i", clip["path"]]
            clip["input"] = idx
            # fill the card without stretching: scale until both sides cover it, then crop the centre
            filters.append(
                f"[{idx}:v]trim=duration={clip['duration']:.3f},setpts=PTS-STARTPTS,fps={FPS},"
                f"scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h},setsar=1,format=yuv420p[v{mi}_{ci}]")
            parts.append(f"[v{mi}_{ci}]")
            idx += 1
        joined = f"[j{mi}]"
        if len(parts) == 1:
            filters.append(f"{parts[0]}null{joined}")
        else:
            filters.append(f"{''.join(parts)}concat=n={len(parts)}:v=1:a=0{joined}")
        args += ["-loop", "1", "-i", f"{work_dir}/mask_{w}x{h}.png"]
        mask = idx
        idx += 1
        # hold the last frame when this card is shorter than the segment
        filters.append(
            f"{joined}tpad=stop_mode=clone:stop_duration={dur:.3f},trim=duration={dur:.3f},format=yuva420p[c{mi}];"
            f"[{mask}:v]format=gray,scale={w}:{h}[m{mi}];[c{mi}][m{mi}]alphamerge[r{mi}];"
            f"{chain}[r{mi}]overlay={x}:{y}:shortest=1[s{mi}]")
        chain = f"[s{mi}]"
        if audio_member is None:
            audio_member = m

    args += ["-loop", "1", "-i", f"{work_dir}/top.png"]
    filters.append(f"{chain}[{idx}:v]overlay=0:0:shortest=1[t0]")
    chain = "[t0]"
    idx += 1
    # per-clip text, shown while that clip plays (the last clip's text stays during the held frame)
    n = 0
    for mi, m in enumerate(job["members"]):
        start = 0.0
        for ci, clip in enumerate(m["clips"]):
            end = dur if ci == len(m["clips"]) - 1 else start + clip["duration"]
            args += ["-loop", "1", "-i", f"{work_dir}/clip_{mi}_{ci}.png"]
            filters.append(f"{chain}[{idx}:v]overlay=0:0:shortest=1:enable='between(t,{start:.3f},{end:.3f})'[x{n}]")
            chain = f"[x{n}]"
            idx += 1
            n += 1
            start += clip["duration"]
    filters.append(f"{chain}format=yuv420p[v]")

    audio_parts = []
    for ci, clip in enumerate(audio_member["clips"]):
        if has_audio(clip["path"]):
            filters.append(f"[{clip['input']}:a]aresample=48000,aformat=channel_layouts=stereo,apad,"
                           f"atrim=duration={clip['duration']:.3f},asetpts=PTS-STARTPTS[a{ci}]")
        else:
            args += ["-f", "lavfi", "-t", f"{clip['duration']:.3f}", "-i", "anullsrc=r=48000:cl=stereo"]
            filters.append(f"[{idx}:a]anull[a{ci}]")
            idx += 1
        audio_parts.append(f"[a{ci}]")
    filters.append(f"{''.join(audio_parts)}concat=n={len(audio_parts)}:v=0:a=1,apad,atrim=duration={dur:.3f}[a]")

    args += ["-filter_complex", ";".join(filters), "-map", "[v]", "-map", "[a]", "-t", f"{dur:.3f}",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-profile:v", "high", "-pix_fmt", "yuv420p",
             "-r", str(FPS), "-c:a", "aac", "-ar", "48000", "-ac", "2", "-b:a", "128k", "-movflags", "+faststart",
             out_path]
    subprocess.run(args, check=True)
    return probe_duration(out_path)


if __name__ == "__main__":
    # Local preview: python render_segment.py job.json out.mp4 (clip "path" values are local files)
    with open(sys.argv[1], encoding="utf-8") as f:
        job = json.load(f)
    out = os.path.abspath(sys.argv[2])
    print(render(job, out, os.path.dirname(out)))
