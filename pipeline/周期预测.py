#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本轮周期转折与剩余时长：经验生存模型（1982-09 起全历史）
----------------------------------------------------------
思路：政策利率是台阶函数，历史上每一轮同向变动（加息/降息）都有明确的
时长与累计幅度。用一个周期"走到第几个月的存活率"给出转折的经验概率：
    S(t) = P(一轮周期持续时间 ≥ t)      —— 经验频率
    h(t) = 条件结束概率                 —— 已走 t 月仍未结束时的月度转折风险
本轮（2026-08 起、截至 2026-11 已走 4 个月）代入 S / h 得到剩余时长与
未来 3/6/12 个月结束（或延续）的概率。样本量小，只作为分布参考。
"""
import csv, json, statistics, os

HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)

GAP = 12          # 同向段之间 ≤12 个月的平台期并为一轮
NOW_MONTH = "2026-11"


def load_rate():
    rows = []
    early = list(csv.reader(open("DFEDTAR.csv")))[1:] + list(csv.reader(open("DFEDTARU.csv")))[1:]
    for d, v in early:
        try:
            rows.append((d, float(v)))
        except ValueError:
            pass
    monthly = {}
    for d, v in rows:
        monthly[d[:7]] = v
    months = sorted(monthly)
    return months, [monthly[m] for m in months]


def split(months, vals):
    segs = []
    for i in range(1, len(vals)):
        d = round(vals[i] - vals[i - 1], 4)
        if abs(d) < 1e-9:
            continue
        dr = 1 if d > 0 else -1
        if segs and segs[-1]["dir"] == dr and (i - 1) - segs[-1]["end"] <= GAP:
            segs[-1]["end"] = i
        else:
            segs.append({"dir": dr, "start": i - 1, "end": i})
    return [{
        "dir": "up" if s["dir"] > 0 else "down",
        "start": months[s["start"]], "end": months[s["end"]],
        "len": s["end"] - s["start"] + 1,
        "bp": int(round((vals[s["end"]] - vals[s["start"]]) * 100)),
    } for s in segs]


def survival(durations):
    """S(t)：时长 ≥ t 的经验概率"""
    n = len(durations)
    return [{"t": t, "s": round(sum(1 for x in durations if x >= t) / n, 3)}
            for t in range(1, max(durations) + 1)]


def main():
    months, vals = load_rate()
    cycles = split(months, vals)
    cur = cycles[-1]
    if cur["end"] < NOW_MONTH:  # 理论上最后一段是开放段
        pass

    elapsed = (int(NOW_MONTH[:4]) - int(cur["start"][:4])) * 12 + \
              (int(NOW_MONTH[5:]) - int(cur["start"][5:])) + 1

    same = [c for c in cycles[:-1] if c["dir"] == cur["dir"]]
    durs = sorted(c["len"] for c in same)
    bps = sorted(c["bp"] for c in same)
    n = len(same)

    med = statistics.median(durs)
    q1 = durs[int(round(0.25 * (n - 1)))]
    q3 = durs[int(round(0.75 * (n - 1)))]
    rem_med = max(med - elapsed, 0)
    rem_lo = max(q1 - elapsed, 0)
    rem_hi = max(q3 - elapsed, 0)

    # 条件概率：已走 elapsed 月仍在延续
    alive = [d for d in durs if d > elapsed]
    p_end_within = {}
    for h in (3, 6, 12):
        if not alive:
            p_end_within[h] = 1.0
        else:
            ended = sum(1 for d in alive if d <= elapsed + h)
            p_end_within[h] = round(ended / len(alive), 2)
    p_continue_12 = round(1 - p_end_within[12], 2)

    out = {
        "asOf": NOW_MONTH,
        "current": {"start": cur["start"], "elapsed": elapsed, "bp": cur["bp"], "dir": cur["dir"]},
        "history": {"from": months[0], "to": months[-1], "cycles": len(cycles),
                    "sameDir": n, "medDur": med, "q1": q1, "q3": q3,
                    "medBp": int(statistics.median(bps)), "minDur": durs[0], "maxDur": durs[-1]},
        "forecast": {"remMed": rem_med, "remLo": rem_lo, "remHi": rem_hi,
                     "endWithin3": p_end_within[3], "endWithin6": p_end_within[6],
                     "endWithin12": p_end_within[12], "continue12": p_continue_12},
        "survival": survival(durs),
        "samples": same,
    }
    json.dump(out, open("pred.json", "w"), ensure_ascii=False, indent=1)

    print(f"历史 {months[0]}~{months[-1]}，{len(cycles)} 轮，同类 {n} 轮")
    print(f"本轮 {cur['dir']} 起于 {cur['start']}，已走 {elapsed} 个月，累计 {cur['bp']:+d}bp")
    print(f"同类时长 中位 {med} 月（{q1}–{q3}），幅度中位 {out['history']['medBp']:+d}bp")
    print(f"剩余时长中位 {rem_med} 月，区间 {rem_lo}–{rem_hi} 月")
    print("未来结束概率", p_end_within, "12 个月仍延续", p_continue_12)


if __name__ == "__main__":
    main()
