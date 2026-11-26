#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据管线：中国 CPI（AkShare）+ 美联储政策利率（官方 FRED）
--------------------------------------------------------------
CPI   : akshare.macro_china_cpi()  -> 国家统计局同比 2008-01 至今
利率  : FRED DFEDTAR(1982-2008) / DFEDTARU(2008-至今，目标区间上沿)
        为什么不用 AkShare 的 macro_bank_usa_interest_rate：
        该接口在 akshare 1.20.42 被移除；其背后金十数据中心镜像
        目前只到 2025-10 且最新一条为空值，不能当"真值"用。
        实测日：2026-11-26。
输出  : data.json（逐月序列）、payload.json（周期与统计）
"""
import json, os, re, sys, csv, datetime
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)

# ---------- 1. 中国 CPI：AkShare ----------
def fetch_cpi():
    import akshare as ak
    df = ak.macro_china_cpi()
    rows = []
    for _, r in df.iterrows():
        m = re.match(r"(\d{4})年(\d{1,2})月份", str(r["月份"]))
        if not m:
            continue
        yoy = pd.to_numeric(r["全国-同比增长"], errors="coerce")
        if pd.isna(yoy):
            continue
        rows.append([f"{m.group(1)}-{int(m.group(2)):02d}", round(float(yoy), 2)])
    rows.sort()
    print(f"[CPI ] akshare.macro_china_cpi -> {len(rows)} 行，{rows[0][0]} ~ {rows[-1][0]}")
    return rows

# ---------- 2. 政策利率：FRED ----------
def read_fred(name):
    p = os.path.join(HERE, f"{name}.csv")
    out = []
    with open(p, newline="") as f:
        for d, v in list(csv.reader(f))[1:]:
            try:
                out.append((d, float(v)))
            except ValueError:
                continue
    print(f"[RATE] {name}.csv -> {len(out)} 条，{out[0][0]} ~ {out[-1][0]}，末值 {out[-1][1]:.2f}")
    return out

def month_end_rate(series, months):
    """每个自然月取最后一个观测值（政策利率为台阶状，月末值即当月状态）"""
    by_month = {}
    for d, v in series:
        by_month[d[:7]] = v
    res, last = [], None
    for m in months:
        if m in by_month:
            last = by_month[m]
        res.append(last)
    return res

def build():
    cpi = fetch_cpi()
    months = [m for m, _ in cpi]
    cpi_val = [v for _, v in cpi]

    early = read_fred("DFEDTAR")     # 1982-09 ~ 2008-12
    late = read_fred("DFEDTARU")     # 2008-12-16 ~ 今
    merged = [x for x in early if x[0] < "2008-12-16"] + late
    rate = month_end_rate(merged, months)
    # 首月缺失时用最近的前值兜底
    first = next((v for v in rate if v is not None), None)
    rate = [first if v is None else v for v in rate]

    return months, cpi_val, rate, merged

# ---------- 3. 周期切分 ----------
def split_cycles(months, rate):
    diffs = []
    for i in range(1, len(rate)):
        d = round(rate[i] - rate[i - 1], 4)
        if abs(d) > 1e-9:
            diffs.append((i - 1, i, d))  # 起点下标、终点下标、变化

    segs = []
    for a, b, d in diffs:
        direction = 1 if d > 0 else -1
        if segs and segs[-1]["dir"] == direction and a <= segs[-1]["endIdx"] + 3:
            segs[-1]["endIdx"] = b
            segs[-1]["bp"] = round(rate[b] - rate[segs[-1]["startIdx"]], 4)
        else:
            segs.append({"dir": direction, "startIdx": a, "endIdx": b,
                         "bp": round(rate[b] - rate[a], 4)})

    cycles = []
    for s in segs:
        i0, i1 = s["startIdx"], s["endIdx"]
        cycles.append({
            "type": "加息" if s["dir"] > 0 else "降息",
            "start": months[i0], "end": months[i1],
            "from": round(rate[i0], 2), "to": round(rate[i1], 2),
            "bp": int(round(s["bp"] * 100)),
            "months": i1 - i0 + 1,
            "cpiAvg": round(sum(cpi_val[i0:i1 + 1]) / (i1 - i0 + 1), 2),
            "i0": i0, "i1": i1,
            "open": i1 == len(months) - 1,
        })
    return cycles

if __name__ == "__main__":
    months, cpi_val, rate, merged = build()
    cycles = split_cycles(months, rate)

    up = [c for c in cycles if c["type"] == "加息"]
    dn = [c for c in cycles if c["type"] == "降息"]

    def wavg(cs):
        w = sum(c["months"] for c in cs)
        return round(sum(c["cpiAvg"] * c["months"] for c in cs) / w, 2)

    import statistics
    r = statistics.correlation(cpi_val, rate)

    data = {"months": months, "cpi": cpi_val, "rate": [round(v, 2) for v in rate]}
    json.dump(data, open("data.json", "w"), ensure_ascii=False)

    payload = {
        "asOf": months[-1],
        "n": len(months),
        "start": months[0],
        "cycles": cycles,
        "stats": {
            "hikeCount": len(up), "hikeBp": sum(c["bp"] for c in up), "hikeCpi": wavg(up),
            "cutCount": len(dn), "cutBp": sum(c["bp"] for c in dn), "cutCpi": wavg(dn),
            "corr": round(r, 2),
            "rateNow": round(rate[-1], 2), "cpiNow": cpi_val[-1],
            "cpi12": round(sum(cpi_val[-12:]) / 12, 2),
        },
    }
    json.dump(payload, open("payload.json", "w"), ensure_ascii=False, indent=1)

    print(f"[OUT ] {len(months)} 个月 {months[0]}~{months[-1]}，{len(cycles)} 轮"
          f"（加 {len(up)} / 降 {len(dn)}）")
    print(f"[STAT] 加息 CPI 加权 {payload['stats']['hikeCpi']}% / +{payload['stats']['hikeBp']}bp；"
          f"降息 {payload['stats']['cutCpi']}% / {payload['stats']['cutBp']}bp；r={payload['stats']['corr']}")
    for c in cycles:
        print(f"  {c['type']} {c['start']}→{c['end']} {c['from']}→{c['to']} ({c['bp']:+d}bp) CPI均 {c['cpiAvg']}")
