import math, random, sys
random.seed(7)
E0, N0 = 612400.0, 7795800.0
W, H, STEP = 1800.0, 1300.0, 20.0

def terrain(x, y):
    # rolling hills, a valley with a stream running roughly south-north, and a ridge
    z = 820.0
    z += 38 * math.sin(x / 310.0) * math.cos(y / 260.0)
    z += 22 * math.sin((x + 2 * y) / 420.0 + 1.3)
    z += 9 * math.sin(x / 95.0 + 0.7) * math.sin(y / 120.0)
    z += 0.035 * y - 0.012 * x
    valley = 900 + 140 * math.sin(y / 300.0)
    z -= 26 * math.exp(-((x - valley) / 110.0) ** 2)
    return z

pts, idx = [], {}
nx, ny = int(W / STEP) + 1, int(H / STEP) + 1
for i in range(nx):
    for j in range(ny):
        jx = 0 if i in (0, nx - 1) else random.uniform(-5, 5)
        jy = 0 if j in (0, ny - 1) else random.uniform(-5, 5)
        x, y = i * STEP + jx, j * STEP + jy
        pts.append((E0 + x, N0 + y, terrain(x, y) + random.uniform(-0.15, 0.15)))
        idx[i, j] = len(pts)
faces = []
for i in range(nx - 1):
    for j in range(ny - 1):
        a, b, c, d = idx[i, j], idx[i + 1, j], idx[i + 1, j + 1], idx[i, j + 1]
        faces += [(a, b, c), (a, c, d)] if (i + j) % 2 else [(a, b, d), (b, c, d)]

# Alignment through PIs with circular curves.
PIs = [(150, 120), (520, 330), (760, 300), (1050, 620), (1180, 980), (1600, 1180)]
R = [0, 260, 220, 300, 240, 0]
PIs = [(E0 + x, N0 + y) for x, y in PIs]
def sub(a, b): return (a[0] - b[0], a[1] - b[1])
def norm(v): l = math.hypot(*v); return (v[0] / l, v[1] / l)
elems, cur = [], PIs[0]
for k in range(1, len(PIs) - 1):
    p, a, b = PIs[k], norm(sub(PIs[k], PIs[k - 1])), norm(sub(PIs[k + 1], PIs[k]))
    delta = math.acos(max(-1, min(1, a[0] * b[0] + a[1] * b[1])))
    T = R[k] * math.tan(delta / 2)
    pc = (p[0] - a[0] * T, p[1] - a[1] * T)
    pt = (p[0] + b[0] * T, p[1] + b[1] * T)
    cross = a[0] * b[1] - a[1] * b[0]
    rot = "ccw" if cross > 0 else "cw"
    nrm = (-a[1], a[0]) if cross > 0 else (a[1], -a[0])
    ctr = (pc[0] + nrm[0] * R[k], pc[1] + nrm[1] * R[k])
    elems.append(("L", cur, pc, math.dist(cur, pc)))
    elems.append(("C", pc, pt, R[k] * delta, ctr, rot, R[k]))
    cur = pt
elems.append(("L", cur, PIs[-1], math.dist(cur, PIs[-1])))
length = sum(e[3] for e in elems)

def at(s):
    for e in elems:
        if s <= e[3] or e is elems[-1]:
            if e[0] == "L":
                d = norm(sub(e[2], e[1])); return (e[1][0] + d[0] * s, e[1][1] + d[1] * s)
            c, r = e[4], e[6]; a0 = math.atan2(e[1][1] - c[1], e[1][0] - c[0])
            a = a0 + (s / r if e[5] == "ccw" else -s / r)
            return (c[0] + r * math.cos(a), c[1] + r * math.sin(a))
        s -= e[3]

# Grade: PVIs that follow the ground loosely.
pv = [0.0, 380.0, 820.0, 1250.0, 1700.0, length]
gz = [terrain(*sub(at(s), (E0, N0))) for s in pv]
gz = [gz[0] + 0.5] + [z + d for z, d in zip(gz[1:-1], (-3.5, 4.0, -2.5, 3.0))] + [gz[-1] + 0.5]

f = sys.stdout
w = f.write
w('<?xml version="1.0" encoding="UTF-8"?>\n<LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="1.2">\n')
w('  <Units><Metric linearUnit="meter" areaUnit="squareMeter" volumeUnit="cubicMeter" angularUnit="decimal degrees"/></Units>\n')
w('  <CoordinateSystem epsgCode="31983"/>\n  <Application name="Gromatic exemplo"/>\n  <CgPoints>\n')
for k in range(14):
    s = (k + 0.5) * length / 14; p = at(s); off = random.choice((-1, 1)) * random.uniform(8, 25)
    q = at(min(s + 1, length)); d = norm(sub(q, p)) if q != p else (1, 0)
    x, y = p[0] - d[1] * off, p[1] + d[0] * off
    w(f'    <CgPoint name="{k + 1}" code="{random.choice(("TN", "TN", "BORDO", "CERCA", "MARCO"))}">{y:.3f} {x:.3f} {terrain(x - E0, y - N0):.3f}</CgPoint>\n')
