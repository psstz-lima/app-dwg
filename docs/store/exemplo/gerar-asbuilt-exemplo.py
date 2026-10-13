import io, random, runpy, contextlib
with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    g = runpy.run_path("rodovia.py")
random.seed(11)
at, terrain, length, E0, N0, norm, sub = g["at"], g["terrain"], g["length"], g["E0"], g["N0"], g["norm"], g["sub"]
out = []
for k in range(80):
    s = random.uniform(20, length - 20); p = at(s); q = at(s + 1); d = norm(sub(q, p))
    off = random.uniform(-12, 12); x, y = p[0] + d[1] * off, p[1] - d[0] * off
    z = terrain(x - E0, y - N0) + random.gauss(0, 0.03) + (random.choice((0.12, -0.15, 0.2)) if random.random() < 0.12 else 0)
    out.append(f"AB{k + 1},TN,{x:.3f},{y:.3f},{z:.3f}")
print("\r\n".join(out))