w('  </CgPoints>\n  <Surfaces>\n    <Surface name="TERRENO NATURAL">\n      <Definition surfType="TIN">\n        <Pnts>\n')
for n, (x, y, z) in enumerate(pts, 1): w(f'          <P id="{n}">{y:.3f} {x:.3f} {z:.3f}</P>\n')
w('        </Pnts>\n        <Faces>\n')
for a, b, c in faces: w(f'          <F>{a} {b} {c}</F>\n')
w('        </Faces>\n      </Definition>\n    </Surface>\n  </Surfaces>\n  <Alignments>\n')
w(f'    <Alignment name="EIXO PRINCIPAL" length="{length:.3f}" staStart="0.000">\n      <CoordGeom>\n')
for e in elems:
    if e[0] == "L": w(f'        <Line length="{e[3]:.3f}"><Start>{e[1][1]:.3f} {e[1][0]:.3f}</Start><End>{e[2][1]:.3f} {e[2][0]:.3f}</End></Line>\n')
    else: w(f'        <Curve rot="{e[5]}" radius="{e[6]:.3f}" length="{e[3]:.3f}"><Start>{e[1][1]:.3f} {e[1][0]:.3f}</Start><Center>{e[4][1]:.3f} {e[4][0]:.3f}</Center><End>{e[2][1]:.3f} {e[2][0]:.3f}</End></Curve>\n')
w('      </CoordGeom>\n      <Profile name="GREIDE">\n        <ProfAlign name="GREIDE">\n')
w(f'          <PVI>0.000 {gz[0]:.3f}</PVI>\n')
for s, z in zip(pv[1:-1], gz[1:-1]): w(f'          <ParaCurve length="160.000">{s:.3f} {z:.3f}</ParaCurve>\n')
w(f'          <PVI>{length:.3f} {gz[-1]:.3f}</PVI>\n        </ProfAlign>\n      </Profile>\n')

def grade(s):
    # Tangents between PVIs with symmetric parabolas of 160 m.
    for k in range(1, len(pv) - 1):
        L = 160.0; g1 = (gz[k] - gz[k - 1]) / (pv[k] - pv[k - 1]); g2 = (gz[k + 1] - gz[k]) / (pv[k + 1] - pv[k])
        if abs(s - pv[k]) <= L / 2:
            x = s - (pv[k] - L / 2); return gz[k] - g1 * L / 2 + g1 * x + (g2 - g1) * x * x / (2 * L)
    for k in range(1, len(pv)):
        if s <= pv[k]: return gz[k - 1] + (gz[k] - gz[k - 1]) * (s - pv[k - 1]) / (pv[k] - pv[k - 1])
    return gz[-1]

def ground(s, off):
    p = at(s); q = at(min(s + 0.5, length)); r = at(max(s - 0.5, 0))
    d = norm(sub(q, r)); x, y = p[0] + d[1] * off, p[1] - d[0] * off   # positive offset to the right
    return terrain(x - E0, y - N0)

w('      <CrossSects>\n')
s = 0.0
while s <= length + 1e-6:
    z = grade(s)
    base = [(0.0, z, "eixo"), (3.5, z - 0.07, "bordo"), (6.0, z - 0.195, "acostamento")]
    def side(sign):
        out = [(sign * o, e, c) for o, e, c in base]
        o, e = 6.0, z - 0.195
        if ground(s, sign * o) > e:  # ditch, then 1:1 cut slope up to the ground
            out += [(sign * 6.5, e - 0.5, "valeta"), (sign * 7.0, e, "valeta borda")]
            for k in range(1, 400):
                oo, ee = 7.0 + 0.1 * k, e + 0.1 * k
                if ee >= ground(s, sign * oo): out.append((sign * oo, ee, "crista")); break
        else:  # 1.5:1 fill slope down to the ground
            for k in range(1, 600):
                oo, ee = o + 0.15 * k, e - 0.1 * k
                if ee <= ground(s, sign * oo): out.append((sign * oo, ee, "pé")); break
        return out
    allp = sorted({(round(o, 3), round(e, 3), c) for o, e, c in side(-1) + side(1)}, key=lambda t: t[0])
    w(f'        <CrossSect sta="{s:.3f}">\n          <DesignCrossSectSurf name="Acabada">')
    for o, e, c in allp: w(f'<CrossSectPnt code="{c}">{o + 0.0:.3f} {e:.3f}</CrossSectPnt>')
    w('</DesignCrossSectSurf>\n        </CrossSect>\n')
    s += 20.0
w('      </CrossSects>\n    </Alignment>\n  </Alignments>\n</LandXML>\n')
print(f"len {length:.1f} pts {len(pts)} faces {len(faces)}", file=sys.stderr)
